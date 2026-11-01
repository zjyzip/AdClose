package com.close.hook.ads.ui.fragment.hook

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.AnimatedVectorDrawable
import android.net.Uri
import android.util.Log
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.ItemKeyProvider
import androidx.recyclerview.selection.SelectionPredicates
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.R
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.databinding.FragmentCustomHookManagerBinding
import com.close.hook.ads.ui.activity.CustomHookActivity
import com.close.hook.ads.manager.ScopeManager
import com.close.hook.ads.ui.adapter.CustomHookAdapter
import com.close.hook.ads.ui.fragment.base.BaseFragment
import com.close.hook.ads.ui.viewmodel.CustomHookViewModel
import com.close.hook.ads.ui.viewmodel.ImportAction
import com.close.hook.ads.util.AppIconLoader
import com.close.hook.ads.util.AppUtils
import com.close.hook.ads.util.ClipboardHookParser
import com.close.hook.ads.util.ParseResult
import com.close.hook.ads.util.INavContainer
import com.close.hook.ads.util.dp
import com.close.hook.ads.util.readTextLimited
import com.close.hook.ads.util.FooterSpaceItemDecoration
import com.close.hook.ads.util.MotionUtils
import com.close.hook.ads.util.OnBackPressContainer
import com.close.hook.ads.util.OnBackPressListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.close.hook.ads.util.showFeedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.zhanghai.android.fastscroll.FastScrollerBuilder

private const val MAX_HOOK_IMPORT_BYTES = 10 * 1024 * 1024

class CustomHookManagerFragment : BaseFragment<FragmentCustomHookManagerBinding>(), OnBackPressListener {

