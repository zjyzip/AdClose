package com.close.hook.ads.ui.fragment

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
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
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import java.util.Locale
import java.util.Optional
import java.util.function.Predicate
import java.util.stream.Collectors

class AppsFragment : BaseFragment<FragmentAppsBinding>(), OnClearClickListener,
    IOnTabClickListener {

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
    }

    private fun setupRecyclerView() {
        appsAdapter = AppsAdapter(requireContext(), object : AppsAdapter.OnItemClickListener {
            override fun onItemClick(packageName: String) {
                handleItemClick(packageName)
            }

            override fun onItemLongClick(packageName: String) {
                handleItemLongClick(packageName)
            }
        })

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
                        (activity as? INavContainer)?.hideNavigation()
                    } else if (dy < 0) {
                        (activity as? INavContainer)?.showNavigation()
                    }
                }
            })
        }
    }

    private fun handleItemClick(packageName: String) {
        val appInfo = viewModel.appInfoList.find { it.packageName == packageName }
        if (appInfo != null) {
            if (MainActivity.isModuleActivated()) {
                showBottomSheetDialog(appInfo)
            } else {
                AppUtils.showToast(requireContext(), "模块尚未被激活")
            }
        }
    }

    private fun handleItemLongClick(packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.fromParts("package", packageName, null)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            AppUtils.showToast(requireContext(), "无法打开应用设置")
            e.printStackTrace()
        }
    }

    private fun setupLiveDataObservation() {
        val appInfoMediatorLiveData = MediatorLiveData<List<AppInfo>>()
    
        fun addSourceAndProcess(liveData: LiveData<List<AppInfo>>, type: String) {
            appInfoMediatorLiveData.addSource(liveData) { apps ->
                appInfoMediatorLiveData.value = processAppInfoList(apps, type)
            }
        }
    
        if (type == "configured" || type == "user") {
            addSourceAndProcess(viewModel.userAppsLiveData, "user")
        }
        if (type == "configured" || type == "system") {
            addSourceAndProcess(viewModel.systemAppsLiveData, "system")
        }
    
        appInfoMediatorLiveData.observe(viewLifecycleOwner) { combinedAppInfoList ->
            handleCombinedAppInfoList(combinedAppInfoList)
        }
    }

    private fun processAppInfoList(appInfoList: List<AppInfo>, appType: String): List<AppInfo> {
        return appInfoList.filter { appInfo ->
            when (type) {
                "configured" -> appInfo.isEnable == 1
                else -> true
            }
        }
    }

    private fun handleCombinedAppInfoList(combinedAppInfoList: List<AppInfo>) {
        if (viewModel.appInfoList.isEmpty()) {
            viewModel.appInfoList.addAll(combinedAppInfoList)
            viewModel.isFilter = true
            updateSortList(viewModel.filterBean, "", PrefManager.isReverse)
            binding.progressBar.visibility = View.GONE
        }
    }

    private fun showBottomSheetDialog(appInfo: AppInfo) {
        bottomSheetDialog = BottomSheetDialog(requireContext())
        val binding = BottomDialogSwitchesBinding.inflate(layoutInflater, null, false)
        bottomSheetDialog.setContentView(binding.root)
        setupBottomSheetDialogBinding(binding, appInfo)
        bottomSheetDialog.show()
    }

    private fun setupBottomSheetDialogBinding(binding: BottomDialogSwitchesBinding, appInfo: AppInfo) {
        binding.apply {
            sheetAppName.text = appInfo.appName
            buttonClose.setOnClickListener { bottomSheetDialog.dismiss() }
            version.text = appInfo.versionName
            icon.setImageDrawable(AppUtils.getAppIcon(appInfo.packageName))
        }
        setupListeners(binding.root, appInfo)
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
        val safeAppInfoList = viewModel.appInfoList ?: emptyList()

        val filteredList = if (filterBean.filter.isNotEmpty()) {
            filterBean.filter.fold(safeAppInfoList) { list, title ->
                list.filter { appInfo ->
                    getAppInfoFilter(title, keyWord)(appInfo)
                }
            }
        } else {
            safeAppInfoList.filter { getAppInfoFilter(null, keyWord)(it) }
        }

        val comparator = getAppInfoComparator(filterBean.title).let { if (isReverse) it.reversed() else it }
        val sortedList = filteredList.sortedWith(comparator)

        viewModel.filterList.clear()
        viewModel.filterList.addAll(sortedList)
        appsAdapter.submitList(sortedList)
        (requireParentFragment() as OnSetHintListener).setHint(sortedList.size)
    }

    private fun getAppInfoComparator(title: String): Comparator<AppInfo> {
    return when (title) {
            "应用大小" -> compareBy { it.size }
            "最近更新时间" -> compareBy { it.lastUpdateTime }
            "安装日期" -> compareBy { it.firstInstallTime }
            "Target 版本" -> compareBy { it.targetSdk }
            else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.appName }
        }
    }

    private fun getAppInfoFilter(title: String?, keyWord: String): (AppInfo) -> Boolean {
        val time = 3 * 24 * 3600L
        return { appInfo: AppInfo ->
            when (title) {
                "已配置" -> appInfo.isEnable == 1 && appInfo.matchesKeyword(keyWord)
                "最近更新" -> System.currentTimeMillis() / 1000 - appInfo.lastUpdateTime / 1000 < time && appInfo.matchesKeyword(keyWord)
                "已禁用" -> appInfo.isAppEnable == 0 && appInfo.matchesKeyword(keyWord)
                else -> appInfo.matchesKeyword(keyWord)
            }
        }
    }

    private fun AppInfo.matchesKeyword(keyWord: String): Boolean {
        val lowerCaseKeyword = keyWord.lowercase(Locale.getDefault())
        return this.appName.lowercase(Locale.getDefault()).contains(lowerCaseKeyword) ||
               this.packageName.lowercase(Locale.getDefault()).contains(lowerCaseKeyword)
    }

    override fun onDestroyView() {
        super.onDestroyView()
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

    override fun onStop() {
        super.onStop()
        (requireParentFragment() as OnCLearCLickContainer).controller = null
        (requireParentFragment() as IOnTabClickContainer).tabController = null
    }

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
