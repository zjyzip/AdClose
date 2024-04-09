package com.close.hook.ads.ui.fragment.app

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import com.close.hook.ads.R
import com.close.hook.ads.databinding.BaseTablayoutViewpagerBinding
import com.close.hook.ads.databinding.BottomDialogSearchFilterBinding
import com.close.hook.ads.ui.activity.MainActivity
import com.close.hook.ads.ui.fragment.base.BasePagerFragment
import com.close.hook.ads.util.IOnFabClickContainer
import com.close.hook.ads.util.IOnFabClickListener
import com.close.hook.ads.util.PrefManager
import com.close.hook.ads.util.dp
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar

class AppsPagerFragment : BasePagerFragment(), IOnFabClickContainer {

    override val tabList: List<Int> =
        listOf(R.string.tab_user_apps, R.string.tab_configured_apps, R.string.tab_system_apps)
    private var bottomSheet: BottomSheetDialog? = null
    private lateinit var filerBinding: BottomDialogSearchFilterBinding
    private lateinit var filterBtn: ImageButton
    private lateinit var backupFab: FloatingActionButton
    private lateinit var restoreFab: FloatingActionButton
    override var fabController: IOnFabClickListener? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BaseTablayoutViewpagerBinding.inflate(inflater, container, false)
        setupFilterButton()
        setupFab()
        return binding.root
    }

    private fun setupFab() {
        backupFab = getFab(R.drawable.ic_export, R.string.export) {
            fabController?.onExport()
        }
        restoreFab = getFab(R.drawable.ic_import, R.string.restore) {
            fabController?.onRestore()
        }
        updateFabMargin()
        binding.root.apply {
            addView(backupFab)
            addView(restoreFab)
        }
    }

    private fun getFab(image: Int, tooltip: Int, onClick: () -> Unit): FloatingActionButton =
        FloatingActionButton(requireContext()).apply {
            layoutParams = CoordinatorLayout.LayoutParams(
                CoordinatorLayout.LayoutParams.WRAP_CONTENT,
                CoordinatorLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                behavior = HideBottomViewOnScrollBehavior<FloatingActionButton>()
            }
            setImageResource(image)
            tooltipText = getString(tooltip)
            setOnClickListener { onClick() }
        }

    private fun updateFabMargin() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            restoreFab.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                rightMargin = 25.dp
                bottomMargin = navigationBars.bottom + 105.dp
            }
            backupFab.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                rightMargin = 25.dp
                bottomMargin = navigationBars.bottom + 186.dp
            }
            insets
        }
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
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                outValue,
                true
            )
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
        controller?.updateSortList(
            Pair(PrefManager.order, filters),
            binding.editText.text.toString(),
            PrefManager.isReverse
        )
    }

    private fun resetFilters() {
        with(filerBinding) {
            sortBy.check(sortBy.getChildAt(0).id)
            filter.clearCheck()
            reverseSwitch.isChecked = false
        }

        PrefManager.isReverse = false
        PrefManager.order = "应用名称"
        PrefManager.configured = false
        PrefManager.updated = false
        PrefManager.disabled = false

        updateSortAndFilters()
    }

    private fun setupChipGroup(chipGroup: ChipGroup, titles: List<String>, isSortBy: Boolean) {
        chipGroup.isSingleSelection = isSortBy
        titles.forEach { title ->
            val chip = Chip(requireContext()).apply {
                text = title
                isCheckable = true
                isClickable = true
                isChecked = if (isSortBy) title == PrefManager.order else when (title) {
                    "已配置" -> PrefManager.configured
                    "最近更新" -> PrefManager.updated
                    "已禁用" -> PrefManager.disabled
                    else -> false
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
            when (title) {
                "已配置" -> PrefManager.configured = chip.isChecked
                "最近更新" -> PrefManager.updated = chip.isChecked
                "已禁用" -> PrefManager.disabled = chip.isChecked
            }
        }

        val message =
            if (isSortBy) "${requireContext().getString(R.string.sort_by_default)}: $title" else "$title 已更新"
        Snackbar.make(filerBinding.root, message, Snackbar.LENGTH_SHORT).show()
        updateSortAndFilters()
    }

    override fun search(text: String) {
        updateSortAndFilters()
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
        fabController = null
    }
}
