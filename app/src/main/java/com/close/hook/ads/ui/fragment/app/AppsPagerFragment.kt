package com.close.hook.ads.ui.fragment.app

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.close.hook.ads.R
import com.close.hook.ads.databinding.BaseTablayoutViewpagerBinding
import com.close.hook.ads.databinding.BottomDialogSearchFilterBinding
import com.close.hook.ads.ui.activity.MainActivity
import com.close.hook.ads.ui.fragment.base.BasePagerFragment
import com.close.hook.ads.util.PrefManager
import com.close.hook.ads.util.dp
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar

class AppsPagerFragment : BasePagerFragment() {

    override val tabList: List<Int> =
        listOf(R.string.tab_user_apps, R.string.tab_configured_apps, R.string.tab_system_apps)
    private var bottomSheet: BottomSheetDialog? = null
    private lateinit var filerBinding: BottomDialogSearchFilterBinding
    private lateinit var filterBtn: ImageButton

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BaseTablayoutViewpagerBinding.inflate(inflater, container, false)
        setupFilterButton()
        return binding.root
    }

    private fun setupFilterButton() {
        filterBtn = ImageButton(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
            setPadding(16.dp, 0, 16.dp, 0)
            setImageResource(R.drawable.ic_filter)

            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)

            binding.searchContainer.addView(this)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initFilterSheet()
    }

    override fun initButton() {
        super.initButton()
        filterBtn.setOnClickListener { bottomSheet?.show() }
    }

    private fun initFilterSheet() {
        bottomSheet = BottomSheetDialog(requireContext())
        filerBinding = BottomDialogSearchFilterBinding.inflate(layoutInflater, null, false)
        bottomSheet?.setContentView(filerBinding.root)

        setupToolbar()
        setupSwitchesAndChips()
    }

    private fun setupToolbar() {
        filerBinding.toolbar.apply {
            setNavigationOnClickListener { bottomSheet?.dismiss() }
            setOnMenuItemClickListener {
                if (it.itemId == R.id.action_reset) resetFilters()
                false
            }
        }
    }

    private fun setupSwitchesAndChips() {
        filerBinding.reverseSwitch.apply {
            isChecked = PrefManager.isReverse
            setOnCheckedChangeListener { _, isChecked ->
                PrefManager.isReverse = isChecked
                updateSortAndFilters()
            }
        }

        val sortByTitles = listOf("应用名称", "应用大小", "最近更新时间", "安装日期", "Target 版本")
        val filterTitles = listOf("已配置", "最近更新", "已禁用")

        setupChipGroup(filerBinding.sortBy, sortByTitles, true)
        setupChipGroup(filerBinding.filter, filterTitles, false)
    }

    private fun updateSortAndFilters() {
        val filters = listOfNotNull(
            "已配置".takeIf { PrefManager.configured },
            "最近更新".takeIf { PrefManager.updated },
            "已禁用".takeIf { PrefManager.disabled }
        )
        controller?.updateSortList(Pair(PrefManager.order, filters), binding.editText.text.toString(), PrefManager.isReverse)
    }

    private fun resetFilters() {
        with(filerBinding) {
            sortBy.check(0)
            filter.clearCheck()
            reverseSwitch.isChecked = false
        }

        PrefManager.apply {
            isReverse = false
            order = "应用名称"
            configured = false
            updated = false
            disabled = false
        }

        updateSortAndFilters()
    }

    private fun setupChipGroup(chipGroup: ChipGroup, titles: List<String>, isSortBy: Boolean) {
        chipGroup.isSingleSelection = isSortBy
        titles.forEach { title ->
            val chip = Chip(requireContext()).apply {
                text = title
                isCheckable = true
                isClickable = true
                isChecked = when {
                    isSortBy -> title == PrefManager.order
                    else -> title == "已配置" && PrefManager.configured ||
                            title == "最近更新" && PrefManager.updated ||
                            title == "已禁用" && PrefManager.disabled
                }
                setOnClickListener { handleChipClick(this, title, isSortBy) }
            }
            chipGroup.addView(chip)
        }
    }

    private fun handleChipClick(chip: Chip, title: String, isSortBy: Boolean) {
        if (!isSortBy && title == "已配置" && !MainActivity.isModuleActivated()) {
            Snackbar.make(filerBinding.root, "模块尚未被激活", Snackbar.LENGTH_SHORT).show()
            chip.isChecked = false
            return
        }

        if (isSortBy) {
            PrefManager.order = title
        } else {
            val isChecked = chip.isChecked
            when (title) {
                "已配置" -> PrefManager.configured = isChecked
                "最近更新" -> PrefManager.updated = isChecked
                "已禁用" -> PrefManager.disabled = isChecked
            }
        }

        val message = if (isSortBy) "${requireContext().getString(R.string.sort_by_default)}: $title" else "$title 已更新"
        Snackbar.make(filerBinding.root, message, Snackbar.LENGTH_SHORT).show()
        updateSortAndFilters()
    }

    private fun showFilterToast(title: String, isEnabled: Boolean) {
        val message = if (isEnabled) {
            "${requireContext().getString(R.string.filter_enabled)}: $title"
        } else {
            "${requireContext().getString(R.string.filter_disabled)}: $title"
        }
        Snackbar.make(filerBinding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    override fun searchJob(text: String) {
        val filterList = ArrayList<String>().apply {
            if (PrefManager.configured) add("已配置")
            if (PrefManager.updated) add("最近更新")
            if (PrefManager.disabled) add("已禁用")
        }
        controller?.updateSortList(
            Pair(PrefManager.order, filterList),
            text,
            PrefManager.isReverse
        )
    }

    override fun getFragment(position: Int): Fragment =
        when (position) {
            0 -> AppsFragment.newInstance("user")
            1 -> AppsFragment.newInstance("configured")
            2 -> AppsFragment.newInstance("system")
            else -> throw IllegalArgumentException()
        }

    fun setHint(totalApp: Int) {
        binding.editText.hint = if (totalApp != 0) "搜索 ${totalApp}个应用"
        else "搜索"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bottomSheet?.dismiss()
        bottomSheet = null
    }
}