    private val viewModel: CustomHookViewModel by viewModels {
        CustomHookViewModel.Factory(
            requireActivity().application,
            arguments?.getString(ARG_PACKAGE_NAME)
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var hookAdapter: CustomHookAdapter
    private lateinit var footerSpaceDecoration: FooterSpaceItemDecoration
    private var tracker: SelectionTracker<CustomHookInfo>? = null
    private var currentActionMode: ActionMode? = null
    private var isSelectionMode = false
    private var editingConfig: CustomHookInfo? = null
    private var isFabMenuOpen = false
    private lateinit var childFabs: List<View>

    private val launchedPackages = mutableSetOf<String>()

    private val scopeCallback = object : ScopeManager.ScopeCallback {
        override fun onScopeOperationSuccess(message: String) {
            Log.i("CustomHookManagerFragment", message)
        }
        override fun onScopeOperationFail(message: String) {
            if (isAdded) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private val inputMethodManager: InputMethodManager by lazy {
        requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }
    private val clipboardManager: ClipboardManager by lazy {
        requireActivity().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.menu_custom_hook, menu)
            return true
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean =
            when (item.itemId) {
                R.id.action_delete -> { showDeleteSelectedConfirmDialog(); true }
                R.id.action_copy -> { copySelectedHooks(); true }
                else -> false
            }
        override fun onDestroyActionMode(mode: ActionMode) {
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

        fun newInstance(packageName: String?) = CustomHookManagerFragment().apply {
            arguments = Bundle().apply { putString(ARG_PACKAGE_NAME, packageName) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val targetPkgName = viewModel.getTargetPackageName()

        setupToolbar()
        setupRecyclerView()
        setupTracker()
        setupSearchInput()
        setupFabMenu()
        observeViewModel()
        setupHeaderDisplay(targetPkgName)
        setupFragmentResultListener()
    }

    private fun showAutoDetectedHookDialog(hookInfo: CustomHookInfo) {
        editingConfig = null
        CustomHookDialogFragment.newInstance(hookInfo).show(childFragmentManager, CustomHookDialogFragment.TAG)
    }

    private fun setupToolbar() {
        (activity as? AppCompatActivity)?.let { activity ->
            activity.setSupportActionBar(binding.toolBar)
            activity.supportActionBar?.apply {
                setDisplayShowTitleEnabled(false)
                setDisplayHomeAsUpEnabled(true)
                setHomeButtonEnabled(true)
            }
        }
        binding.toolBar.setNavigationOnClickListener {
            (activity as? AppCompatActivity)?.onBackPressedDispatcher?.onBackPressed()
        }
    }

    private fun setupHeaderDisplay(targetPkgName: String?) {
        binding.headerContainer.isVisible = !targetPkgName.isNullOrEmpty()
        if (!targetPkgName.isNullOrEmpty()) {
            loadAppInfoIntoHeader(targetPkgName)
            binding.appHeaderInclude.switchGlobalEnable.isVisible = true
            observeGlobalHookToggle()
            binding.headerContainer.setOnClickListener {
                (activity as? CustomHookActivity)?.showNavigation()
                binding.recyclerView.scrollToPosition(0)
            }
        }
    }

    private fun observeGlobalHookToggle() {
        val switch = binding.appHeaderInclude.switchGlobalEnable
        switch.setOnClickListener {
            val isCheckedAfterClick = switch.isChecked
            val targetPackageName = viewModel.getTargetPackageName()
            viewLifecycleOwner.lifecycleScope.launch {
                handleGlobalHookToggle(isCheckedAfterClick, targetPackageName)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isGlobalEnabled.collect { isEnabled ->
                    binding.appHeaderInclude.switchGlobalEnable.run {
                        if (isChecked != isEnabled) {
                            jumpDrawablesToCurrentState()
                            isChecked = isEnabled
                            jumpDrawablesToCurrentState()
                        }
                        text = if (isEnabled) getString(R.string.enabled) else getString(R.string.disabled)
                    }
                }
            }
        }
    }

    private suspend fun handleGlobalHookToggle(isEnabling: Boolean, pkgName: String?) {
        if (pkgName == null) {
            scopeCallback.onScopeOperationFail("Target package name is not available.")
            viewModel.setGlobalHookStatus(!isEnabling)
            return
        }
        val currentScope = ScopeManager.getScope() ?: run {
            withContext(Dispatchers.Main) {
                scopeCallback.onScopeOperationFail("Failed to get scope. Service might not be connected.")
            }
            viewModel.setGlobalHookStatus(!isEnabling)
            return
        }
        val isPackageInScope = pkgName in currentScope
        if (isEnabling) {
            if (!isPackageInScope) {
                ScopeManager.addScope(pkgName, object : ScopeManager.ScopeCallback {
                    override fun onScopeOperationSuccess(message: String) {
                        scopeCallback.onScopeOperationSuccess(message)
                        viewModel.setGlobalHookStatus(true)
                    }

                    override fun onScopeOperationFail(message: String) {
                        scopeCallback.onScopeOperationFail(message)
                        viewModel.setGlobalHookStatus(false)
                    }
                })
            } else {
                scopeCallback.onScopeOperationSuccess("'$pkgName' is already enabled.")
                viewModel.setGlobalHookStatus(true)
            }
        } else {
            if (isPackageInScope) {
                withContext(Dispatchers.Main) { showRemoveScopeConfirmationDialog(pkgName) }
            } else {
                viewModel.setGlobalHookStatus(false)
            }
        }
    }

    private fun showRemoveScopeConfirmationDialog(packageName: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.remove_scope_dialog_title)
            .setMessage(getString(R.string.disable_scope_dialog_message, packageName))
            .setCancelable(false)
            .setPositiveButton(R.string.action_remove) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val error = ScopeManager.removeScope(packageName)
                    if (error == null) {
                        viewModel.setGlobalHookStatus(false)
                        scopeCallback.onScopeOperationSuccess("$packageName disabled and removed from scope successfully.")
                    } else {
                        viewModel.setGlobalHookStatus(true)
                        scopeCallback.onScopeOperationFail("Failed to remove $packageName from scope: $error")
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                viewModel.setGlobalHookStatus(false)
                dialog.dismiss()
            }
            .show()
    }

    private fun loadAppInfoIntoHeader(packageName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val appNameDeferred = async(Dispatchers.IO) {
                runCatching { AppUtils.getAppName(requireContext(), packageName) }.getOrDefault(packageName)
            }
            val appInfoDeferred = async(Dispatchers.IO) {
                runCatching { requireContext().packageManager.getPackageInfo(packageName, 0) }.getOrNull()
            }
            val iconDeferred = async(Dispatchers.IO) {
                AppIconLoader.loadAndCompressIcon(
                    requireContext(), packageName,
                    AppIconLoader.calculateTargetIconSizePx(requireContext())
                )
            }
            val appName = appNameDeferred.await()
            val appInfo = appInfoDeferred.await()
            val icon = iconDeferred.await()
            val versionName = appInfo?.versionName ?: "N/A"
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                appInfo?.longVersionCode ?: 0L
            } else {
                @Suppress("DEPRECATION")
                appInfo?.versionCode?.toLong() ?: 0L
            }
            binding.appHeaderInclude.apply {
                appNameHeader.text = appName
                appVersionHeader.text = getString(R.string.app_version_format, versionName, versionCode)
                appIconHeader.setImageDrawable(icon)
            }
        }
    }

    private fun setupRecyclerView() {
        hookAdapter = CustomHookAdapter(
            onDeleteItem = ::showDeleteConfigConfirmDialog,
            onEditItem = ::showEditHookDialog,
            onLongClickItem = { config ->
                tracker?.takeIf { !it.hasSelection() }?.select(config)
            },
            onClickItem = { config ->
                tracker?.let {
                    if (it.hasSelection()) {
                        if (it.isSelected(config)) it.deselect(config) else it.select(config)
                    } else {
                        viewModel.toggleHookActivation(config, !config.isEnabled)
                    }
                }
            },
            onToggleEnabled = { config, isEnabled ->
                viewModel.toggleHookActivation(config, isEnabled)
            }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = hookAdapter
            footerSpaceDecoration = FooterSpaceItemDecoration(footerHeight = 80.dp)
            addItemDecoration(footerSpaceDecoration)
            attachNavScrollListener()

            val bottomNavigation = (activity as? CustomHookActivity)?.bottomNavigationView
            if (bottomNavigation == null) {
                FastScrollerBuilder(this).useMd2Style().build()
            } else {
                bottomNavigation.doOnLayout { navigation ->
                    if (_binding == null) return@doOnLayout
                    val bottomPadding = navigation.height
                    setPadding(paddingLeft, paddingTop, paddingRight, bottomPadding)
                    clipToPadding = false
                    FastScrollerBuilder(this)
                        .useMd2Style()
                        .setPadding(0, 0, 0, bottomPadding)
                        .build()
                }
            }
        }
    }

    private fun setupTracker() {
        isSelectionMode = false
        tracker = SelectionTracker.Builder(
            "custom_hook_selection_id",
            binding.recyclerView,
            CustomHookItemKeyProvider(hookAdapter),
            CustomHookItemDetailsLookup(binding.recyclerView),
            StorageStrategy.createParcelableStorage(CustomHookInfo::class.java)
        ).withSelectionPredicate(SelectionPredicates.createSelectAnything()).build().also {
            hookAdapter.tracker = it
            it.addObserver(object : SelectionTracker.SelectionObserver<CustomHookInfo>() {
                override fun onSelectionChanged() {
                    super.onSelectionChanged()
                    val selectedCount = it.selection.size()
                    val selectionMode = selectedCount > 0
                    if (selectionMode != isSelectionMode) {
                        isSelectionMode = selectionMode
                        if (hookAdapter.itemCount > 0) {
                            hookAdapter.notifyItemRangeChanged(0, hookAdapter.itemCount)
                        }
                    }
                    if (selectedCount > 0) {
                        if (currentActionMode == null) {
                            currentActionMode = (activity as? AppCompatActivity)?.startSupportActionMode(actionModeCallback)
                        }
                        currentActionMode?.title = getString(R.string.selected_items_count, selectedCount)
                    } else {
                        currentActionMode?.finish()
                    }
                }
            })
        }
    }

    class CustomHookItemDetailsLookup(private val recyclerView: RecyclerView) : ItemDetailsLookup<CustomHookInfo>() {
        override fun getItemDetails(e: MotionEvent): ItemDetails<CustomHookInfo>? {
            val view = recyclerView.findChildViewUnder(e.x, e.y) ?: return null
            val viewHolder = recyclerView.getChildViewHolder(view) as? CustomHookAdapter.ViewHolder
            return viewHolder?.let {
                val buttonViews = listOf(
                    it.itemView.findViewById<View>(R.id.btn_delete),
                    it.itemView.findViewById<View>(R.id.btn_edit),
                    it.itemView.findViewById<View>(R.id.switch_enabled)
                )
                val isInsideButton = buttonViews.any { button ->
                    val rect = android.graphics.Rect()
                    button.getHitRect(rect)
                    rect.contains(e.x.toInt() - view.left, e.y.toInt() - view.top)
                }
                if (isInsideButton) null else it.getItemDetails()
            }
        }
    }

    class CustomHookItemKeyProvider(private val adapter: CustomHookAdapter) : ItemKeyProvider<CustomHookInfo>(SCOPE_CACHED) {
        override fun getKey(position: Int): CustomHookInfo? = adapter.currentList.getOrNull(position)
        override fun getPosition(key: CustomHookInfo): Int =
            adapter.currentList.indexOfFirst { it.id == key.id }.takeIf { it >= 0 } ?: RecyclerView.NO_POSITION
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
                launch { viewModel.isLoading.collect { binding.progressBar.isVisible = it } }
                launch {
                    viewModel.autoDetectError.collect { error ->
                        if (error == "timeout") {
                            launchedPackages.remove(viewModel.getTargetPackageName())
                            binding.progressBar.isVisible = false
                            showSnackbar(getString(R.string.auto_detect_timeout))
                            viewModel.clearAutoDetectHooksResult()
                        }
                    }
                }
                launch {
                    viewModel.autoDetectedHooksResult.collect { hooks ->
                        hooks?.let {
                            launchedPackages.remove(viewModel.getTargetPackageName())
                            binding.progressBar.isVisible = false
                            showAutoDetectHooksDialog(it)
                            viewModel.clearAutoDetectHooksResult()
                        }
                    }
                }
            }
        }
    }

    private fun showDeleteSelectedConfirmDialog() {
        val selectedItemsList = tracker?.selection?.toList() ?: return
        if (selectedItemsList.isEmpty()) return
        showConfirmDialog(
            titleRes = R.string.delete_selected_hooks_title,
            messageRes = R.string.delete_selected_hooks_message,
            onConfirmed = {
                viewLifecycleOwner.lifecycleScope.launch {
                    val ids = selectedItemsList.mapTo(mutableSetOf()) { it.id }
                    val removed = viewModel.getExportableHooks().filter { it.id in ids }
                    val deletedCount = viewModel.deleteHookConfigs(removed)
                    binding.root.showFeedback(getString(R.string.deleted_hooks_count, deletedCount),
                        undo = { viewModel.restoreHooks(removed) })
                    tracker?.clearSelection()
                }
            }
        )
    }

    private fun copySelectedHooks() {
        val selectedItemsList = tracker?.selection?.toList() ?: return
        if (selectedItemsList.isEmpty()) return
        try {
            val jsonString = viewModel.getJsonStringForConfigs(selectedItemsList)
            val clip = ClipData.newPlainText(getString(R.string.hook_configurations_label), jsonString)
            clipboardManager.setPrimaryClip(clip)
            showSnackbar(getString(R.string.copied_hooks_count, selectedItemsList.size))
        } catch (e: Exception) {
            showSnackbar(getString(R.string.export_failed))
        } finally {
            tracker?.clearSelection()
        }
    }

    private fun setupFabMenu() {
        val hasTargetPackage = viewModel.getTargetPackageName() != null
        if (!hasTargetPackage) {
            binding.fabAutoDetectAds.isVisible = false
        }
        childFabs = buildList {
            if (hasTargetPackage) add(binding.fabAutoDetectAds)
            add(binding.fabClipboardAutoDetect)
            add(binding.fabAddHook)
            add(binding.fabClearAllHooks)
        }
        binding.scrim.setOnClickListener { closeFabMenu() }
        binding.fabMain.setOnClickListener { if (isFabMenuOpen) closeFabMenu() else openFabMenu() }
        val fabClickMap = mapOf(
            binding.fabAutoDetectAds to ::onAutoDetectAdsClicked,
            binding.fabAddHook to ::onAddHookClicked,
            binding.fabClipboardAutoDetect to ::onClipboardAutoDetectClicked,
            binding.fabClearAllHooks to ::onClearAllHooksClicked
        )
        fabClickMap.forEach { (fab, action) -> fab.setOnClickListener { action(); closeFabMenu() } }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.fabMenuContainer.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                rightMargin = 16.dp
                bottomMargin = navigationBars.bottom + 96.dp
            }
            insets
        }
    }

    private fun openFabMenu() {
        if (isFabMenuOpen) return
        isFabMenuOpen = true
        binding.fabMain.contentDescription = getString(R.string.fab_close_actions)
        if (!MotionUtils.areAnimationsEnabled(requireContext())) {
            binding.scrim.visibility = View.VISIBLE
            binding.scrim.alpha = 1f
            binding.fabMain.setImageResource(R.drawable.ic_close)
            childFabs.forEach { fab ->
                fab.visibility = View.VISIBLE
                fab.alpha = 1f
                fab.translationY = 0f
            }
            return
        }
        binding.scrim.visibility = View.VISIBLE
        binding.scrim.animate().alpha(1f).setDuration(300).start()
        binding.fabMain.setImageResource(R.drawable.ic_close)
        (binding.fabMain.drawable as? AnimatedVectorDrawable)?.start()
        childFabs.forEachIndexed { index, fab ->
            fab.visibility = View.VISIBLE
            fab.alpha = 0f
            fab.translationY = ((childFabs.size - index) * 15.dp).toFloat()
            fab.animate().alpha(1f).translationY(0f).setDuration(300).setStartDelay((index * 40).toLong()).start()
        }
    }

    private fun closeFabMenu() {
        if (!isFabMenuOpen) return
        isFabMenuOpen = false
        binding.fabMain.contentDescription = getString(R.string.fab_open_actions)
        if (!MotionUtils.areAnimationsEnabled(requireContext())) {
            binding.scrim.visibility = View.GONE
            binding.fabMain.setImageResource(R.drawable.ic_add)
            childFabs.forEach { fab ->
                fab.visibility = View.INVISIBLE
                fab.alpha = 0f
                fab.translationY = 0f
            }
            return
        }
        binding.scrim.animate().alpha(0f).setDuration(300).withEndAction { binding.scrim.visibility = View.GONE }.start()
        binding.fabMain.setImageResource(R.drawable.ic_add)
        (binding.fabMain.drawable as? AnimatedVectorDrawable)?.start()
        childFabs.reversed().forEachIndexed { index, fab ->
            fab.animate().alpha(0f).translationY(((childFabs.size - index) * 15.dp).toFloat())
                .setDuration(300).setStartDelay((index * 40).toLong())
                .withEndAction { if (!isFabMenuOpen) fab.visibility = View.INVISIBLE }.start()
        }
    }

    private fun onAutoDetectAdsClicked() {
        viewModel.getTargetPackageName()?.let { packageName ->
            binding.progressBar.isVisible = true
            viewModel.requestAutoDetectHooks(packageName)
            showSnackbar(getString(R.string.auto_detect_waiting))

            if (launchedPackages.add(packageName)) { 
                requireContext().packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
                    try {
                        startActivity(launchIntent)
                        showToast(getString(R.string.toast_return_to_check_results))
                    } catch (e: Exception) {
                        Log.e("CustomHookManager", "Failed to launch app: $packageName", e)
                        launchedPackages.remove(packageName)
                    }
                } ?: run {
                    launchedPackages.remove(packageName)
                    showToast(getString(R.string.auto_detect_no_app))
                    binding.progressBar.isVisible = false
                }
            }
        } ?: showSnackbar(getString(R.string.auto_detect_select_app))
    }

    private fun onAddHookClicked() { showAddHookDialog() }
    private fun onClearAllHooksClicked() { showClearAllConfirmDialog() }

    private fun onClipboardAutoDetectClicked() {
        val clipText = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
        if (clipText.isNullOrBlank()) { showToast(getString(R.string.clipboard_content_unrecognized)); return }
        when (val result = ClipboardHookParser.parseClipboardContent(clipText, viewModel.getTargetPackageName())) {
            is ParseResult.JsonImport -> showAutoDetectHooksDialog(result.hooks, titleResId = null)
            is ParseResult.SmaliImport -> {
                editingConfig = null
                CustomHookDialogFragment.newInstance(result.hook).show(childFragmentManager, CustomHookDialogFragment.TAG)
            }
            null -> showToast(getString(R.string.clipboard_content_unrecognized))
        }
    }

    private fun showAutoDetectHooksDialog(detectedHooks: List<CustomHookInfo>, titleResId: Int? = R.string.dialog_auto_detect_ads_title) {
        if (detectedHooks.isEmpty()) { showSnackbar(getString(R.string.no_new_hooks_to_import)); return }
        val existingHooks = viewModel.getExportableHooks()
        val existingHooksMap = existingHooks.associateBy { "${it.className}:${it.methodNames?.joinToString(",")}" }
        val newDetectedHooks = detectedHooks.filter { !existingHooksMap.containsKey("${it.className}:${it.methodNames?.joinToString(",")}") }
        if (newDetectedHooks.isEmpty()) { showSnackbar(getString(R.string.no_new_hooks_to_import)); return }
        val items = newDetectedHooks.map { "${it.className}.${it.methodNames?.joinToString(", ") ?: ""}" }.toTypedArray()
        val checkedItems = BooleanArray(items.size) { true }
        MaterialAlertDialogBuilder(requireContext())
            .apply { if (titleResId != null) setTitle(titleResId) else setTitle(R.string.import_clipboard_title) }
            .setMultiChoiceItems(items, checkedItems) { _, which, isChecked -> checkedItems[which] = isChecked }
            .setPositiveButton(R.string.restore) { dialog, _ ->
                val selectedHooks = newDetectedHooks.filterIndexed { index, _ -> checkedItems[index] }
                if (selectedHooks.isNotEmpty()) { viewModel.importHooks(selectedHooks); showSnackbar(getString(R.string.import_selected_hooks_count, selectedHooks.size)) }
                else showSnackbar(getString(R.string.no_hook_selected))
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showClearAllConfirmDialog() {
        showConfirmDialog(R.string.clear_all_hooks_title, R.string.clear_all_hooks_message) {
            val removed = viewModel.getExportableHooks().toList()
            viewModel.clearAllHooks()
            binding.root.showFeedback(getString(R.string.deleted_hooks_count, removed.size),
                undo = { viewModel.restoreHooks(removed) })
            tracker?.clearSelection()
        }
    }

    private fun updateEmptyView(isEmpty: Boolean) { binding.emptyView.root.isVisible = isEmpty }

    private fun showDeleteConfigConfirmDialog(config: CustomHookInfo) {
        showConfirmDialog(R.string.delete_hook_title, R.string.delete_hook_message) {
            val removed = viewModel.getExportableHooks().filter { it.id == config.id }
            viewModel.deleteHook(config)
            binding.root.showFeedback(getString(R.string.hook_deleted_successfully),
                undo = { viewModel.restoreHooks(removed) })
            tracker?.clearSelection()
        }
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
        childFragmentManager.setFragmentResultListener(CustomHookDialogFragment.REQUEST_KEY_HOOK_CONFIG, viewLifecycleOwner) { _, bundle ->
            BundleCompat.getParcelable(bundle, CustomHookDialogFragment.BUNDLE_KEY_HOOK_CONFIG, CustomHookInfo::class.java)?.let { newOrUpdatedConfig ->
                val isEditing = editingConfig != null
                if (isEditing) viewModel.updateHook(editingConfig!!, newOrUpdatedConfig) else viewModel.addHook(newOrUpdatedConfig)
                showSnackbar(getString(if (isEditing) R.string.hook_updated_successfully else R.string.hook_added_successfully))
                editingConfig = null
            }
        }
    }

    private fun setupSearchInput() {
        binding.editTextSearch.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            setIconAndFocus(if (hasFocus) R.drawable.ic_magnifier_to_back else R.drawable.ic_back_to_magnifier, hasFocus)
        }
        binding.editTextSearch.addTextChangedListener(searchTextWatcher)
        binding.searchIcon.setOnClickListener { onSearchIconClicked() }
        binding.clearSearch.setOnClickListener { binding.editTextSearch.text = null }
        binding.exportHooks.setOnClickListener { onExportHooksClicked() }
        binding.restoreHooks.setOnClickListener { onRestoreHooksClicked() }
    }

    private fun onSearchIconClicked() {
        with(binding.editTextSearch) {
            if (isFocused) { setText(""); setIconAndFocus(R.drawable.ic_back_to_magnifier, false) }
            else setIconAndFocus(R.drawable.ic_magnifier_to_back, true)
        }
    }

    private fun onExportHooksClicked() {
        val fileName = viewModel.getTargetPackageName()?.let { "custom_hooks_${it}.json" } ?: "custom_hooks_global.json"
        backupLauncher.launch(fileName)
    }
    private fun onRestoreHooksClicked() { restoreLauncher.launch(arrayOf("application/json")) }

    private val searchTextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) { viewModel.setSearchQuery(s.toString()) }
        override fun afterTextChanged(s: Editable) {}
    }

    private fun setIconAndFocus(drawableId: Int, focus: Boolean) {
        binding.searchIcon.contentDescription = getString(if (focus) R.string.search_close else R.string.search)
        binding.searchIcon.apply {
            setImageDrawable(ContextCompat.getDrawable(requireContext(), drawableId))
            (drawable as? AnimatedVectorDrawable)?.start()
        }
        with(binding.editTextSearch) {
            if (focus) { requestFocus(); inputMethodManager.showSoftInput(this, 0) }
            else { clearFocus(); inputMethodManager.hideSoftInputFromWindow(windowToken, 0) }
        }
    }

    private fun exportHooks(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val jsonString = viewModel.getJsonStringForConfigs(viewModel.getExportableHooks())
                requireContext().contentResolver.openOutputStream(uri)?.bufferedWriter().use { it?.write(jsonString) }
                withContext(Dispatchers.Main) { showSnackbar(getString(R.string.export_success)) }
            }.onFailure { withContext(Dispatchers.Main) { showMessageDialog(R.string.export_failed, it.message ?: "Unknown error", it) } }
        }
    }

    private fun importHooks(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val jsonString = requireContext().contentResolver.openInputStream(uri)
                    ?.use { it.readTextLimited(MAX_HOOK_IMPORT_BYTES) }
                if (jsonString.isNullOrEmpty()) throw IllegalArgumentException(getString(R.string.error_empty_or_invalid_json))
                val importedConfigs = Json.decodeFromString<List<CustomHookInfo>>(jsonString)
                val importAction = viewModel.getImportAction(importedConfigs)
                withContext(Dispatchers.Main) { handleImportAction(importAction) }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                withContext(Dispatchers.Main) {
                    showMessageDialog(R.string.import_failed, e.message ?: "Unknown error", e)
                }
            }
        }
    }

    private suspend fun handleImportAction(action: ImportAction) {
        when (action) {
            is ImportAction.DirectImport -> { viewModel.importHooks(action.configs); showSnackbar(getString(R.string.import_success)) }
            is ImportAction.PromptImportGlobal -> showImportGlobalRulesDialog(action.configs)
            is ImportAction.ImportGlobalAndNotifySkipped -> {
                val updated = viewModel.importGlobalHooksToStorage(action.globalConfigs)
                val baseMessage = if (updated) getString(R.string.import_success) else getString(R.string.no_new_hooks_to_import_global)
                showSnackbar(if (action.skippedAppSpecificCount > 0) getString(R.string.imported_global_rules_app_specific_skipped, action.skippedAppSpecificCount) else baseMessage)
            }
        }
    }

    private fun showMessageDialog(titleResId: Int, message: String, throwable: Throwable?) {
        MaterialAlertDialogBuilder(requireContext()).setTitle(titleResId).setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .apply {
                if (throwable != null) setNegativeButton(R.string.crash_log) { _, _ ->
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.crash_log)
                        .setMessage(throwable.stackTraceToString()).setPositiveButton(android.R.string.ok, null).show()
                }
            }.show()
    }

    private fun showSnackbar(message: String) {
        binding.root.showFeedback(message)
    }
    private fun showToast(message: String) { binding.root.showFeedback(message) }

    private fun showConfirmDialog(titleRes: Int, messageRes: Int, onConfirmed: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext()).setTitle(titleRes).setMessage(messageRes)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirmed() }.show()
    }

    private fun showImportGlobalRulesDialog(importedConfigs: List<CustomHookInfo>) {
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.import_global_rules_title).setMessage(R.string.import_global_rules_message)
            .setPositiveButton(R.string.import_to_global) { dialog, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val globalOnlyImported = importedConfigs.filter { it.packageName.isNullOrEmpty() }
                    if (globalOnlyImported.isEmpty()) showSnackbar(getString(R.string.no_global_hooks_found_to_import))
                    else {
                        val updated = viewModel.importGlobalHooksToStorage(globalOnlyImported)
                        showSnackbar(getString(if (updated) R.string.imported_to_global_successfully else R.string.no_new_hooks_to_import_global))
                    }
                }
                dialog.dismiss()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    override fun onResume() {
        super.onResume()
        (activity as? OnBackPressContainer)?.backController = this
    }

    override fun onPause() {
        super.onPause()
        (activity as? OnBackPressContainer)?.backController = null
        currentActionMode?.finish()
        if (isFabMenuOpen) closeFabMenu()
        inputMethodManager.hideSoftInputFromWindow(binding.editTextSearch.windowToken, 0)
    }

    override fun onBackPressed(): Boolean {
        return when {
            isFabMenuOpen -> { closeFabMenu(); true }
            binding.editTextSearch.isFocused -> { binding.editTextSearch.setText(""); setIconAndFocus(R.drawable.ic_back_to_magnifier, false); true }
            tracker?.hasSelection() == true -> { tracker?.clearSelection(); true }
            else -> false
        }
    }

    override fun onDestroyView() {
        binding.editTextSearch.removeTextChangedListener(searchTextWatcher)
        childFragmentManager.clearFragmentResultListener(CustomHookDialogFragment.REQUEST_KEY_HOOK_CONFIG)
        super.onDestroyView()
    }
}
