package com.close.hook.ads.ui.fragment

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnFocusChangeListener
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.close.hook.ads.R
import com.close.hook.ads.data.model.FilterBean
import com.close.hook.ads.databinding.UniversalWithTabsBinding
import com.close.hook.ads.ui.activity.MainActivity
import com.close.hook.ads.ui.viewmodel.AppsViewModel
import com.close.hook.ads.util.AppUtils
import com.close.hook.ads.util.IOnTabClickContainer
import com.close.hook.ads.util.IOnTabClickListener
import com.close.hook.ads.util.OnBackPressContainer
import com.close.hook.ads.util.OnBackPressListener
import com.close.hook.ads.util.OnCLearCLickContainer
import com.close.hook.ads.util.OnClearClickListener
import com.close.hook.ads.util.OnSetHintListener
import com.close.hook.ads.util.PrefManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayout.OnTabSelectedListener
import com.google.android.material.tabs.TabLayoutMediator
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.PublishSubject
import java.util.concurrent.TimeUnit

class InstalledAppsFragment : BaseFragment<UniversalWithTabsBinding>(), OnBackPressListener,
    OnCLearCLickContainer, OnSetHintListener, IOnTabClickContainer {

    private val viewModel by lazy { ViewModelProvider(this)[AppsViewModel::class.java] }
    private var searchSubject: PublishSubject<String> = PublishSubject.create()
    private var searchDisposable: Disposable? = null
    private var imm: InputMethodManager? = null
    override var tabController: IOnTabClickListener? = null
    override var controller: OnClearClickListener? = null
    private lateinit var bottomSheetDialog: BottomSheetDialog
    private lateinit var reverseSwitch: CheckBox
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        imm = requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeViews()
        setupViewPagerAndTabs()
    }

    private fun initializeViews() {
        setupSearchIcon()
        setupSearchEditText()
        setupSearchObservable()
        setupSearchClear()
        setupFilter()
        setupSearchFilter()
    }

    @SuppressLint("InflateParams")
    private fun setupFilter() {
        viewModel.filterBean = FilterBean()
        viewModel.sortList = ArrayList<String>().apply {
            if (PrefManager.configured) add("已配置")
            if (PrefManager.updated) add("最近更新")
            if (PrefManager.disabled) add("已禁用")
        }
        viewModel.filterBean.title = PrefManager.order
        viewModel.filterBean.filter = viewModel.sortList

        bottomSheetDialog = BottomSheetDialog(requireContext())
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.bottom_dialog_search_filter, null)
        bottomSheetDialog.setContentView(dialogView)
        setupFilterListeners(dialogView)
    }

    private fun setupSearchFilter() {
        binding.searchFilter.setOnClickListener { bottomSheetDialog.show() }
    }

    private fun setupFilterListeners(dialogView: View) {
        val toolbar = dialogView.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { bottomSheetDialog.dismiss() }

        val sortBy = dialogView.findViewById<ChipGroup>(R.id.sort_by)
        val filter = dialogView.findViewById<ChipGroup>(R.id.filter)

        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_reset) {
                resetFilters(sortBy, filter)
            }
            false
        }

        reverseSwitch = dialogView.findViewById(R.id.reverse_switch)
        reverseSwitch.isChecked = PrefManager.isReverse
        reverseSwitch.setOnCheckedChangeListener { _, isReverse ->
            PrefManager.isReverse = isReverse
            viewModel.isFilter = true
            controller?.updateSortList(
                viewModel.filterBean,
                binding.searchEditText.text.toString(),
                isReverse
            )
        }

        setupChipGroup(
            sortBy,
            listOf("应用名称", "应用大小", "最近更新时间", "安装日期", "Target 版本"),
            true
        )
        setupChipGroup(filter, listOf("已配置", "最近更新", "已禁用"), false)
    }

    private fun setupChipGroup(chipGroup: ChipGroup, titles: List<String>, isSortBy: Boolean) {
        chipGroup.isSingleSelection = isSortBy
        titles.forEach { title ->
            chipGroup.addView(getChip(title, isSortBy))
        }
    }

    private fun getChip(title: String, isSortBy: Boolean): Chip {
        return Chip(requireContext()).apply {
            if (title == "应用名称")
                id = 0
            text = title
            isCheckable = true
            isClickable = true
            isChecked = when {
                isSortBy && title == PrefManager.order -> true
                title == "已配置" && PrefManager.configured -> true
                title == "最近更新" && PrefManager.updated -> true
                title == "已禁用" && PrefManager.disabled -> true
                else -> false
            }
            setOnClickListener {
                chipClickAction(this, title, isSortBy)
            }
        }
    }

    private fun chipClickAction(chip: Chip, title: String, isSortBy: Boolean) {
        if (!isSortBy && title == "已配置" && !MainActivity.isModuleActivated()) {
            AppUtils.showToast(requireContext(), "模块尚未被激活")
            chip.isChecked = false
            return
        }

        if (!isSortBy) {
            val isChecked = chip.isChecked
            if (isChecked) viewModel.sortList.add(title) else viewModel.sortList.remove(title)
            when (title) {
                "已配置" -> PrefManager.configured = isChecked
                "最近更新" -> PrefManager.updated = isChecked
                "已禁用" -> PrefManager.disabled = isChecked
            }
            showFilterToast(title, isChecked)
        } else {
            PrefManager.order = title
            viewModel.filterBean.title = title
            AppUtils.showToast(
                requireContext(),
                "${requireContext().getString(R.string.sort_by_default)}: $title"
            )
        }
        viewModel.filterBean.filter = viewModel.sortList
        viewModel.isFilter = true
        controller?.updateSortList(
            viewModel.filterBean,
            binding.searchEditText.text.toString(),
            PrefManager.isReverse
        )
    }

    private fun showFilterToast(title: String, isEnabled: Boolean) {
        val message = if (isEnabled) {
            "${requireContext().getString(R.string.filter_enabled)}: $title"
        } else {
            "${requireContext().getString(R.string.filter_disabled)}: $title"
        }
        AppUtils.showToast(requireContext(), message)
    }

    private fun resetFilters(sortBy: ChipGroup, filter: ChipGroup) {
        sortBy.check(0)
        filter.clearCheck()
        PrefManager.apply {
            isReverse = false
            order = "应用名称"
            configured = false
            updated = false
            disabled = false
        }
        reverseSwitch.isChecked = false
        viewModel.apply {
            filterBean.title = "应用名称"
            sortList.clear()
            isFilter = false
        }
        controller?.updateSortList(viewModel.filterBean, "", false)
    }

    private fun setupViewPagerAndTabs() {
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun createFragment(position: Int): Fragment {
                return when (position) {
                    0 -> AppsFragment.newInstance("user")
                    1 -> AppsFragment.newInstance("configured")
                    2 -> AppsFragment.newInstance("system")
                    else -> throw IllegalArgumentException()
                }
            }

            override fun getItemCount() = 3

        }
        TabLayoutMediator(
            binding.tabLayout, binding.viewPager
        ) { tab: TabLayout.Tab, position: Int ->
            val tabList = listOf(
                R.string.tab_user_apps, R.string.tab_configured_apps, R.string.tab_system_apps
            )
            tab.setText(getString(tabList[position]))
        }.attach()
        binding.tabLayout.addOnTabSelectedListener(object : OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {}
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {
                tabController!!.onReturnTop()
            }
        })
    }

    private fun setupSearchClear() {
        binding.searchClear.setOnClickListener { binding.searchEditText.setText("") }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun setupSearchIcon() {
        binding.searchIcon.setOnClickListener {
            if (binding.searchEditText.isFocused) {
                binding.searchEditText.setText("")
                setIconAndFocus(R.drawable.ic_search, false)
            } else {
                setIconAndFocus(R.drawable.ic_back, true)
            }
        }
    }

    private fun setupSearchEditText() {
        binding.searchEditText.onFocusChangeListener = OnFocusChangeListener { _, hasFocus ->
            setIconAndFocus(if (hasFocus) R.drawable.ic_back else R.drawable.ic_search, hasFocus)
        }
        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                searchSubject.onNext(s.toString())
            }

            override fun afterTextChanged(s: Editable) {
                binding.searchClear.visibility =
                    if (s.toString().isEmpty()) View.GONE else View.VISIBLE
            }
        })
    }

    private fun setupSearchObservable() {
        searchDisposable = searchSubject
            .debounce(300, TimeUnit.MILLISECONDS)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { query -> performSearch(query) }
    }

    private fun performSearch(query: String) {
        viewModel.isFilter = true
        controller?.updateSortList(
            viewModel.filterBean, query, PrefManager.isReverse
        )
    }

    private fun setIconAndFocus(drawableId: Int, focus: Boolean) {
        binding.searchIcon.setImageDrawable(getDrawable(drawableId))
        if (focus) {
            binding.searchEditText.requestFocus()
            imm?.showSoftInput(binding.searchEditText, 0)
        } else {
            binding.searchEditText.clearFocus()
            imm?.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
        }
    }

    private fun getDrawable(drawableId: Int): Drawable? {
        return requireContext().getDrawable(drawableId)
    }

    override fun onDestroy() {
        super.onDestroy()
        searchDisposable?.dispose()
        bottomSheetDialog.dismiss()
    }

    override fun onBackPressed(): Boolean {
        if (binding.searchEditText.isFocused) {
            binding.searchEditText.setText("")
            setIconAndFocus(R.drawable.ic_search, false)
            return true
        }
        return false
    }

    override fun onStop() {
        super.onStop()
        (requireContext() as OnBackPressContainer).controller = null
    }

    override fun onResume() {
        super.onResume()
        (requireContext() as OnBackPressContainer).controller = this
    }

    override fun setHint(totalApp: Int) {
        if (totalApp != 0) {
            binding.searchEditText.hint = "搜索 " + totalApp + "个应用"
        } else {
            binding.searchEditText.hint = "搜索"
        }
    }
}
