package com.close.hook.ads.ui.fragment.hook

import android.content.ClipData
import android.content.ClipboardManager
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.ItemKeyProvider
import androidx.recyclerview.selection.Selection
import androidx.recyclerview.selection.SelectionPredicates
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.R
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.databinding.FragmentCustomHookManagerBinding
import com.close.hook.ads.ui.adapter.CustomHookAdapter
import com.close.hook.ads.ui.fragment.base.BaseFragment
import com.close.hook.ads.ui.viewmodel.CustomHookViewModel
import com.close.hook.ads.ui.viewmodel.ImportAction
import com.close.hook.ads.util.AppIconLoader
import com.close.hook.ads.util.AppUtils
import com.close.hook.ads.util.ClipboardHookParser
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
import java.io.BufferedReader
import java.io.InputStreamReader

class CustomHookManagerFragment : BaseFragment<FragmentCustomHookManagerBinding>() {

    private val viewModel: CustomHookViewModel by viewModels()

    private lateinit var hookAdapter: CustomHookAdapter
    private var tracker: SelectionTracker<CustomHookInfo>? = null
    private var selectedItems: Selection<CustomHookInfo>? = null
    private var currentActionMode: ActionMode? = null
    private var editingConfig: CustomHookInfo? = null

    private val inputMethodManager: InputMethodManager by lazy {
        requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }
    private val clipboardManager: ClipboardManager by lazy {
        requireActivity().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    private val prettyJson = Json { prettyPrint = true }

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            mode?.menuInflater?.inflate(R.menu.menu_custom_hook, menu)
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
            tracker?.clearSelection()
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

    private fun setupOnBackPressed() {
        requireActivity().onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.editTextSearch.isFocused -> {
                        binding.editTextSearch.setText("")
                        setIconAndFocus(R.drawable.ic_back_to_magnifier, false)
                    }
                    tracker?.hasSelection() == true -> {
                        tracker?.clearSelection()
                    }
                    else -> {
                        this.isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val targetPkgName = arguments?.getString(ARG_PACKAGE_NAME)
        viewModel.init(targetPkgName)

        setupToolbar()
        setupRecyclerView()
        setUpTracker()
        addObserverToTracker()
        setupSearchInput()
        setupFabButtons()
        observeViewModel()
        setupHeaderDisplay(targetPkgName)
        setupFragmentResultListener()
        setupOnBackPressed()
    }

    private fun showAutoDetectedHookDialog(hookInfo: CustomHookInfo) {
        editingConfig = null
        CustomHookDialogFragment.newInstance(hookInfo).show(childFragmentManager, CustomHookDialogFragment.TAG)
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
        } else {
            binding.headerContainer.visibility = View.VISIBLE
            loadAppInfoIntoHeader(targetPkgName)
            binding.appHeaderInclude.switchGlobalEnable.visibility = View.VISIBLE
            observeGlobalHookToggle()
        }
    }

    private fun observeGlobalHookToggle() {
        binding.appHeaderInclude.switchGlobalEnable.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setGlobalHookStatus(isChecked)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isGlobalEnabled.collect { isEnabled ->
                    if (binding.appHeaderInclude.switchGlobalEnable.isChecked != isEnabled) {
                        binding.appHeaderInclude.switchGlobalEnable.jumpDrawablesToCurrentState()
                        binding.appHeaderInclude.switchGlobalEnable.isChecked = isEnabled
                        binding.appHeaderInclude.switchGlobalEnable.jumpDrawablesToCurrentState()
                    }
                    binding.appHeaderInclude.switchGlobalEnable.text = if (isEnabled) getString(R.string.enabled) else getString(R.string.disabled)
                }
            }
        }
    }

    private fun loadAppInfoIntoHeader(packageName: String) {
        lifecycleScope.launch {
            val appName = withContext(Dispatchers.IO) {
                runCatching { AppUtils.getAppName(requireContext(), packageName) }.getOrDefault(packageName)
            }

            val appInfo = withContext(Dispatchers.IO) {
                runCatching { requireContext().packageManager.getPackageInfo(packageName, 0) }.getOrNull()
            }

            val versionName = appInfo?.versionName ?: "N/A"
            val versionCode = appInfo?.longVersionCode ?: 0L

            binding.appHeaderInclude.appNameHeader.text = appName
            binding.appHeaderInclude.appVersionHeader.text = getString(R.string.app_version_format, versionName, versionCode)

            val targetSizePx = AppIconLoader.calculateTargetIconSizePx(requireContext())
            val icon = withContext(Dispatchers.IO) { AppIconLoader.loadAndCompressIcon(requireContext(), packageName, targetSizePx) }
            binding.appHeaderInclude.appIconHeader.setImageDrawable(icon)
        }
    }

    private fun setupRecyclerView() {
        hookAdapter = CustomHookAdapter(
            onDeleteItem = { config -> showDeleteConfigConfirmDialog(config) },
            onEditItem = { config -> showEditHookDialog(config) },
            onLongClickItem = { config ->
                tracker?.let { if (!it.hasSelection()) it.select(config) }
            },
            onClickItem = { config ->
                tracker?.let {
                    if (it.hasSelection()) {
                        if (it.isSelected(config)) it.deselect(config) else it.select(config)
                    } else {
                        lifecycleScope.launch { viewModel.toggleHookActivation(config, !config.isEnabled) }
                    }
                }
            },
            onToggleEnabled = { config, isEnabled ->
                lifecycleScope.launch { viewModel.toggleHookActivation(config, isEnabled) }
            }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = hookAdapter
        }
    }

    private fun setUpTracker() {
        tracker = SelectionTracker.Builder(
            "custom_hook_selection_id",
            binding.recyclerView,
            CustomHookItemKeyProvider(hookAdapter),
            CustomHookItemDetailsLookup(binding.recyclerView),
            StorageStrategy.createParcelableStorage(CustomHookInfo::class.java)
        ).withSelectionPredicate(
            SelectionPredicates.createSelectAnything()
        ).build()

        hookAdapter.tracker = tracker
    }

    private fun addObserverToTracker() {
        tracker?.addObserver(object : SelectionTracker.SelectionObserver<CustomHookInfo>() {
            override fun onSelectionChanged() {
                super.onSelectionChanged()
                selectedItems = tracker?.selection
                val selectedCount = selectedItems?.size() ?: 0

                hookAdapter.setMultiSelectMode(selectedCount > 0)

                if (selectedCount > 0) {
                    currentActionMode = currentActionMode ?: (activity as? AppCompatActivity)?.startSupportActionMode(actionModeCallback)
                    currentActionMode?.title = getString(R.string.selected_items_count, selectedCount)
                } else {
                    currentActionMode?.finish()
                }

                hookAdapter.updateSelection(selectedItems?.toSet() ?: emptySet())
            }
        })
    }

    class CustomHookItemDetailsLookup(private val recyclerView: RecyclerView) :
        ItemDetailsLookup<CustomHookInfo>() {
        override fun getItemDetails(e: MotionEvent): ItemDetails<CustomHookInfo>? {
            val view = recyclerView.findChildViewUnder(e.x, e.y)
            if (view != null) {
                val viewHolder = recyclerView.getChildViewHolder(view)
                if (viewHolder is CustomHookAdapter.ViewHolder) {
                    val deleteButton = viewHolder.itemView.findViewById<View>(R.id.btn_delete)
                    val editButton = viewHolder.itemView.findViewById<View>(R.id.btn_edit)
                    val switchEnabled = viewHolder.itemView.findViewById<View>(R.id.switch_enabled)

                    val isInsideButton = listOf(deleteButton, editButton, switchEnabled).any { button ->
                        val rect = android.graphics.Rect()
                        button.getHitRect(rect)
                        rect.contains(e.x.toInt() - view.left, e.y.toInt() - view.top)
                    }

                    if (isInsideButton) {
                        return null
                    }
                    return viewHolder.getItemDetails()
                }
            }
            return null
        }
    }

    class CustomHookItemKeyProvider(private val adapter: CustomHookAdapter) :
        ItemKeyProvider<CustomHookInfo>(SCOPE_CACHED) {
        override fun getKey(position: Int): CustomHookInfo? {
            return adapter.currentList.getOrNull(position)
        }

        override fun getPosition(key: CustomHookInfo): Int {
            val index = adapter.currentList.indexOfFirst { it.id == key.id }
            return if (index >= 0) index else RecyclerView.NO_POSITION
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

    private fun showDeleteSelectedConfirmDialog() {
        val selectedItemsList = selectedItems?.toList() ?: emptyList()
        if (selectedItemsList.isEmpty()) {
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_selected_hooks_title)
            .setMessage(R.string.delete_selected_hooks_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    val deletedCount = viewModel.deleteHookConfigs(selectedItemsList)
                    showSnackbar(getString(R.string.deleted_hooks_count, deletedCount))
                    tracker?.clearSelection()
                }
            }
            .show()
    }

    private fun copySelectedHooks() {
        val selectedItemsList = selectedItems?.toList() ?: emptyList()
        if (selectedItemsList.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.no_hook_selected_to_copy), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val message = viewModel.copyHooksToJson(selectedItemsList)
            message?.let { showSnackbar(it) }
            tracker?.clearSelection()
        }
    }

    private fun setupFab(fab: FloatingActionButton, offsetIndex: Int, onClick: () -> Unit) {
        val baseMargin = 16.dp
        val navBarHeight = ViewCompat.getRootWindowInsets(binding.root)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val fabHeightWithMargin = 56.dp + 16.dp
        val params = CoordinatorLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            behavior = HideBottomViewOnScrollBehavior<FloatingActionButton>()
            rightMargin = 25.dp
            bottomMargin = navBarHeight + baseMargin + (fabHeightWithMargin * offsetIndex)
        }
        fab.layoutParams = params
        fab.visibility = View.VISIBLE
        fab.setOnClickListener { onClick() }
    }

    private fun setupFabButtons() {
        setupFab(binding.fabClipboardAutoDetect, 2) {
            val clipText = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
            if (clipText.isNullOrBlank()) {
                Toast.makeText(requireContext(), getString(R.string.clipboard_content_unrecognized), Toast.LENGTH_SHORT).show()
                return@setupFab
            }

            ClipboardHookParser.parseClipboardContent(clipText, viewModel.getTargetPackageName())?.let { hookInfo ->
                showAutoDetectedHookDialog(hookInfo)
            } ?: run {
                Toast.makeText(requireContext(), getString(R.string.clipboard_content_unrecognized), Toast.LENGTH_SHORT).show()
            }
        }

        setupFab(binding.fabAddHook, 1) { showAddHookDialog() }
        setupFab(binding.fabClearAllHooks, 0) { showClearAllConfirmDialog() }
    }

    private fun showClearAllConfirmDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.clear_all_hooks_title)
            .setMessage(R.string.clear_all_hooks_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    viewModel.clearAllHooks()
                    showSnackbar(getString(R.string.all_hooks_cleared))
                    tracker?.clearSelection()
                }
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
                lifecycleScope.launch {
                    viewModel.deleteHook(config)
                    showSnackbar(getString(R.string.hook_deleted_successfully))
                    tracker?.clearSelection()
                }
            }
            .show()
    }

    private fun showAddHookDialog() {
        editingConfig = null
        CustomHookDialogFragment.newInstance(null).show(childFragmentManager, CustomHookDialogFragment.TAG)
    }

    private fun showEditHookDialog(config: CustomHookInfo) {
        editingConfig = config
        CustomHookDialogFragment.newInstance(config).show(childFragmentManager, CustomHookDialogFragment.TAG)
    }

    private fun setupFragmentResultListener() {
        childFragmentManager.setFragmentResultListener(
            CustomHookDialogFragment.REQUEST_KEY_HOOK_CONFIG,
            viewLifecycleOwner
        ) { _, bundle ->
            val updatedConfig = BundleCompat.getParcelable(bundle, CustomHookDialogFragment.BUNDLE_KEY_HOOK_CONFIG, CustomHookInfo::class.java)
            updatedConfig?.let { newOrUpdatedConfig ->
                lifecycleScope.launch {
                    if (editingConfig != null) {
                        viewModel.updateHook(editingConfig!!, newOrUpdatedConfig)
                        showSnackbar(getString(R.string.hook_updated_successfully))
                    } else {
                        viewModel.addHook(newOrUpdatedConfig)
                        showSnackbar(getString(R.string.hook_added_successfully))
                    }
                    editingConfig = null
                }
            }
        }
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
            inputMethodManager.showSoftInput(binding.editTextSearch, 0)
        } else {
            binding.editTextSearch.clearFocus()
            inputMethodManager.hideSoftInputFromWindow(binding.editTextSearch.windowToken, 0)
        }
    }

    private fun exportHooks(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val exportableHooks = viewModel.getExportableHooks()
                val jsonString = prettyJson.encodeToString(exportableHooks)
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
                val jsonString = requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use(BufferedReader::readText)
                if (jsonString.isNullOrEmpty()) {
                    throw IllegalArgumentException(getString(R.string.error_empty_or_invalid_json))
                }

                val importedConfigs = Json.decodeFromString<List<CustomHookInfo>>(jsonString)
                val importAction = viewModel.getImportAction(importedConfigs)

                withContext(Dispatchers.Main) {
                    handleImportAction(importAction)
                }

            }.onFailure {
                withContext(Dispatchers.Main) {
                    showMessageDialog(R.string.import_failed, it.message ?: "Unknown error", it)
                }
            }
        }
    }

    private suspend fun handleImportAction(action: ImportAction) {
        when (action) {
            is ImportAction.DirectImport -> {
                val updated = viewModel.importHooks(action.configs)
                showSnackbar(
                    if (updated) getString(R.string.import_success)
                    else getString(R.string.no_new_hooks_to_import)
                )
            }
            is ImportAction.PromptImportGlobal -> {
                showImportGlobalRulesDialog(action.configs)
            }
            is ImportAction.ImportGlobalAndNotifySkipped -> {
                val updated = viewModel.importGlobalHooksToStorage(action.globalConfigs)
                val baseMessage = if (updated) getString(R.string.import_success) else getString(R.string.no_new_hooks_to_import_global)
                if (action.skippedAppSpecificCount > 0) {
                    showSnackbar(getString(R.string.imported_global_rules_app_specific_skipped, action.skippedAppSpecificCount))
                } else {
                    showSnackbar(baseMessage)
                }
            }
        }
    }

    private fun showMessageDialog(titleResId: Int, message: String, throwable: Throwable?) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleResId)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .apply {
                if (throwable != null) {
                    setNegativeButton(R.string.crash_log) { _, _ ->
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.crash_log)
                            .setMessage(throwable.stackTraceToString() ?: "No stack trace available.")
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun showImportGlobalRulesDialog(importedConfigs: List<CustomHookInfo>) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.import_global_rules_title)
            .setMessage(R.string.import_global_rules_message)
            .setPositiveButton(R.string.import_to_global) { dialog, _ ->
                lifecycleScope.launch {
                    val globalOnlyImported = importedConfigs.filter { it.packageName.isNullOrEmpty() }
                    if (globalOnlyImported.isEmpty()) {
                        showSnackbar(getString(R.string.no_global_hooks_found_to_import))
                    } else {
                        val updated = viewModel.importGlobalHooksToStorage(globalOnlyImported)
                        showSnackbar(
                            if (updated) getString(R.string.imported_to_global_successfully)
                            else getString(R.string.no_new_hooks_to_import_global)
                        )
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        currentActionMode?.finish()
        inputMethodManager.hideSoftInputFromWindow(binding.editTextSearch.windowToken, 0)
    }

    override fun onDestroyView() {
        binding.editTextSearch.removeTextChangedListener(searchTextWatcher)
        childFragmentManager.clearFragmentResultListener(CustomHookDialogFragment.REQUEST_KEY_HOOK_CONFIG)
        super.onDestroyView()
    }
}
