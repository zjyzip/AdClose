package com.close.hook.ads.ui.fragment.app

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
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
import com.close.hook.ads.preference.PrefManager
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

    companion object {
        @JvmStatic
        fun newInstance() = AppsPagerFragment()

        private val SORT_OPTION_RES_IDS = listOf(
            R.string.sort_by_app_name,
            R.string.sort_by_app_size,
            R.string.sort_by_last_update,
            R.string.sort_by_install_date,
            R.string.sort_by_target_version
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BaseTablayoutViewpagerBinding.inflate(inflater, container, false)
        setupFilterButton()
        setupFab()
        return binding.root
    }

    private fun createFab(image: Int, tooltip: Int, onClick: () -> Unit): FloatingActionButton =
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

    private fun setupFab() {
        backupFab = createFab(R.drawable.ic_export, R.string.export) {
            fabController?.onExport()
        }
        restoreFab = createFab(R.drawable.ic_import, R.string.restore) {
            fabController?.onRestore()
        }
        updateFabMargin()
        binding.root.apply {
            addView(backupFab)
            addView(restoreFab)
        }
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
        setupOnBackPressed()
    }

    private fun setupOnBackPressed() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.editText.isFocused -> {
                        binding.editText.setText("")
                        filterBtn.setImageResource(R.drawable.ic_filter)
                        binding.editText.clearFocus()
                    }
                    else -> {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }
    
    override fun initButton() {
        super.initButton()
        filterBtn.setOnClickListener { bottomSheet?.show() }
    }

    private fun initFilterSheet() {
        bottomSheet = BottomSheetDialog(requireContext()).apply {
            filerBinding = BottomDialogSearchFilterBinding.inflate(layoutInflater)
            setContentView(filerBinding.root)
        }

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

        val sortByItems = SORT_OPTION_RES_IDS
        val filterItems = listOf(
            R.string.filter_configured,
            R.string.filter_recent_update,
            R.string.filter_disabled
        )

        setupChipGroup(filerBinding.sortBy, sortByItems, true)
        setupChipGroup(filerBinding.filter, filterItems, false)
    }

    private fun updateSortAndFilters() {
        controller?.updateSortList(
            PrefManager.order,
            binding.editText.text.toString(),
            PrefManager.isReverse,
            PrefManager.configured,
            PrefManager.updated,
            PrefManager.disabled
        )
    }

    private fun resetFilters() {
        with(filerBinding) {
            val defaultSortIndex = 0
            if (sortBy.childCount > defaultSortIndex) {
                (sortBy.getChildAt(defaultSortIndex) as? Chip)?.let { sortBy.check(it.id) }
            }
            filter.clearCheck()
            reverseSwitch.isChecked = false
        }

        PrefManager.isReverse = false
        PrefManager.order = 0
        PrefManager.configured = false
        PrefManager.updated = false
        PrefManager.disabled = false
    
        updateSortAndFilters()
    }

    private fun setupChipGroup(chipGroup: ChipGroup, titles: List<Int>, isSortBy: Boolean) {
        chipGroup.isSingleSelection = isSortBy
        chipGroup.removeAllViews()
        titles.forEachIndexed { index, titleResId ->
            val chip = Chip(requireContext()).apply {
                text = getString(titleResId)
                isCheckable = true
                isClickable = true
                isChecked = if (isSortBy) index == PrefManager.order else getChipCheckedState(titleResId)
                setOnClickListener { handleChipClick(this, titleResId, isSortBy, index) }
            }
            chipGroup.addView(chip)
        }
    }

    private fun getChipCheckedState(titleResId: Int): Boolean {
        return when (titleResId) {
            R.string.filter_configured -> PrefManager.configured
            R.string.filter_recent_update -> PrefManager.updated
            R.string.filter_disabled -> PrefManager.disabled
            else -> false
        }
    }

    private fun handleChipClick(chip: Chip, titleResId: Int, isSortBy: Boolean, index: Int) {
        if (isSortBy) {
            handleSortByChipClick(titleResId, index)
        } else {
            if (titleResId == R.string.filter_configured && !MainActivity.isModuleActivated()) {
                showSnackbar(getString(R.string.module_not_activated))
                chip.isChecked = false
                return
            }
            when (titleResId) {
                R.string.filter_configured -> PrefManager.configured = chip.isChecked
                R.string.filter_recent_update -> PrefManager.updated = chip.isChecked
                R.string.filter_disabled -> PrefManager.disabled = chip.isChecked
            }

            showSnackbar("${getString(titleResId)} ${getString(R.string.updated)}")
            updateSortAndFilters()
        }
    }
    
    private fun handleSortByChipClick(titleResId: Int, index: Int) {
        PrefManager.order = index
        showSnackbar("${getString(R.string.sort_by_default)}: ${getString(titleResId)}")
        updateSortAndFilters()
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(filerBinding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    override fun search(text: String) {
        updateSortAndFilters()
    }

    override fun getFragment(position: Int): Fragment {
        return AppsFragment.newInstance(
            when (position) {
                0 -> "user"
                1 -> "configured"
                2 -> "system"
                else -> throw IllegalArgumentException("Unknown position")
            }
        )
    }

    fun setHint(totalApp: Int) {
        binding.editText.hint = if (totalApp != 0) {
            getString(R.string.search_hint_with_count, totalApp)
        } else {
            getString(R.string.search_hint)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bottomSheet?.dismiss()
        bottomSheet = null
    }
}
