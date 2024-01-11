package com.close.hook.ads.ui.fragment

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.View.OnFocusChangeListener
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.CompoundButton
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.close.hook.ads.R
import com.close.hook.ads.data.model.FilterBean
import com.close.hook.ads.databinding.UniversalWithTabsBinding
import com.close.hook.ads.ui.activity.MainActivity
import com.close.hook.ads.ui.adapter.UniversalPagerAdapter
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
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.TimeUnit

class InstalledAppsFragment : BaseFragment<UniversalWithTabsBinding>(), OnBackPressListener,
    OnCLearCLickContainer, OnSetHintListener, IOnTabClickContainer {

    private val viewModel by lazy { ViewModelProvider(this)[AppsViewModel::class.java] }
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
        setupSearchClear()
        setupFilter()
        setupSearchFilter()
    }

    @SuppressLint("InflateParams")
    private fun setupFilter() {
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
        toolbar.setOnMenuItemClickListener { item: MenuItem ->
            if (item.itemId == R.id.action_reset) {
                sortBy.check(0)
                filter.clearCheck()
                PrefManager.isReverse = false
                reverseSwitch.isChecked = false
                PrefManager.order = "应用名称"
                PrefManager.configured = false
                PrefManager.updated = false
                PrefManager.disabled = false
                viewModel.filterBean.title = "应用名称"
                viewModel.sortList.clear()
                viewModel.isFilter = false
                controller?.updateSortList(
                    viewModel.filterBean, "", false
                )
            }
            false
        }
        reverseSwitch = dialogView.findViewById(R.id.reverse_switch)
        reverseSwitch.isChecked = PrefManager.isReverse
        reverseSwitch.setOnCheckedChangeListener { _: CompoundButton?, isReverse: Boolean ->
            PrefManager.isReverse = isReverse
            viewModel.isFilter = true
            controller?.updateSortList(
                viewModel.filterBean, binding.searchEditText.text.toString(), isReverse
            )
        }
        sortBy.isSingleSelection = true
        val sortList = listOf("应用名称", "应用大小", "最近更新时间", "安装日期", "Target 版本")
        for (title in sortList) {
            sortBy.addView(getChip(title))
        }
        filter.isSingleSelection = false
        val filterList = listOf("已配置", "最近更新", "已禁用")
        for (title in filterList) {
            filter.addView(getChip(title))
        }
    }

    private fun getChip(title: String): View {
        val chip = Chip(requireContext())
        chip.text = title
        chip.isCheckable = true
        chip.isClickable = true
        if (title == "应用名称") {
            chip.id = 0
        }
        if (title == PrefManager.order) {
            chip.isChecked = true
        } else if (title == "已配置" && PrefManager.configured) {
            chip.isChecked = true
        } else if (title == "最近更新" && PrefManager.updated) {
            chip.isChecked = true
        } else if (title == "已禁用" && PrefManager.disabled) {
            chip.isChecked = true
        }
        chip.setOnClickListener {
            if (title == "已配置" && !MainActivity.isModuleActivated()) {
                AppUtils.showToast(requireContext(), "模块尚未被激活")
                chip.isChecked = false
                return@setOnClickListener
            }
            if (title == "最近更新" || title == "已禁用" || title == "已配置") {
                if (chip.isChecked) viewModel.sortList.add(title) else viewModel.sortList.remove(
                    title
                )
                when (title) {
                    "已配置" -> PrefManager.configured = chip.isChecked
                    "最近更新" -> PrefManager.updated = chip.isChecked
                    "已禁用" -> PrefManager.disabled = chip.isChecked
                }
            } else {
                PrefManager.order = title
                viewModel.filterBean.title = title
            }
            viewModel.filterBean.filter = viewModel.sortList
            viewModel.isFilter = true
            controller?.updateSortList(
                viewModel.filterBean, binding.searchEditText.text.toString(), PrefManager.isReverse
            )
        }
        return chip
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

    private fun setIconAndFocus(drawableId: Int, focus: Boolean) {
        binding.searchIcon.setImageDrawable(requireContext().getDrawable(drawableId))
        if (focus) {
            binding.searchEditText.requestFocus()
            imm!!.showSoftInput(binding.searchEditText, 0)
        } else {
            binding.searchEditText.clearFocus()
            imm!!.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
        }
    }

    private fun setupViewPagerAndTabs() {
        val adapter = UniversalPagerAdapter(
            this, listOf<Fragment>(
                AppsFragment.newInstance("user"),
                AppsFragment.newInstance("configured"),
                AppsFragment.newInstance("system")
            )
        )
        binding.viewPager.adapter = adapter
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

    private fun setupSearchEditText() {
        binding.searchEditText.onFocusChangeListener =
            OnFocusChangeListener { _: View?, hasFocus: Boolean ->
                setIcon(if (hasFocus) R.drawable.ic_back else R.drawable.ic_search)
            }
        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (searchDisposable != null && !searchDisposable!!.isDisposed) {
                    searchDisposable!!.dispose()
                }
                if (binding.searchEditText.isFocused) {
                    searchDisposable =
                        Observable.just(s.toString()).debounce(300, TimeUnit.MILLISECONDS)
                            .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                            .subscribe { query: String -> performSearch(query) }
                }
            }

            override fun afterTextChanged(s: Editable) {
                binding.searchClear.visibility =
                    if (s.toString().isEmpty()) View.GONE else View.VISIBLE
            }

            private fun performSearch(query: String) {
                viewModel.isFilter = true
                controller?.updateSortList(
                    viewModel.filterBean, query, PrefManager.isReverse
                )
            }
        })
    }

    private fun setIcon(drawableId: Int) {
        binding.searchIcon.setImageDrawable(requireContext().getDrawable(drawableId))
    }

    override fun onDestroy() {
        super.onDestroy()
            searchDisposable?.dispose()
    }

    override fun onBackPressed(): Boolean {
        if (binding.searchEditText.isFocused) {
            binding.searchEditText.setText("")
            setIconAndFocus(R.drawable.ic_search, false)
            return true
        }
        return false
    }

    override fun onResume() {
        super.onResume()
        val onBackPressContainer = requireContext() as OnBackPressContainer
        onBackPressContainer.controller = this
    }

    override fun setHint(totalApp: Int) {
        if (totalApp != 0) {
            binding.searchEditText.hint = "搜索 " + totalApp + "个应用"
        } else {
            binding.searchEditText.hint = "搜索"
        }
    }
}
