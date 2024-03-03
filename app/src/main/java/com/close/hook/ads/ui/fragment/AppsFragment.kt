package com.close.hook.ads.ui.fragment

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.R
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.data.model.FilterBean
import com.close.hook.ads.databinding.BottomDialogAppInfoBinding
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
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

        setupSwipeRefreshLayout()
        observeLiveData()

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
            } else {
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

            override fun onItemLongClick(appInfo: AppInfo) {
                handleItemLongClick(appInfo)
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

    private fun setupSwipeRefreshLayout() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            viewModel.refreshApps(type ?: "user")
        }
    }

    private fun observeLiveData() {
        viewModel.userAppsLiveData.observe(viewLifecycleOwner) { apps ->
            if (type == "user") {
                appsAdapter.submitList(apps)
                binding.swipeRefreshLayout.isRefreshing = false
            }
        }

        viewModel.systemAppsLiveData.observe(viewLifecycleOwner) { apps ->
            if (type == "system") {
                appsAdapter.submitList(apps)
                binding.swipeRefreshLayout.isRefreshing = false
            }
        }

        //configured
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

    private fun handleItemLongClick(appInfo: AppInfo) {
        val bottomSheetDialog = BottomSheetDialog(requireContext())
        val binding = BottomDialogAppInfoBinding.inflate(layoutInflater)
        bottomSheetDialog.setContentView(binding.root)
        setupBottomDialogAppInfoBinding(binding, appInfo)
        bottomSheetDialog.show()
        setupDialogActions(bottomSheetDialog, binding, appInfo)
    }

    private fun setupDialogActions(bottomSheetDialog: BottomSheetDialog, binding: BottomDialogAppInfoBinding, appInfo: AppInfo) {
        binding.close.setOnClickListener {
            bottomSheetDialog.dismiss()
        }
        binding.detail.setOnClickListener {
            openAppDetails(appInfo.packageName)
            bottomSheetDialog.dismiss()
        }
        binding.launch.setOnClickListener {
            launchApp(appInfo.packageName)
            bottomSheetDialog.dismiss()
        }
    }

    private fun openAppDetails(packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            AppUtils.showToast(requireContext(), "无法打开应用详情")
        }
    }

    private fun launchApp(packageName: String) {
        val intent = requireContext().packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                AppUtils.showToast(requireContext(), "打开失败")
            }
        } else {
            AppUtils.showToast(requireContext(), "打开失败")
        }
    }

    private fun getFormatSize(size: Long): String {
        val df = DecimalFormat("0.00")
        return when {
            size >= 1.shl(30) -> "${df.format(size / 1.shl(30).toFloat())}GB"
            size >= 1.shl(20) -> "${df.format(size / 1.shl(20).toFloat())}MB"
            size >= 1.shl(10) -> "${df.format(size / 1.shl(10).toFloat())}KB"
            else -> "$size B"
        }
    }

    private fun setupBottomDialogAppInfoBinding(binding: BottomDialogAppInfoBinding, appInfo: AppInfo) {
        with(binding) {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            icon.setImageDrawable(AppUtils.getAppIcon(appInfo.packageName))
            appName.text = appInfo.appName
            packageName.apply {
                title.text = "APK包名"
                value.text = appInfo.packageName
            }
            appSize.apply {
                title.text = "APK大小"
                value.text = getFormatSize(appInfo.size)
            }
            versionName.apply {
                title.text = "版本名称"
                value.text = appInfo.versionName
            }
            versionCode.apply {
                title.text = "版本号"
                value.text = appInfo.versionCode.toString()
            }
            targetSdk.apply {
                title.text = "TargetSDK"
                value.text = appInfo.targetSdk.toString()
            }
            minSdk.apply {
                title.text = "MinSDK"
                value.text = appInfo.minSdk.toString()
            }
            installTime.apply {
                title.text = "安装时间"
                value.text = dateFormat.format(Date(appInfo.firstInstallTime))
            }
            updateTime.apply {
                title.text = "更新时间"
                value.text = dateFormat.format(Date(appInfo.lastUpdateTime))
            }
        }
    }

    private fun setupLiveDataObservation() {
        val appInfoMediatorLiveData = MediatorLiveData<List<AppInfo>>()
    
        if (type == "configured" && !MainActivity.isModuleActivated()) {
            AppUtils.showToast(requireContext(), "模块尚未被激活")
            binding.progressBar.visibility = View.GONE
            return
        }

        when (type) {
            "configured", "user" -> appInfoMediatorLiveData.addSource(viewModel.userAppsLiveData) { apps ->
                appInfoMediatorLiveData.value = processAppInfoList(apps)
            }

            "configured", "system" -> appInfoMediatorLiveData.addSource(viewModel.systemAppsLiveData) { apps ->
                appInfoMediatorLiveData.value = processAppInfoList(apps)
            }
        }

        appInfoMediatorLiveData.observe(viewLifecycleOwner) { combinedAppInfoList ->
            if (combinedAppInfoList.isNotEmpty()) {
                handleCombinedAppInfoList(combinedAppInfoList)
            }
        }
    }

    private fun processAppInfoList(appInfoList: List<AppInfo>): List<AppInfo> {
        return if (type == "configured") {
            appInfoList.filter { it.isEnable == 1 }
        } else {
            appInfoList
        }
    }

    private fun handleCombinedAppInfoList(combinedAppInfoList: List<AppInfo>) {
        if (viewModel.appInfoList != combinedAppInfoList) {
            viewModel.appInfoList.clear()
            viewModel.appInfoList.addAll(combinedAppInfoList)
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

    private fun setupBottomSheetDialogBinding(
        binding: BottomDialogSwitchesBinding,
        appInfo: AppInfo
    ) {
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

        val comparator =
            getAppInfoComparator(filterBean.title).let { if (isReverse) it.reversed() else it }
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
                "最近更新" -> System.currentTimeMillis() / 1000 - appInfo.lastUpdateTime / 1000 < time && appInfo.matchesKeyword(
                    keyWord
                )

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
