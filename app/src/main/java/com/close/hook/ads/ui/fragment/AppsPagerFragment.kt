package com.close.hook.ads.ui.fragment

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.close.hook.ads.R
import com.close.hook.ads.databinding.BottomDialogSearchFilterBinding
import com.close.hook.ads.databinding.UniversalWithTabsBinding
import com.close.hook.ads.ui.activity.MainActivity
import com.close.hook.ads.util.IOnTabClickContainer
import com.close.hook.ads.util.IOnTabClickListener
import com.close.hook.ads.util.OnBackPressContainer
import com.close.hook.ads.util.OnBackPressListener
import com.close.hook.ads.util.OnCLearCLickContainer
import com.close.hook.ads.util.OnClearClickListener
import com.close.hook.ads.util.PrefManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AppsPagerFragment : BaseFragment<UniversalWithTabsBinding>(), OnBackPressListener,
    IOnTabClickContainer, OnCLearCLickContainer {

    override var tabController: IOnTabClickListener? = null
    override var controller: OnClearClickListener? = null
    private val tabList =
        listOf(R.string.tab_user_apps, R.string.tab_configured_apps, R.string.tab_system_apps)
    private var imm: InputMethodManager? = null
    private var bottomSheet: BottomSheetDialog? = null
    private lateinit var filerBinding: BottomDialogSearchFilterBinding
    private var searchJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        imm = requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        initView()
        initEditText()
        initButton()
        initFilterSheet()

    }

    private fun initFilterSheet() {
        bottomSheet = BottomSheetDialog(requireContext())
        filerBinding = BottomDialogSearchFilterBinding.inflate(layoutInflater, null, false)
        bottomSheet?.setContentView(filerBinding.root)

        filerBinding.apply {
            toolbar.apply {
                setNavigationOnClickListener {
                    bottomSheet?.dismiss()
                }

                setOnMenuItemClickListener {
                    when (it.itemId) {
                        R.id.action_reset -> resetFilters()
                    }
                    return@setOnMenuItemClickListener false
                }
            }

            reverseSwitch.isChecked = PrefManager.isReverse
            reverseSwitch.setOnCheckedChangeListener { _, isReverse ->
                PrefManager.isReverse = isReverse
                controller?.updateSortList(
                    Pair(
                        PrefManager.order,
                        ArrayList<String>().apply {
                            if (PrefManager.configured) add("已配置")
                            if (PrefManager.updated) add("最近更新")
                            if (PrefManager.disabled) add("已禁用")
                        }
                    ),
                    binding.searchEditText.text.toString(),
                    reverseSwitch.isChecked
                )
            }

            setupChipGroup(
                sortBy,
                listOf("应用名称", "应用大小", "最近更新时间", "安装日期", "Target 版本"),
                true
            )
            setupChipGroup(filter, listOf("已配置", "最近更新", "已禁用"), false)
        }
    }

    private fun resetFilters() {
        filerBinding.sortBy.check(0)
        filerBinding.filter.clearCheck()
        PrefManager.apply {
            isReverse = false
            order = "应用名称"
            configured = false
            updated = false
            disabled = false
        }
        filerBinding.reverseSwitch.isChecked = false
        controller?.updateSortList(Pair("应用名称", listOf()), "", false)
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
            Snackbar.make(filerBinding.root, "模块尚未被激活", Snackbar.LENGTH_SHORT).show()
            chip.isChecked = false
            return
        }

        if (!isSortBy) {
            val isChecked = chip.isChecked
            when (title) {
                "已配置" -> PrefManager.configured = isChecked
                "最近更新" -> PrefManager.updated = isChecked
                "已禁用" -> PrefManager.disabled = isChecked
            }
            showFilterToast(title, isChecked)
        } else {
            PrefManager.order = title
            Snackbar.make(
                filerBinding.root,
                "${requireContext().getString(R.string.sort_by_default)}: $title",
                Snackbar.LENGTH_SHORT
            ).show()
        }
        controller?.updateSortList(
            Pair(
                PrefManager.order,
                ArrayList<String>().apply {
                    if (PrefManager.configured) add("已配置")
                    if (PrefManager.updated) add("最近更新")
                    if (PrefManager.disabled) add("已禁用")
                }
            ),
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
        Snackbar.make(filerBinding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun initButton() {
        binding.apply {
            searchIcon.setOnClickListener {
                if (binding.searchEditText.isFocused) {
                    binding.searchEditText.setText("")
                    setIconAndFocus(R.drawable.ic_search, false)
                } else {
                    setIconAndFocus(R.drawable.ic_back, true)
                }
            }

            searchClear.setOnClickListener { binding.searchEditText.setText("") }

            searchFilter.setOnClickListener { bottomSheet?.show() }
        }
    }

    private fun initEditText() {
        binding.searchEditText.onFocusChangeListener =
            View.OnFocusChangeListener { _, hasFocus ->
                setIconAndFocus(
                    if (hasFocus) R.drawable.ic_back else R.drawable.ic_search,
                    hasFocus
                )
            }
        binding.searchEditText.addTextChangedListener(textWatcher)
    }

    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            searchJob?.cancel()
            searchJob = lifecycleScope.launch {
                delay(if (s.isBlank()) 0L else 300L)
                val filterList = ArrayList<String>().apply {
                    if (PrefManager.configured) add("已配置")
                    if (PrefManager.updated) add("最近更新")
                    if (PrefManager.disabled) add("已禁用")
                }
                controller?.updateSortList(
                    Pair(PrefManager.order, filterList),
                    s.toString(),
                    PrefManager.isReverse
                )
            }
        }

        override fun afterTextChanged(s: Editable) {
            binding.searchClear.isVisible = s.isNotBlank()
        }
    }

    private fun setIconAndFocus(drawableId: Int, focus: Boolean) {
        binding.searchIcon.setImageDrawable(requireContext().getDrawable(drawableId))
        if (focus) {
            binding.searchEditText.requestFocus()
            imm?.showSoftInput(binding.searchEditText, 0)
        } else {
            binding.searchEditText.clearFocus()
            imm?.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
        }
    }

    private fun initView() {
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun createFragment(position: Int) =
                when (position) {
                    0 -> AppsFragment.newInstance("user")
                    1 -> AppsFragment.newInstance("configured")
                    2 -> AppsFragment.newInstance("system")
                    else -> throw IllegalArgumentException()
                }

            override fun getItemCount() = tabList.size
        }

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = getString(tabList[position])
        }.attach()

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {}
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {
                tabController?.onReturnTop()
            }
        })
    }

    fun setHint(totalApp: Int) {
        binding.searchEditText.hint = if (totalApp != 0) "搜索 ${totalApp}个应用"
        else "搜索"
    }

    override fun onStop() {
        super.onStop()
        (activity as? OnBackPressContainer)?.controller = null
    }

    override fun onResume() {
        super.onResume()
        (activity as? OnBackPressContainer)?.controller = this
    }

    override fun onBackPressed(): Boolean {
        if (binding.searchEditText.isFocused) {
            binding.searchEditText.setText("")
            setIconAndFocus(R.drawable.ic_search, false)
            return true
        }
        return false
    }

    override fun onDestroyView() {
        binding.searchEditText.removeTextChangedListener(textWatcher)
        super.onDestroyView()
        bottomSheet?.dismiss()
        bottomSheet = null
    }

}
