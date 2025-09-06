package com.close.hook.ads.ui.fragment.hook

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.close.hook.ads.util.INavContainer
import com.close.hook.ads.util.dp
import com.close.hook.ads.util.FooterSpaceItemDecoration
import com.close.hook.ads.util.OnBackPressContainer
import com.close.hook.ads.util.OnBackPressListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.zhanghai.android.fastscroll.FastScrollerBuilder

class CustomHookManagerFragment : BaseFragment<FragmentCustomHookManagerBinding>(), OnBackPressListener {

    private val viewModel: CustomHookViewModel by viewModels {
        CustomHookViewModel.Factory(
            requireActivity().application,
            arguments?.getString(ARG_PACKAGE_NAME)
        )
    }

    private lateinit var hookAdapter: CustomHookAdapter
    private lateinit var footerSpaceDecoration: FooterSpaceItemDecoration
    private var tracker: SelectionTracker<CustomHookInfo>? = null
    private var currentActionMode: ActionMode? = null
    private var editingConfig: CustomHookInfo? = null
    private var isFabMenuOpen = false
    private lateinit var childFabs: List<View>

    private val scopeCallback = object : ScopeManager.ScopeCallback {
        override fun onScopeOperationSuccess(message: String) {
            Log.i("CustomHookManagerFragment", message)
        }

        override fun onScopeOperationFail(message: String) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    private val snackbarLayoutParams: CoordinatorLayout.LayoutParams by lazy {
        CoordinatorLayout.LayoutParams(
            CoordinatorLayout.LayoutParams.MATCH_PARENT,
            CoordinatorLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            setMargins(10.dp, 0, 10.dp, 90.dp)
        }
    }

    private val inputMethodManager: InputMethodManager by lazy {
        requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }
    private val clipboardManager: ClipboardManager by lazy {
        requireActivity().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    private val autoDetectResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_AUTO_DETECT_ADS_RESULT) {
                intent.extras?.let { extras ->
                    BundleCompat.getParcelableArrayList(extras, "detected_hooks_result", CustomHookInfo::class.java)?.let {
                        viewModel.handleAutoDetectResult(it.toList())
                    }
                }
            }
        }
    }

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.menu_custom_hook, menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean =
            when (item.itemId) {
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
        const val REQUEST_KEY_HOOK_CONFIG = "hook_config_request"
        const val BUNDLE_KEY_HOOK_CONFIG = "hook_config_bundle"
        private const val ACTION_AUTO_DETECT_ADS_RESULT = "com.close.hook.ads.ACTION_AUTO_DETECT_ADS_RESULT"

        fun newInstance(packageName: String?) = CustomHookManagerFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_PACKAGE_NAME, packageName)
            }
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
        setupBroadcastReceiver()
    }

    private fun setupBroadcastReceiver() {
        val filter = IntentFilter(ACTION_AUTO_DETECT_ADS_RESULT)
        requireContext().registerReceiver(autoDetectResultReceiver, filter, getReceiverOptions())
    }

    private fun getReceiverOptions(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0

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

            lifecycleScope.launch {
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
                ScopeManager.addScope(pkgName, scopeCallback)
            } else {
                scopeCallback.onScopeOperationSuccess("'$pkgName' is already enabled.")
            }
            viewModel.setGlobalHookStatus(true)
        } else {
            if (isPackageInScope) {
                withContext(Dispatchers.Main) {
                    showRemoveScopeConfirmationDialog(pkgName)
                }
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
                viewModel.setGlobalHookStatus(false) 
                
                lifecycleScope.launch {
                    val error = ScopeManager.removeScope(packageName)
                    if (error == null) {
                        scopeCallback.onScopeOperationSuccess("$packageName disabled and removed from scope successfully.")
                    } else {
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
        lifecycleScope.launch {
            val appName = withContext(Dispatchers.IO) {
                runCatching { AppUtils.getAppName(requireContext(), packageName) }.getOrDefault(packageName)
            }
            val appInfo = withContext(Dispatchers.IO) {
                runCatching { requireContext().packageManager.getPackageInfo(packageName, 0) }.getOrNull()
            }
            val versionName = appInfo?.versionName ?: "N/A"
            val versionCode = appInfo?.longVersionCode ?: 0L
            val icon = withContext(Dispatchers.IO) {
                AppIconLoader.loadAndCompressIcon(requireContext(), packageName, AppIconLoader.calculateTargetIconSizePx(requireContext()))
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
            FastScrollerBuilder(this).useMd2Style().build()

            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val bottomNavHeight = (activity as? CustomHookActivity)?.bottomNavigationView?.height ?: 0
                setPadding(paddingLeft, paddingTop, paddingRight, bottomNavHeight)
                clipToPadding = false
            }

            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                private var totalDy = 0
                private val scrollThreshold = 20.dp
                private val navContainer = activity as? INavContainer

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (navContainer == null) return
                    super.onScrolled(recyclerView, dx, dy)

                    if (dy > 0) {
                        totalDy += dy
                        if (totalDy > scrollThreshold) {
                            navContainer.hideNavigation()
                            totalDy = 0
                            if (isFabMenuOpen) closeFabMenu()
                        }
                    } else if (dy < 0) {
                        totalDy += dy
                        if (totalDy < -scrollThreshold) {
                            navContainer.showNavigation()
                            totalDy = 0
                        }
                    }
                }
            })
        }
    }

    private fun setupTracker() {
        tracker = SelectionTracker.Builder(
            "custom_hook_selection_id",
            binding.recyclerView,
            CustomHookItemKeyProvider(hookAdapter),
            CustomHookItemDetailsLookup(binding.recyclerView),
            StorageStrategy.createParcelableStorage(CustomHookInfo::class.java)
        ).withSelectionPredicate(
            SelectionPredicates.createSelectAnything()
        ).build().also {
            hookAdapter.tracker = it
            it.addObserver(object : SelectionTracker.SelectionObserver<CustomHookInfo>() {
                override fun onSelectionChanged() {
                    super.onSelectionChanged()
                    val selectedCount = it.selection.size()
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

    class CustomHookItemDetailsLookup(private val recyclerView: RecyclerView) :
        ItemDetailsLookup<CustomHookInfo>() {
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

    class CustomHookItemKeyProvider(private val adapter: CustomHookAdapter) :
        ItemKeyProvider<CustomHookInfo>(SCOPE_CACHED) {
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
                launch {
                    viewModel.isLoading.collect { binding.progressBar.isVisible = it }
                }
                launch {
                    viewModel.autoDetectedHooksResult.collect { hooks ->
                        hooks?.let {
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
                lifecycleScope.launch {
                    val deletedCount = viewModel.deleteHookConfigs(selectedItemsList)
                    showSnackbar(getString(R.string.deleted_hooks_count, deletedCount))
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
        } finally {
            tracker?.clearSelection()
        }
    }

    private fun setupFabMenu() {
        childFabs = listOf(
            binding.fabAutoDetectAds,
            binding.fabClipboardAutoDetect,
            binding.fabAddHook,
            binding.fabClearAllHooks
        )

        binding.scrim.setOnClickListener { closeFabMenu() }
        binding.fabMain.setOnClickListener { if (isFabMenuOpen) closeFabMenu() else openFabMenu() }

        val fabClickMap = mapOf(
            binding.fabAutoDetectAds to ::onAutoDetectAdsClicked,
            binding.fabAddHook to ::onAddHookClicked,
            binding.fabClipboardAutoDetect to ::onClipboardAutoDetectClicked,
            binding.fabClearAllHooks to ::onClearAllHooksClicked
        )
        fabClickMap.forEach { (fab, action) ->
            fab.setOnClickListener {
                action()
                closeFabMenu()
            }
        }

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

        binding.scrim.visibility = View.VISIBLE
        binding.scrim.animate().alpha(1f).setDuration(300).start()

        binding.fabMain.setImageResource(R.drawable.ic_close)
        (binding.fabMain.drawable as? AnimatedVectorDrawable)?.start()

        childFabs.forEachIndexed { index, fab ->
            fab.visibility = View.VISIBLE
            fab.alpha = 0f
            fab.translationY = ((childFabs.size - index) * 15.dp).toFloat()
            fab.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .setStartDelay((index * 40).toLong())
                .start()
        }
    }

    private fun closeFabMenu() {
        if (!isFabMenuOpen) return
        isFabMenuOpen = false

        binding.scrim.animate().alpha(0f).setDuration(300).withEndAction {
            binding.scrim.visibility = View.GONE
        }.start()

        binding.fabMain.setImageResource(R.drawable.ic_add)
        (binding.fabMain.drawable as? AnimatedVectorDrawable)?.start()

        childFabs.reversed().forEachIndexed { index, fab ->
            fab.animate()
                .alpha(0f)
                .translationY(((childFabs.size - index) * 15.dp).toFloat())
                .setDuration(300)
                .setStartDelay((index * 40).toLong())
                .withEndAction {
                    if (!isFabMenuOpen) {
                        fab.visibility = View.INVISIBLE
                    }
                }
                .start()
        }
    }

    private fun onAutoDetectAdsClicked() {
        viewModel.getTargetPackageName()?.let { packageName ->
            binding.progressBar.isVisible = true
            viewModel.requestAutoDetectHooks(packageName)
            showSnackbar("已发送请求，请等待扫描结果...")
        } ?: showSnackbar("请先选择一个应用")
    }
    
    private fun onAddHookClicked() {
        showAddHookDialog()
    }
    
    private fun onClipboardAutoDetectClicked() {
        val clipText = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
        if (clipText.isNullOrBlank()) {
            showToast(getString(R.string.clipboard_content_unrecognized))
            return
        }

        ClipboardHookParser.parseClipboardContent(clipText, viewModel.getTargetPackageName())?.let {
            showAutoDetectedHookDialog(it)
        } ?: showToast(getString(R.string.clipboard_content_unrecognized))
    }

    private fun onClearAllHooksClicked() {
        showClearAllConfirmDialog()
    }

    private fun showAutoDetectHooksDialog(detectedHooks: List<CustomHookInfo>) {
        if (detectedHooks.isEmpty()) {
            showSnackbar(getString(R.string.no_new_hooks_to_import))
            return
        }

        val existingHooks = viewModel.getExportableHooks()
        val existingHooksMap = existingHooks.associateBy { "${it.className}:${it.methodNames?.joinToString(",")}" }
        val newDetectedHooks = detectedHooks.filter { newHook ->
            val key = "${newHook.className}:${newHook.methodNames?.joinToString(",")}"
            !existingHooksMap.containsKey(key)
        }

        if (newDetectedHooks.isEmpty()) {
            showSnackbar(getString(R.string.no_new_hooks_to_import))
            return
        }

        val items = newDetectedHooks.map {
            "${it.className}.${it.methodNames?.joinToString(", ") ?: ""}"
        }.toTypedArray()
        val checkedItems = BooleanArray(items.size) { true }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_auto_detect_ads_title)
            .setMultiChoiceItems(items, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton(R.string.restore) { dialog, _ ->
                val selectedHooks = newDetectedHooks.filterIndexed { index, _ -> checkedItems[index] }
                if (selectedHooks.isNotEmpty()) {
                    viewModel.importHooks(selectedHooks)
                    showSnackbar(getString(R.string.import_selected_hooks_count, selectedHooks.size))
                } else {
                    showSnackbar(getString(R.string.no_hook_selected))
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showClearAllConfirmDialog() {
        showConfirmDialog(
            titleRes = R.string.clear_all_hooks_title,
            messageRes = R.string.clear_all_hooks_message,
            onConfirmed = {
                viewModel.clearAllHooks()
                showSnackbar(getString(R.string.all_hooks_cleared))
                tracker?.clearSelection()
            }
        )
    }

    private fun updateEmptyView(isEmpty: Boolean) {
        binding.emptyView.isVisible = isEmpty
    }

    private fun showDeleteConfigConfirmDialog(config: CustomHookInfo) {
        showConfirmDialog(
            titleRes = R.string.delete_hook_title,
            messageRes = R.string.delete_hook_message,
            onConfirmed = {
                viewModel.deleteHook(config)
                showSnackbar(getString(R.string.hook_deleted_successfully))
                tracker?.clearSelection()
            }
        )
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
        childFragmentManager.setFragmentResultListener(REQUEST_KEY_HOOK_CONFIG, viewLifecycleOwner) { _, bundle ->
            BundleCompat.getParcelable(bundle, BUNDLE_KEY_HOOK_CONFIG, CustomHookInfo::class.java)?.let { newOrUpdatedConfig ->
                val isEditing = editingConfig != null
                if (isEditing) {
                    viewModel.updateHook(editingConfig!!, newOrUpdatedConfig)
                } else {
                    viewModel.addHook(newOrUpdatedConfig)
                }
                val messageResId = if (isEditing) R.string.hook_updated_successfully else R.string.hook_added_successfully
                showSnackbar(getString(messageResId))
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
            if (isFocused) {
                setText("")
                setIconAndFocus(R.drawable.ic_back_to_magnifier, false)
            } else {
                setIconAndFocus(R.drawable.ic_magnifier_to_back, true)
            }
        }
    }

    private fun onExportHooksClicked() {
        val fileName = viewModel.getTargetPackageName()?.let { "custom_hooks_${it}.json" } ?: "custom_hooks_global.json"
        backupLauncher.launch(fileName)
    }

    private fun onRestoreHooksClicked() {
        restoreLauncher.launch(arrayOf("application/json"))
    }

    private val searchTextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) { viewModel.setSearchQuery(s.toString()) }
        override fun afterTextChanged(s: Editable) {}
    }

    private fun setIconAndFocus(drawableId: Int, focus: Boolean) {
        binding.searchIcon.apply {
            setImageDrawable(ContextCompat.getDrawable(requireContext(), drawableId))
            (drawable as? AnimatedVectorDrawable)?.start()
        }
        with(binding.editTextSearch) {
            if (focus) {
                requestFocus()
                inputMethodManager.showSoftInput(this, 0)
            } else {
                clearFocus()
                inputMethodManager.hideSoftInputFromWindow(windowToken, 0)
            }
        }
    }

    private fun exportHooks(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val jsonString = viewModel.getJsonStringForConfigs(viewModel.getExportableHooks())
                requireContext().contentResolver.openOutputStream(uri)?.bufferedWriter().use { it?.write(jsonString) }
                withContext(Dispatchers.Main) { showSnackbar(getString(R.string.export_success)) }
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
                val importAction = viewModel.getImportAction(importedConfigs)

                withContext(Dispatchers.Main) { handleImportAction(importAction) }
            }.onFailure {
                withContext(Dispatchers.Main) { showMessageDialog(R.string.import_failed, it.message ?: "Unknown error", it) }
            }
        }
    }

    private suspend fun handleImportAction(action: ImportAction) {
        when (action) {
            is ImportAction.DirectImport -> {
                viewModel.importHooks(action.configs)
                showSnackbar(getString(R.string.import_success))
            }
            is ImportAction.PromptImportGlobal -> {
                showImportGlobalRulesDialog(action.configs)
            }
            is ImportAction.ImportGlobalAndNotifySkipped -> {
                val updated = viewModel.importGlobalHooksToStorage(action.globalConfigs)
                val baseMessage = if (updated) getString(R.string.import_success) else getString(R.string.no_new_hooks_to_import_global)
                val finalMessage = if (action.skippedAppSpecificCount > 0) {
                    getString(R.string.imported_global_rules_app_specific_skipped, action.skippedAppSpecificCount)
                } else {
                    baseMessage
                }
                showSnackbar(finalMessage)
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
                            .setMessage(throwable.stackTraceToString())
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun showSnackbar(message: String) {
        val snackBar = Snackbar.make(
            binding.root,
            message,
            Snackbar.LENGTH_SHORT
        )
        snackBar.view.layoutParams = snackbarLayoutParams
        snackBar.show()
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun showConfirmDialog(titleRes: Int, messageRes: Int, onConfirmed: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirmed() }
            .show()
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
                        val messageResId = if (updated) R.string.imported_to_global_successfully else R.string.no_new_hooks_to_import_global
                        showSnackbar(getString(messageResId))
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
            isFabMenuOpen -> {
                closeFabMenu()
                true
            }
            binding.editTextSearch.isFocused -> {
                binding.editTextSearch.setText("")
                setIconAndFocus(R.drawable.ic_back_to_magnifier, false)
                true
            }
            tracker?.hasSelection() == true -> {
                tracker?.clearSelection()
                true
            }
            else -> false
        }
    }

    override fun onDestroyView() {
        requireContext().unregisterReceiver(autoDetectResultReceiver)
        binding.editTextSearch.removeTextChangedListener(searchTextWatcher)
        childFragmentManager.clearFragmentResultListener(REQUEST_KEY_HOOK_CONFIG)
        super.onDestroyView()
    }
}
