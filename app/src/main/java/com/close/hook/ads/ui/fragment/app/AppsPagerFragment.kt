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
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BaseTablayoutViewpagerBinding.inflate(inflater, container, false)
        filterBtn = ImageButton(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            setPadding(16.dp, 0, 16.dp, 0)
            setImageResource(R.drawable.ic_filter)
            val outValue = TypedValue()
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                outValue,
                true
            )
            setBackgroundResource(outValue.resourceId)
        }
        binding.searchContainer.addView(filterBtn)
        return binding.root
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
                    binding.editText.text.toString(),
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
            binding.editText.text.toString(),
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
