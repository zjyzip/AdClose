package com.close.hook.ads.ui.fragment.hook

import android.content.Context
import android.graphics.drawable.AnimatedVectorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.close.hook.ads.R
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.databinding.FragmentCustomHookManagerBinding
import com.close.hook.ads.ui.adapter.CustomHookAdapter
import com.close.hook.ads.ui.viewmodel.CustomHookViewModel
import com.close.hook.ads.ui.viewmodel.ImportResult
import com.close.hook.ads.ui.fragment.base.BaseFragment
import com.close.hook.ads.util.AppIconLoader
import com.close.hook.ads.util.AppUtils
import com.close.hook.ads.util.OnBackPressContainer
import com.close.hook.ads.util.OnBackPressListener
import com.close.hook.ads.util.dp
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class CustomHookManagerFragment : BaseFragment<FragmentCustomHookManagerBinding>(), OnBackPressListener {

    private val viewModel: CustomHookViewModel by viewModels()
    private lateinit var hookAdapter: CustomHookAdapter

    private var currentActionMode: ActionMode? = null
    private var inputMethodManager: InputMethodManager? = null

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            mode?.menuInflater?.inflate(R.menu.menu_custom_hook, menu)
            hookAdapter.setMultiSelectMode(true)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            return when (item?.itemId) {
                R.id.action_delete -> {
                    showDeleteSelectedConfirmDialog()
                    true
                }
                R.id.action_copy -> {
                    copySelectedHooks()
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            currentActionMode = null
            viewModel.clearSelection()
            hookAdapter.setMultiSelectMode(false)
        }
    }

    private val backupLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            uri?.let(::exportHooks)
        }

    private val restoreLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let(::importHooks)
        }

    companion object {
        private const val ARG_PACKAGE_NAME = "packageName"

        fun newInstance(packageName: String?) =
            CustomHookManagerFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PACKAGE_NAME, packageName)
                }
            }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        inputMethodManager = requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        val targetPkgName = arguments?.getString(ARG_PACKAGE_NAME)
        viewModel.init(targetPkgName)

        setupToolbar()
        setupRecyclerView()
        setupSearchInput()
        setupFabButtons()
        observeViewModel()
        setupHeaderDisplay(targetPkgName)
    }

    private fun setupToolbar() {
        (activity as? AppCompatActivity)?.setSupportActionBar(binding.toolBar)
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            setDisplayShowTitleEnabled(false)
            setDisplayHomeAsUpEnabled(true)
            setHomeButtonEnabled(true)
        }
        binding.toolBar.setNavigationOnClickListener {
            (activity as? AppCompatActivity)?.onBackPressedDispatcher?.onBackPressed()
        }
    }

    private fun setupHeaderDisplay(targetPkgName: String?) {
        if (targetPkgName.isNullOrEmpty()) {
            binding.headerContainer.visibility = View.GONE
            (activity as? AppCompatActivity)?.supportActionBar?.title = getString(R.string.title_global_hook_configs)
        } else {
            binding.headerContainer.visibility = View.VISIBLE
            loadAppInfoIntoHeader(targetPkgName)
            binding.appHeaderInclude.switchGlobalEnable.visibility = View.VISIBLE
            observeGlobalHookToggle()
        }
    }

    private fun observeGlobalHookToggle() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isGlobalEnabled.collect { isEnabled ->
                    binding.appHeaderInclude.switchGlobalEnable.isChecked = isEnabled
                    binding.appHeaderInclude.switchGlobalEnable.text = if (isEnabled) getString(R.string.enabled) else getString(R.string.disabled)
                }
            }
        }
        binding.appHeaderInclude.switchGlobalEnable.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setGlobalHookStatus(isChecked)
        }
    }

    private fun loadAppInfoIntoHeader(packageName: String) {
        lifecycleScope.launch {
            val appName = withContext(Dispatchers.IO) { AppUtils.getAppName(requireContext(), packageName) }
            val appInfo = withContext(Dispatchers.IO) {
                try { requireContext().packageManager.getPackageInfo(packageName, 0) } catch (_: Exception) { null }
            }

            val versionName = appInfo?.versionName ?: "N/A"
            val versionCode = appInfo?.longVersionCode ?: 0L

            binding.appHeaderInclude.appNameHeader.text = appName
            binding.appHeaderInclude.appVersionHeader.text = getString(R.string.app_version_format, versionName, versionCode)

            val targetSizePx = AppIconLoader.calculateTargetIconSizePx(requireContext())
            val icon = withContext(Dispatchers.IO) { AppIconLoader.loadAndCompressIcon(requireContext(), packageName, targetSizePx) }
            binding.appHeaderInclude.appIconHeader.setImageDrawable(icon)
            (activity as? AppCompatActivity)?.supportActionBar?.title = appName
        }
    }

    private fun setupRecyclerView() {
        hookAdapter = CustomHookAdapter(
            onDeleteItem = { config -> showDeleteConfigConfirmDialog(config) },
            onEditItem = { config -> showEditHookDialog(config) },
            onLongClickItem = { config -> handleItemLongClick(config) },
            onClickItem = { config -> handleItemClick(config) },
            onToggleEnabled = { config, isEnabled -> viewModel.toggleHookActivation(config, isEnabled) }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = hookAdapter
        }
    }

    private fun handleItemLongClick(config: CustomHookInfo) {
        if (currentActionMode == null) {
            currentActionMode = (activity as? AppCompatActivity)?.startSupportActionMode(actionModeCallback)
        }
        viewModel.toggleSelection(config)
    }

    private fun handleItemClick(config: CustomHookInfo) {
        if (currentActionMode != null) {
            viewModel.toggleSelection(config)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.filteredConfigs.collect { configs ->
                        hookAdapter.submitList(configs)
                        updateEmptyView(configs.isEmpty() && viewModel.searchQuery.value.isBlank())
                    }
                }
                launch {
                    viewModel.selectedConfigs.collect { selectedItems ->
                        hookAdapter.updateSelection(selectedItems)
                        updateActionModeTitle(selectedItems.size)
                    }
                }
                launch {
                    viewModel.searchQuery.collect { query ->
                        hookAdapter.setSearchQuery(query)
                        binding.clearSearch.isVisible = query.isNotBlank()
                    }
                }
                launch {
                    viewModel.isLoading.collect { isLoading ->
                        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    private fun updateActionModeTitle(selectedCount: Int) {
        if (selectedCount > 0) {
            currentActionMode = currentActionMode ?: (activity as? AppCompatActivity)?.startSupportActionMode(actionModeCallback)
            currentActionMode?.title = getString(R.string.selected_items_count, selectedCount)
        } else {
            currentActionMode?.finish()
        }
    }

    private fun showDeleteSelectedConfirmDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_selected_hooks_title)
            .setMessage(R.string.delete_selected_hooks_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selectedCount = viewModel.selectedConfigs.value.size
                viewModel.deleteSelectedHookConfigs()
                showSnackbar(getString(R.string.deleted_hooks_count, selectedCount))
                currentActionMode?.finish()
            }
            .show()
    }

    private fun copySelectedHooks() {
        lifecycleScope.launch {
            val message = viewModel.copySelectedHooksToJson()
            showSnackbar(message)
            currentActionMode?.finish()
        }
    }

    private fun setupFabButtons() {
        val navBarHeight = ViewCompat.getRootWindowInsets(binding.root)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val baseMargin = 16.dp
        val fabHeightWithMargin = 56.dp + 16.dp

        binding.fabAddHook.apply {
            val params = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                behavior = HideBottomViewOnScrollBehavior<FloatingActionButton>()
                rightMargin = 25.dp
                bottomMargin = navBarHeight + baseMargin + fabHeightWithMargin
            }
            layoutParams = params
            visibility = View.VISIBLE
            setOnClickListener { showAddHookDialog() }
        }

        binding.fabClearAllHooks.apply {
            val params = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                behavior = HideBottomViewOnScrollBehavior<FloatingActionButton>()
                rightMargin = 25.dp
                bottomMargin = navBarHeight + baseMargin
            }
            layoutParams = params
            visibility = View.VISIBLE
            setOnClickListener { showClearAllConfirmDialog() }
        }
    }

    private fun showClearAllConfirmDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.clear_all_hooks_title)
            .setMessage(R.string.clear_all_hooks_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.clearAllHooks()
                showSnackbar(getString(R.string.all_hooks_cleared))
            }
            .show()
    }

    private fun updateEmptyView(isEmpty: Boolean) {
        binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun showDeleteConfigConfirmDialog(config: CustomHookInfo) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_hook_title)
            .setMessage(R.string.delete_hook_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.deleteHook(config)
                showSnackbar(getString(R.string.hook_deleted_successfully))
            }
            .show()
    }

    private fun showAddHookDialog() {
        CustomHookDialogFragment.newInstance(null) { newConfig ->
            viewModel.addHook(newConfig)
            showSnackbar(getString(R.string.hook_added_successfully))
        }.show(childFragmentManager, CustomHookDialogFragment.TAG)
    }

    private fun showEditHookDialog(config: CustomHookInfo) {
        CustomHookDialogFragment.newInstance(config) { updatedConfig ->
            viewModel.updateHook(config, updatedConfig)
            showSnackbar(getString(R.string.hook_updated_successfully))
        }.show(childFragmentManager, CustomHookDialogFragment.TAG)
    }

    private fun setupSearchInput() {
        binding.editTextSearch.onFocusChangeListener =
            View.OnFocusChangeListener { _, hasFocus ->
                setIconAndFocus(
                    if (hasFocus) R.drawable.ic_magnifier_to_back else R.drawable.ic_back_to_magnifier,
                    hasFocus
                )
            }
        binding.editTextSearch.addTextChangedListener(searchTextWatcher)
        binding.searchIcon.setOnClickListener {
            if (binding.editTextSearch.isFocused) {
                binding.editTextSearch.setText("")
                setIconAndFocus(R.drawable.ic_back_to_magnifier, false)
            } else {
                setIconAndFocus(R.drawable.ic_magnifier_to_back, true)
            }
        }
        binding.clearSearch.setOnClickListener {
            binding.editTextSearch.text = null
        }
        binding.exportHooks.setOnClickListener {
            val fileName = viewModel.getTargetPackageName()?.let { pkgName ->
                "${pkgName}_custom_hooks.json"
            } ?: "global_custom_hooks.json"
            backupLauncher.launch(fileName)
        }
        binding.restoreHooks.setOnClickListener {
            restoreLauncher.launch(arrayOf("application/json"))
        }
    }

    private val searchTextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            viewModel.setSearchQuery(s.toString())
        }
        override fun afterTextChanged(s: Editable) {}
    }

    private fun setIconAndFocus(drawableId: Int, focus: Boolean) {
        binding.searchIcon.setImageDrawable(ContextCompat.getDrawable(requireContext(), drawableId))
        (binding.searchIcon.drawable as? AnimatedVectorDrawable)?.start()
        if (focus) {
            binding.editTextSearch.requestFocus()
            inputMethodManager?.showSoftInput(binding.editTextSearch, 0)
        } else {
            binding.editTextSearch.clearFocus()
            inputMethodManager?.hideSoftInputFromWindow(binding.editTextSearch.windowToken, 0)
        }
    }

    private fun exportHooks(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val exportableHooks = viewModel.getExportableHooks()
                val jsonString = Json.encodeToString(exportableHooks)
                requireContext().contentResolver.openOutputStream(uri)?.bufferedWriter().use { writer ->
                    writer?.write(jsonString)
                }
                withContext(Dispatchers.Main) {
                    showSnackbar(getString(R.string.export_success))
                }
            }.onFailure {
                withContext(Dispatchers.Main) { showMessageDialog(R.string.export_failed, it.message ?: "Unknown error", it) }
            }
        }
    }

    private fun importHooks(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val jsonString = requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (jsonString.isNullOrEmpty()) {
                    throw IllegalArgumentException(getString(R.string.error_empty_or_invalid_json))
                }

                val importedConfigs = Json.decodeFromString<List<CustomHookInfo>>(jsonString)
                handleImportResult(viewModel.handleImport(importedConfigs))

            }.onFailure {
                withContext(Dispatchers.Main) {
                    showMessageDialog(R.string.import_failed, it.message ?: "Unknown error", it)
                }
            }
        }
    }

    private fun handleImportResult(result: ImportResult) {
        when (result) {
            is ImportResult.ShowSnackbar -> {
                showSnackbar(getString(if (result.isUpdated) R.string.import_success else R.string.no_new_hooks_to_import))
            }
            is ImportResult.ShowDialog -> {
                showMessageDialog(result.titleResId, result.message, result.throwable)
            }
            is ImportResult.ShowGlobalRulesImportDialog -> {
                showImportGlobalRulesDialog(result.importedConfigs)
            }
            is ImportResult.ShowAppSpecificRulesSkippedSnackbar -> {
                showSnackbar(getString(R.string.imported_global_rules_app_specific_skipped, result.skippedCount))
                if (result.isUpdated) {
                    showSnackbar(getString(R.string.import_success))
                } else {
                    showSnackbar(getString(R.string.no_new_hooks_to_import))
                }
            }
        }
    }

    private fun showImportGlobalRulesDialog(importedConfigs: List<CustomHookInfo>) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.import_global_rules_title)
            .setMessage(R.string.import_global_rules_message)
            .setPositiveButton(R.string.import_to_global) { dialog, _ ->
                lifecycleScope.launch {
                    val updated = viewModel.confirmGlobalHooksImport(importedConfigs)
                    showSnackbar(getString(if (updated) R.string.imported_to_global_successfully else R.string.no_new_hooks_to_import_global))
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showMessageDialog(titleResId: Int, message: String, throwable: Throwable?) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleResId)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.crash_log) { _, _ ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.crash_log)
                    .setMessage(throwable?.stackTraceToString() ?: "No stack trace available.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            .show()
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onBackPressed(): Boolean {
        return when {
            binding.editTextSearch.isFocused -> {
                binding.editTextSearch.setText("")
                setIconAndFocus(R.drawable.ic_back_to_magnifier, false)
                true
            }
            viewModel.selectedConfigs.value.isNotEmpty() -> {
                currentActionMode?.finish()
                true
            }
            else -> false
        }
    }

    override fun onPause() {
        super.onPause()
        (activity as? OnBackPressContainer)?.backController = null
        currentActionMode?.finish()
        inputMethodManager?.hideSoftInputFromWindow(binding.editTextSearch.windowToken, 0)
    }

    override fun onResume() {
        super.onResume()
        (activity as? OnBackPressContainer)?.backController = this
    }

    override fun onDestroyView() {
        binding.editTextSearch.removeTextChangedListener(searchTextWatcher)
        super.onDestroyView()
    }
}
