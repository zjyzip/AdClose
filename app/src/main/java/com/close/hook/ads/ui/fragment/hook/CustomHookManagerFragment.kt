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
import android.widget.Toast
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CustomHookManagerFragment : BaseFragment<FragmentCustomHookManagerBinding>(), OnBackPressListener {

    private val viewModel: CustomHookViewModel by viewModels()
    private lateinit var adapter: CustomHookAdapter

    private var actionMode: ActionMode? = null
    private var imm: InputMethodManager? = null

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            mode?.menuInflater?.inflate(R.menu.menu_custom_hook, menu)
            adapter.setMultiSelectMode(true)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            return when (item?.itemId) {
                R.id.action_delete -> {
                    showDeleteSelectedDialog()
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
            actionMode = null
            viewModel.clearSelection()
            adapter.setMultiSelectMode(false)
        }
    }

    private val backupSAFLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            uri?.let(::exportHooks)
        }

    private val restoreSAFLauncher: ActivityResultLauncher<Array<String>> =
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
        imm = requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        val targetPackageName = arguments?.getString(ARG_PACKAGE_NAME)
        viewModel.initialize(targetPackageName)

        setupToolbar()
        setupRecyclerView()
        setupSearchInput()
        setupFabButtons()
        observeViewModel()
        setupHeaderDisplay(targetPackageName)
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

    private fun setupHeaderDisplay(targetPackageName: String?) {
        if (targetPackageName.isNullOrEmpty()) {
            binding.headerContainer.visibility = View.GONE
            (activity as? AppCompatActivity)?.supportActionBar?.title = getString(R.string.title_global_hook_configs)
        } else {
            binding.headerContainer.visibility = View.VISIBLE
            loadAppInfoIntoHeader(targetPackageName)
            binding.appHeaderInclude.switchGlobalEnable.visibility = View.VISIBLE
            observeGlobalHookToggle()
        }
    }

    private fun observeGlobalHookToggle() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isGlobalHookEnabled.collect { isEnabled ->
                    binding.appHeaderInclude.switchGlobalEnable.isChecked = isEnabled
                    binding.appHeaderInclude.switchGlobalEnable.text = if (isEnabled) getString(R.string.enabled) else getString(R.string.disabled)
                }
            }
        }
        binding.appHeaderInclude.switchGlobalEnable.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setGlobalHookEnabledStatus(isChecked)
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
        adapter = CustomHookAdapter(
            onItemDelete = { config -> showDeleteConfirmationDialog(config) },
            onItemEdit = { config -> showEditHookDialog(config) },
            onItemLongClick = { config -> handleItemLongClick(config) },
            onItemClick = { config -> handleItemClick(config) },
            onToggleEnabled = { config, isEnabled -> viewModel.toggleHookActivation(config, isEnabled) }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@CustomHookManagerFragment.adapter
        }
    }

    private fun handleItemLongClick(config: CustomHookInfo) {
        if (actionMode == null) {
            actionMode = (activity as? AppCompatActivity)?.startSupportActionMode(actionModeCallback)
        }
        viewModel.toggleSelection(config)
    }

    private fun handleItemClick(config: CustomHookInfo) {
        if (actionMode != null) {
            viewModel.toggleSelection(config)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.filteredHooks.collect { configs ->
                        adapter.submitList(configs)
                        updateEmptyView(configs.isEmpty() && viewModel.query.value.isBlank())
                    }
                }
                launch {
                    viewModel.selectedHooks.collect { selectedItems ->
                        adapter.updateSelection(selectedItems)
                        updateActionModeTitle(selectedItems.size)
                    }
                }
                launch {
                    viewModel.query.collect { query ->
                        adapter.setSearchQuery(query)
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
            actionMode = actionMode ?: (activity as? AppCompatActivity)?.startSupportActionMode(actionModeCallback)
            actionMode?.title = getString(R.string.selected_items_count, selectedCount)
        } else {
            actionMode?.finish()
        }
    }

    private fun showDeleteSelectedDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_selected_hooks_title)
            .setMessage(R.string.delete_selected_hooks_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selectedCount = viewModel.selectedHooks.value.size
                viewModel.deleteSelectedHookConfigs()
                actionMode?.finish()
                Snackbar.make(binding.root, getString(R.string.deleted_hooks_count, selectedCount), Snackbar.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun copySelectedHooks() {
        lifecycleScope.launch {
            val message = viewModel.copySelectedHooksToJson()
            Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
            actionMode?.finish()
        }
    }

    private fun setupFabButtons() {
        val navigationBarHeight = ViewCompat.getRootWindowInsets(binding.root)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
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
                bottomMargin = navigationBarHeight + baseMargin + fabHeightWithMargin
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
                bottomMargin = navigationBarHeight + baseMargin
            }
            layoutParams = params
            visibility = View.VISIBLE
            setOnClickListener { showClearAllDialog() }
        }
    }


    private fun showClearAllDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.clear_all_hooks_title)
            .setMessage(R.string.clear_all_hooks_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.clearAllHooks()
                Snackbar.make(binding.root, getString(R.string.all_hooks_cleared), Snackbar.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun updateEmptyView(isEmpty: Boolean) {
        binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun showDeleteConfirmationDialog(config: CustomHookInfo) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_hook_title)
            .setMessage(R.string.delete_hook_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.deleteHook(config)
                Snackbar.make(binding.root, getString(R.string.hook_deleted_successfully), Snackbar.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showAddHookDialog() {
        CustomHookDialogFragment.newInstance { newConfig ->
            viewModel.addHook(newConfig)
            Snackbar.make(binding.root, getString(R.string.hook_added_successfully), Snackbar.LENGTH_SHORT).show()
        }.show(childFragmentManager, CustomHookDialogFragment.TAG)
    }

    private fun showEditHookDialog(config: CustomHookInfo) {
        CustomHookDialogFragment.newInstance(config) { updatedConfig ->
            viewModel.updateHook(config, updatedConfig)
            Snackbar.make(binding.root, getString(R.string.hook_updated_successfully), Snackbar.LENGTH_SHORT).show()
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
        binding.editTextSearch.addTextChangedListener(textWatcher)
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
            val filename = viewModel.getTargetPackageName()?.let { packageName ->
                "${packageName}_custom_hooks.json"
            } ?: "global_custom_hooks.json"
            backupSAFLauncher.launch(filename)
        }
        binding.restoreHooks.setOnClickListener {
            restoreSAFLauncher.launch(arrayOf("application/json"))
        }
    }

    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            viewModel.setQuery(s.toString())
        }
        override fun afterTextChanged(s: Editable) {}
    }

    private fun setIconAndFocus(drawableId: Int, focus: Boolean) {
        binding.searchIcon.setImageDrawable(ContextCompat.getDrawable(requireContext(), drawableId))
        (binding.searchIcon.drawable as? AnimatedVectorDrawable)?.start()
        if (focus) {
            binding.editTextSearch.requestFocus()
            imm?.showSoftInput(binding.editTextSearch, 0)
        } else {
            binding.editTextSearch.clearFocus()
            imm?.hideSoftInputFromWindow(binding.editTextSearch.windowToken, 0)
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
                    Snackbar.make(binding.root, getString(R.string.export_success), Snackbar.LENGTH_SHORT).show()
                }
            }.onFailure {
                withContext(Dispatchers.Main) { showCrashLogDialog(getString(R.string.export_failed), it) }
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

                val importedHooks = Json.decodeFromString<List<CustomHookInfo>>(jsonString)
                handleImportLogic(importedHooks)
            }.onFailure {
                withContext(Dispatchers.Main) { showCrashLogDialog(getString(R.string.import_failed), it) }
            }
        }
    }

    private suspend fun handleImportLogic(importedHooks: List<CustomHookInfo>) {
        val isCurrentPageGlobal = viewModel.getTargetPackageName().isNullOrEmpty()
        val containsGlobalRules = importedHooks.any { it.packageName.isNullOrEmpty() }
        val containsAppSpecificRules = importedHooks.any { !it.packageName.isNullOrEmpty() }

        when {
            !isCurrentPageGlobal && containsGlobalRules -> {
                withContext(Dispatchers.Main) { showImportGlobalRulesDialog(importedHooks) }
            }
            isCurrentPageGlobal && containsAppSpecificRules -> {
                withContext(Dispatchers.Main) { showAppSpecificRulesSkippedSnackbar(importedHooks.count { !it.packageName.isNullOrEmpty() }) }
                val globalOnlyHooks = importedHooks.filter { it.packageName.isNullOrEmpty() }
                importFilteredHooks(globalOnlyHooks, true)
            }
            else -> {
                importFilteredHooks(importedHooks, false)
            }
        }
    }

    private fun showImportGlobalRulesDialog(importedHooks: List<CustomHookInfo>) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.import_global_rules_title)
            .setMessage(R.string.import_global_rules_message)
            .setPositiveButton(R.string.import_to_global) { dialog, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val globalOnlyImported = importedHooks.filter { it.packageName.isNullOrEmpty() }
                    val updated = viewModel.importGlobalHooksToStorage(globalOnlyImported)
                    withContext(Dispatchers.Main) {
                        Snackbar.make(binding.root, getString(if (updated) R.string.imported_to_global_successfully else R.string.no_new_hooks_to_import_global), Snackbar.LENGTH_SHORT).show()
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAppSpecificRulesSkippedSnackbar(count: Int) {
        Snackbar.make(binding.root, getString(R.string.imported_global_rules_app_specific_skipped, count), Snackbar.LENGTH_LONG).show()
    }

    private suspend fun importFilteredHooks(hooksToImport: List<CustomHookInfo>, isGlobalContext: Boolean) {
        val updated = viewModel.importHooks(hooksToImport)
        withContext(Dispatchers.Main) {
            val messageResId = when {
                updated && !isGlobalContext -> R.string.import_success
                updated && isGlobalContext -> R.string.import_success
                !updated && !isGlobalContext -> R.string.no_new_hooks_to_import
                !updated && isGlobalContext && hooksToImport.isEmpty() -> R.string.no_global_hooks_found_to_import
                else -> R.string.no_new_hooks_to_import
            }
            Snackbar.make(binding.root, getString(messageResId), Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun showCrashLogDialog(title: String, throwable: Throwable) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(throwable.message)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.crash_log) { _, _ ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.crash_log)
                    .setMessage(throwable.stackTraceToString())
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            .show()
    }

    override fun onBackPressed(): Boolean {
        return when {
            binding.editTextSearch.isFocused -> {
                binding.editTextSearch.setText("")
                setIconAndFocus(R.drawable.ic_back_to_magnifier, false)
                true
            }
            viewModel.selectedHooks.value.isNotEmpty() -> {
                actionMode?.finish()
                true
            }
            else -> false
        }
    }

    override fun onPause() {
        super.onPause()
        (activity as? OnBackPressContainer)?.backController = null
        actionMode?.finish()
    }

    override fun onResume() {
        super.onResume()
        (activity as? OnBackPressContainer)?.backController = this
    }

    override fun onDestroyView() {
        binding.editTextSearch.removeTextChangedListener(textWatcher)
        super.onDestroyView()
    }
}
