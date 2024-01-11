package com.close.hook.ads.ui.fragment

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.R
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.data.model.FilterBean
import com.close.hook.ads.databinding.BottomDialogSwitchesBinding
import com.close.hook.ads.databinding.FragmentAppsBinding
import com.close.hook.ads.hook.preference.PreferencesHelper
import com.close.hook.ads.ui.activity.MainActivity
import com.close.hook.ads.ui.adapter.AppsAdapter
import com.close.hook.ads.ui.viewmodel.AppsViewModel
import com.close.hook.ads.util.AppUtils
import com.close.hook.ads.util.INavContainer
import com.close.hook.ads.util.IOnTabClickContainer
import com.close.hook.ads.util.IOnTabClickListener
import com.close.hook.ads.util.LinearItemDecoration
import com.close.hook.ads.util.OnCLearCLickContainer
import com.close.hook.ads.util.OnClearClickListener
import com.close.hook.ads.util.OnSetHintListener
import com.close.hook.ads.util.PrefManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import java.util.Locale
import java.util.Optional
import java.util.function.Predicate
import java.util.stream.Collectors

class AppsFragment : BaseFragment<FragmentAppsBinding>(), OnClearClickListener,
    IOnTabClickListener {

    private val disposables = CompositeDisposable()
    private val viewModel by lazy { ViewModelProvider(this)[AppsViewModel::class.java] }
    private lateinit var appsAdapter: AppsAdapter
    private var type: String? = null
    private lateinit var bottomSheetDialog: BottomSheetDialog
    private lateinit var selectAll: MaterialCheckBox
    private var totalCheck = 0
    private val checkHashMap = HashMap<String, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        type = arguments?.getString("type")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        if (viewModel.appInfoList.isEmpty() && viewModel.filterList.isEmpty()) {
            viewModel.filterBean = FilterBean()
            viewModel.sortList = ArrayList<String>()
            if (PrefManager.configured) {
                viewModel.sortList.add("已配置")
            }
            if (PrefManager.updated) {
                viewModel.sortList.add("最近更新")
            }
            if (PrefManager.disabled) {
                viewModel.sortList.add("已禁用")
            }
            viewModel.filterBean.title = PrefManager.order
            viewModel.filterBean.filter = viewModel.sortList
            setupLiveDataObservation()
        } else {

            val newList = ArrayList<AppInfo>()

            if (viewModel.isFilter) {
                newList.addAll(viewModel.filterList)
            }
            else {
                newList.addAll(viewModel.appInfoList)
            }
            appsAdapter.submitList(newList)
            binding.progressBar.visibility = View.GONE
        }
        setupAdapterItemClick()
    }

    private fun setupRecyclerView() {
        appsAdapter = AppsAdapter()
        FastScrollerBuilder(binding.recyclerViewApps).useMd2Style().build()
        val space = resources.getDimensionPixelSize(ITEM_DECORATION_SPACE)
        binding.recyclerViewApps.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = appsAdapter
            addItemDecoration(LinearItemDecoration(space))

            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    if (dy > 0) {
                        (activity as INavContainer).hideNavigation()
                    } else if (dy < 0) {
                        (activity as INavContainer).showNavigation()
                    }

                }
            })
        }
    }

    private fun setupLiveDataObservation() {
        when (type) {
            "configured" -> {
                val userAppsLiveData: LiveData<List<AppInfo>> = viewModel.userAppsLiveData
                userAppsLiveData.observe(viewLifecycleOwner) { appInfoList ->
                    if (viewModel.appInfoList.isEmpty()) {
                        viewModel.appInfoList.addAll(appInfoList.filter { it.isEnable == 1 })
                        val systemAppsLiveData: LiveData<List<AppInfo>> =
                            viewModel.systemAppsLiveData
                        systemAppsLiveData.observe(viewLifecycleOwner) { systemAppInfoList ->
                            if (binding.progressBar.visibility == View.VISIBLE) {
                                viewModel.appInfoList.addAll(systemAppInfoList.filter { it.isEnable == 1 })
                                viewModel.isFilter = true
                                viewModel.filterBean.filter.clear()
                                updateSortList(viewModel.filterBean, "", PrefManager.isReverse)
                                binding.progressBar.visibility = View.GONE
                            }
                        }
                    }
                }
            }

            else -> {
                val appsLiveData: LiveData<List<AppInfo>> =
                    if (type == "user") viewModel.userAppsLiveData
                    else viewModel.systemAppsLiveData
                appsLiveData.observe(viewLifecycleOwner) { appInfoList ->
                    if (viewModel.appInfoList.isEmpty()) {
                        binding.progressBar.visibility = View.GONE
                        viewModel.appInfoList.addAll(appInfoList)
                        viewModel.isFilter = true
                        updateSortList(viewModel.filterBean, "", PrefManager.isReverse)
                    }
                }
            }
        }
    }

    private fun setupAdapterItemClick() {
        disposables.add(appsAdapter.onClickObservable.observeOn(AndroidSchedulers.mainThread())
            .subscribe { appInfo: AppInfo ->
                if (MainActivity.isModuleActivated()) {
                    showBottomSheetDialog(appInfo)
                } else {
                    AppUtils.showToast(requireContext(), "模块尚未被激活")
                }
            })
        disposables.add(appsAdapter.onLongClickObservable.observeOn(AndroidSchedulers.mainThread())
            .subscribe { appInfo: AppInfo ->
                val intent = Intent()
                intent.setAction("android.settings.APPLICATION_DETAILS_SETTINGS")
                intent.setData(Uri.fromParts("package", appInfo.packageName, null))
                try {
                    requireContext().startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    AppUtils.showToast(requireContext(), "打开失败")
                    e.printStackTrace()
                }
            })
    }

    @SuppressLint("InflateParams")
    private fun showBottomSheetDialog(appInfo: AppInfo) {
        bottomSheetDialog = BottomSheetDialog(requireContext())
        val binding = BottomDialogSwitchesBinding.inflate(layoutInflater, null, false)
        bottomSheetDialog.setContentView(binding.root)
        binding.apply {
            sheetAppName.text = appInfo.appName
            buttonClose.setOnClickListener { bottomSheetDialog.dismiss() }
            version.text = appInfo.versionName
            icon.setImageDrawable(AppUtils.getAppIcon(appInfo.packageName))
        }
        setupListeners(binding.root, appInfo)
        bottomSheetDialog.show()
    }

    private fun updateCheckNum(packageName: String, isChecked: Boolean?) {
        isChecked?.let {
            val currentCount = checkHashMap[packageName] ?: 0
            val newCount = if (isChecked) currentCount + 1 else currentCount - 1
            checkHashMap[packageName] = newCount
        }
        when (checkHashMap[packageName]) {
            0 -> {
                selectAll.isChecked = false
                selectAll.checkedState = MaterialCheckBox.STATE_UNCHECKED
            }

            totalCheck -> {
                selectAll.isChecked = true
                selectAll.checkedState = MaterialCheckBox.STATE_CHECKED
            }

            else -> {
                selectAll.checkedState = MaterialCheckBox.STATE_INDETERMINATE
            }
        }
    }

    @SuppressLint("CommitPrefEdits")
    private fun setupListeners(dialogView: View, appInfo: AppInfo) {
        @SuppressLint("WorldReadableFiles") val prefsHelper =
            PreferencesHelper(dialogView.context, PREFERENCES_NAME)
        val checkBoxIds = intArrayOf(
            R.id.switch_one,
            R.id.switch_two,
            R.id.switch_three,
            R.id.switch_four,
            R.id.switch_five,
            R.id.switch_six,
        )
        val prefKeys = arrayOf(
            "switch_one_",
            "switch_two_",
            "switch_three_",
            "switch_four_",
            "switch_five_",
            "switch_six_",
        )

        selectAll = dialogView.findViewById(R.id.select_all)
        totalCheck = checkBoxIds.size
        checkHashMap[appInfo.packageName] = checkBoxIds.size
        for (i in checkBoxIds.indices) {
            val checkBoxView = dialogView.findViewById<MaterialCheckBox>(checkBoxIds[i])
            val key = prefKeys[i] + appInfo.packageName
            checkBoxView.isChecked = prefsHelper.getBoolean(key, false)
            if (!checkBoxView.isChecked) {
                checkHashMap[appInfo.packageName] =
                    checkHashMap[appInfo.packageName]!! - 1
            }
            if (i == checkBoxIds.size - 1) {
                updateCheckNum(appInfo.packageName, null)
            }
            checkBoxView.addOnCheckedStateChangedListener { checkBox, _ ->
                updateCheckNum(appInfo.packageName, checkBox.isChecked)
            }
        }

        val buttonUpdate = dialogView.findViewById<MaterialButton>(R.id.button_update)
        buttonUpdate.setOnClickListener {
            var total = 0
            for (i in checkBoxIds.indices) {
                val checkBoxView = dialogView.findViewById<MaterialCheckBox>(checkBoxIds[i])
                if (checkBoxView.isChecked)
                    total++
                val key = prefKeys[i] + appInfo.packageName
                prefsHelper.setBoolean(key, checkBoxView.isChecked)
            }
            val position = getAppPosition(appInfo.packageName)
            if (position != -1)
                if (total == 0)
                    viewModel.appInfoList[position].isEnable = 0
                else
                    viewModel.appInfoList[position].isEnable = 1
            AppUtils.showToast(requireContext(), "保存成功")
            bottomSheetDialog.dismiss()
        }

        selectAll.addOnCheckedStateChangedListener { _, state ->
            when (state) {
                MaterialCheckBox.STATE_CHECKED -> {
                    for (i in checkBoxIds.indices) {
                        val checkBoxView = dialogView.findViewById<MaterialCheckBox>(checkBoxIds[i])
                        checkBoxView.isChecked = true
                    }
                }

                MaterialCheckBox.STATE_UNCHECKED -> {
                    for (i in checkBoxIds.indices) {
                        val checkBoxView = dialogView.findViewById<MaterialCheckBox>(checkBoxIds[i])
                        checkBoxView.isChecked = false
                    }
                }
            }
        }

    }

    private fun getAppPosition(packageName: String): Int {
        for ((index, element) in viewModel.appInfoList.withIndex()) {
            if (element.packageName == packageName)
                return index
        }
        return -1
    }

    override fun updateSortList(filterBean: FilterBean, keyWord: String, isReverse: Boolean) {

        Log.d("fsdfsdfsdfdsfsdfsd", "filter: ${filterBean.filter}")
        Log.d("fsdfsdfsdfdsfsdfsd", "title: ${filterBean.title}")

        var safeAppInfoList =
            Optional.ofNullable<List<AppInfo>?>(viewModel.appInfoList).orElseGet { emptyList() }

        if (filterBean.filter.isNotEmpty())
            for (title in filterBean.filter) {
                safeAppInfoList =
                    safeAppInfoList.stream().filter(getAppInfoFilter(title, keyWord))
                        .collect(Collectors.toList())
            }
        else
            safeAppInfoList =
                safeAppInfoList.stream().filter(getAppInfoFilter(null, keyWord))
                    .collect(Collectors.toList())

        var comparator: Comparator<AppInfo> = getAppInfoComparator(filterBean.title)
        if (isReverse)
            comparator = comparator.reversed()
        disposables.add(
            Observable.fromIterable(safeAppInfoList).sorted(comparator).toList()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ sortedList: List<AppInfo?>? ->
                    viewModel.filterList.clear()
                    sortedList?.let {
                        viewModel.filterList.addAll(it)
                    }
                    appsAdapter.submitList(sortedList)
                    (requireParentFragment() as OnSetHintListener).setHint(
                        if (sortedList.isNullOrEmpty()) 0
                        else sortedList.size
                    )
                }, { throwable: Throwable? ->
                    Log.e("AppsFragment", "Error in updateSortList", throwable)
                })
        )
    }

    private fun getAppInfoComparator(title: String): Comparator<AppInfo> {
        return when (title) {
            "应用大小" -> Comparator.comparingLong { obj: AppInfo -> obj.size }

            "最近更新时间" -> Comparator.comparing { obj: AppInfo -> obj.lastUpdateTime }

            "安装日期" -> Comparator.comparing { obj: AppInfo -> obj.firstInstallTime }

            "Target 版本" -> Comparator.comparingInt { obj: AppInfo -> obj.targetSdk }

            else -> Comparator.comparing(
                { obj: AppInfo -> obj.appName }, java.lang.String.CASE_INSENSITIVE_ORDER
            )
        }
    }


    private fun getAppInfoFilter(title: String?, keyWord: String): Predicate<AppInfo> {
        val time = 3 * 24 * 3600L
        return when (title) {
            "已配置" -> Predicate<AppInfo> { appInfo: AppInfo ->
                ((appInfo.appName.lowercase(Locale.getDefault())
                    .contains(keyWord.lowercase(Locale.getDefault()))
                        || appInfo.packageName.lowercase(Locale.getDefault())
                    .contains(keyWord.lowercase(Locale.getDefault())))
                        && appInfo.isEnable == 1)
            }

            "最近更新" -> Predicate<AppInfo> { appInfo: AppInfo ->
                ((appInfo.appName.lowercase(Locale.getDefault())
                    .contains(keyWord.lowercase(Locale.getDefault()))
                        || appInfo.packageName.lowercase(Locale.getDefault())
                    .contains(keyWord.lowercase(Locale.getDefault())))
                        && System.currentTimeMillis() / 1000 - appInfo.lastUpdateTime / 1000 < time)
            }

            "已禁用" -> Predicate<AppInfo> { appInfo: AppInfo ->
                ((appInfo.appName.lowercase(Locale.getDefault())
                    .contains(keyWord.lowercase(Locale.getDefault()))
                        || appInfo.packageName.lowercase(Locale.getDefault())
                    .contains(keyWord.lowercase(Locale.getDefault())))
                        && appInfo.isAppEnable == 0)
            }

            else -> Predicate<AppInfo> { appInfo: AppInfo ->
                appInfo.appName.lowercase(Locale.getDefault())
                    .contains(keyWord.lowercase(Locale.getDefault()))
                        || appInfo.packageName.lowercase(Locale.getDefault())
                    .contains(keyWord.lowercase(Locale.getDefault()))
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        disposables.clear()
    }

    companion object {
        private const val PREFERENCES_NAME = "com.close.hook.ads_preferences"
        private val ITEM_DECORATION_SPACE = R.dimen.normal_space

        @JvmStatic
        fun newInstance(type: String): AppsFragment {
            val fragment = AppsFragment()
            val args = Bundle()
            args.putString("type", type)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onClearAll() {}
    override fun search(keyWord: String?) {}

    override fun onResume() {
        super.onResume()

        (requireParentFragment() as OnCLearCLickContainer).controller = this
        (requireParentFragment() as IOnTabClickContainer).tabController = this
        (requireParentFragment() as OnSetHintListener).setHint(
            if (viewModel.isFilter) viewModel.filterList.size
            else viewModel.appInfoList.size
        )

    }

    override fun onReturnTop() {
        binding.recyclerViewApps.scrollToPosition(0)
        (activity as MainActivity).showNavigation()
    }

}
