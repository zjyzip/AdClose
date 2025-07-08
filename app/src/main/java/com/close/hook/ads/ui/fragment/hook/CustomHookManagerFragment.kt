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
import android.widget.ArrayAdapter
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
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.close.hook.ads.R
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.data.model.HookMethodType
import com.close.hook.ads.databinding.DialogAddCustomHookBinding
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
import java.io.BufferedReader
import java.io.BufferedWriter

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
                    showDeleteSelectedDialog(mode)
                    true
                }
                R.id.action_copy -> {
                    lifecycleScope.launch {
                        val message = viewModel.copySelectedHooksToJson()
                        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
                        mode?.finish()
                    }
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
            uri?.let {
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
                        withContext(Dispatchers.Main) {
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle(getString(R.string.export_failed))
                                .setMessage(it.message)
                                .setPositiveButton(android.R.string.ok, null)
                                .setNegativeButton(getString(R.string.crash_log)) { _, _ ->
                                    MaterialAlertDialogBuilder(requireContext())
                                        .setTitle(getString(R.string.crash_log))
                                        .setMessage(it.stackTraceToString())
                                        .setPositiveButton(android.R.string.ok, null)
                                        .show()
                                }
                                .show()
                        }
                    }
                }
            }
        }

    private val restoreSAFLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        val inputStream = requireContext().contentResolver.openInputStream(uri)
                        val jsonString = inputStream?.bufferedReader()?.use { it.readText() }

                        if (jsonString.isNullOrEmpty()) {
                            throw IllegalArgumentException(getString(R.string.error_empty_or_invalid_json))
                        }

                        val importedHooks = Json.decodeFromString<List<CustomHookInfo>>(jsonString!!)

                        val isCurrentPageGlobal = viewModel.getTargetPackageName().isNullOrEmpty()
                        val containsGlobalRules = importedHooks.any { it.packageName.isNullOrEmpty() }
                        val containsAppSpecificRules = importedHooks.any { !it.packageName.isNullOrEmpty() }

                        when {
                            !isCurrentPageGlobal && containsGlobalRules -> {
                                withContext(Dispatchers.Main) {
                                    MaterialAlertDialogBuilder(requireContext())
                                        .setTitle(getString(R.string.import_global_rules_title))
                                        .setMessage(getString(R.string.import_global_rules_message))
                                        .setPositiveButton(getString(R.string.import_to_global)) { dialog, _ ->
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                val globalOnlyImported = importedHooks.filter { it.packageName.isNullOrEmpty() }
                                                val updated = viewModel.importGlobalHooksToStorage(globalOnlyImported)
                                                withContext(Dispatchers.Main) {
                                                    if (updated) {
                                                        Snackbar.make(binding.root, getString(R.string.imported_to_global_successfully), Snackbar.LENGTH_SHORT).show()
                                                    } else {
                                                        Snackbar.make(binding.root, getString(R.string.no_new_hooks_to_import_global), Snackbar.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                            dialog.dismiss()
                                        }
                                        .setNegativeButton(android.R.string.cancel, null)
                                        .show()
                                }
                            }
                            isCurrentPageGlobal && containsAppSpecificRules -> {
                                withContext(Dispatchers.Main) {
                                    val appSpecificCount = importedHooks.count { !it.packageName.isNullOrEmpty() }
                                    Snackbar.make(binding.root, getString(R.string.imported_global_rules_app_specific_skipped, appSpecificCount), Snackbar.LENGTH_LONG).show()
                                }
                                val globalOnlyHooks = importedHooks.filter { it.packageName.isNullOrEmpty() }
                                if (globalOnlyHooks.isNotEmpty()) {
                                    val updated = viewModel.importHooks(globalOnlyHooks)
                                    withContext(Dispatchers.Main) {
                                        if (updated) {
                                            Snackbar.make(binding.root, getString(R.string.import_success), Snackbar.LENGTH_SHORT).show()
                                        } else {
                                            Snackbar.make(binding.root, getString(R.string.no_new_hooks_to_import), Snackbar.LENGTH_SHORT).show()
                                        }
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        Snackbar.make(binding.root, getString(R.string.no_global_hooks_found_to_import), Snackbar.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            else -> {
                                val updated = viewModel.importHooks(importedHooks)
                                if (updated) {
                                    withContext(Dispatchers.Main) {
                                        Snackbar.make(binding.root, getString(R.string.import_success), Snackbar.LENGTH_SHORT).show()
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        Snackbar.make(binding.root, getString(R.string.no_new_hooks_to_import), Snackbar.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }.onFailure {
                        withContext(Dispatchers.Main) {
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle(getString(R.string.import_failed))
                                .setMessage(it.message)
                                .setPositiveButton(android.R.string.ok, null)
                                .setNegativeButton(getString(R.string.crash_log)) { _, _ ->
                                    MaterialAlertDialogBuilder(requireContext())
                                        .setTitle(getString(R.string.crash_log))
                                        .setMessage(it.stackTraceToString())
                                        .setPositiveButton(android.R.string.ok, null)
                                        .show()
                                }
                                .show()
                        }
                    }
                }
            }
        }

    companion object {
        private const val ARG_PACKAGE_NAME = "packageName"

        @JvmStatic
        fun newInstance(packageName: String?) =
            CustomHookManagerFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PACKAGE_NAME, packageName)
                }
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCustomHookManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        imm = requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        val packageName = arguments?.getString(ARG_PACKAGE_NAME)
        viewModel.initialize(packageName)

        setupToolbar()
        setupRecyclerView()
        initEditText()
        initButton()
        initFab()
        observeViewModel()
        setupHeaderDisplay(packageName)
    }

    private fun setupToolbar() {
        (activity as? AppCompatActivity)?.apply {
            setSupportActionBar(binding.toolBar)
            supportActionBar?.apply {
                setDisplayShowTitleEnabled(false)
                setDisplayHomeAsUpEnabled(true)
                setHomeButtonEnabled(true)
            }
        }

        binding.toolBar.setNavigationOnClickListener {
            (activity as? AppCompatActivity)?.onBackPressedDispatcher?.onBackPressed()
        }
    }

    private fun setupHeaderDisplay(packageName: String?) {
        if (packageName.isNullOrEmpty()) {
            binding.headerContainer.visibility = View.GONE
            (activity as? AppCompatActivity)?.supportActionBar?.title = getString(R.string.title_global_hook_configs)
        } else {
            binding.headerContainer.visibility = View.VISIBLE
            loadAppInfoIntoHeader(packageName)

            binding.appHeaderInclude.switchGlobalEnable.visibility = View.VISIBLE

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
    }

    private fun loadAppInfoIntoHeader(packageName: String) {
        lifecycleScope.launch {
            val appName = withContext(Dispatchers.IO) {
                AppUtils.getAppName(requireContext(), packageName)
            }
            val appInfo = withContext(Dispatchers.IO) {
                try {
                    requireContext().packageManager.getPackageInfo(packageName, 0)
                } catch (_: Exception) {
                    null
                }
            }

            val versionName = appInfo?.versionName ?: "N/A"
            val versionCode = appInfo?.longVersionCode ?: 0L

            binding.appHeaderInclude.appNameHeader.text = appName
            binding.appHeaderInclude.appVersionHeader.text = getString(R.string.app_version_format, versionName, versionCode)

            val targetSizePx = AppIconLoader.calculateTargetIconSizePx(requireContext())
            val icon = withContext(Dispatchers.IO) {
                AppIconLoader.loadAndCompressIcon(requireContext(), packageName, targetSizePx)
            }
            binding.appHeaderInclude.appIconHeader.setImageDrawable(icon)
            (activity as? AppCompatActivity)?.supportActionBar?.title = appName
        }
    }

    private fun setupRecyclerView() {
        adapter = CustomHookAdapter(
            onItemDelete = { config -> showDeleteDialog(config) },
            onItemEdit = { config -> showEditHookDialog(config) },
            onItemLongClick = { config ->
                if (actionMode == null) {
                    actionMode = (activity as? AppCompatActivity)?.startSupportActionMode(actionModeCallback)
                }
                viewModel.toggleSelection(config)
            },
            onItemClick = { config ->
                if (actionMode != null) {
                    viewModel.toggleSelection(config)
                }
            },
            onToggleEnabled = { config, isEnabled ->
                viewModel.toggleHookActivation(config, isEnabled)
            }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@CustomHookManagerFragment.adapter
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
                        updateActionMode(selectedItems.size)
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

    private fun updateActionMode(selectedCount: Int) {
        if (selectedCount > 0) {
            actionMode = actionMode ?: (activity as? AppCompatActivity)?.startSupportActionMode(actionModeCallback)
            actionMode?.title = getString(R.string.selected_items_count, selectedCount)
        } else {
            actionMode?.finish()
            actionMode = null
        }
    }

    private fun showDeleteSelectedDialog(mode: ActionMode?) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete_selected_hooks_title))
            .setMessage(getString(R.string.delete_selected_hooks_message))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.deleteSelectedHookConfigs()
                mode?.finish()
                Snackbar.make(binding.root, getString(R.string.deleted_hooks_count, viewModel.selectedHooks.value.size), Snackbar.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun initFabMarginParams(): CoordinatorLayout.LayoutParams {
        val margin = 16.dp
        val navigationBarHeight = ViewCompat.getRootWindowInsets(binding.root)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0

        return CoordinatorLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            behavior = HideBottomViewOnScrollBehavior<FloatingActionButton>()
            rightMargin = 25.dp
            bottomMargin = navigationBarHeight + margin
        }
    }

    private fun initFab() {
        binding.fabAddHook.apply {
            layoutParams = initFabMarginParams().apply {
                bottomMargin += (56.dp + 16.dp)
            }
            visibility = View.VISIBLE
            setOnClickListener { showAddHookDialog() }
        }

        binding.fabClearAllHooks.apply {
            layoutParams = initFabMarginParams()
            visibility = View.VISIBLE
            setOnClickListener { showClearAllDialog() }
        }
    }

    private fun showClearAllDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.clear_all_hooks_title))
            .setMessage(getString(R.string.clear_all_hooks_message))
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

    private fun showDeleteDialog(config: CustomHookInfo) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete_hook_title))
            .setMessage(getString(R.string.delete_hook_message))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.deleteHook(config)
                Snackbar.make(binding.root, getString(R.string.hook_deleted_successfully), Snackbar.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showHookDialog(
        config: CustomHookInfo? = null,
        title: String,
        onPositive: (CustomHookInfo) -> Unit
    ) {
        val dialogBinding = DialogAddCustomHookBinding.inflate(layoutInflater)

        val hookMethodTypeAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, HookMethodType.values().map { it.displayName })
        dialogBinding.spinnerHookMethodType.setAdapter(hookMethodTypeAdapter)

        config?.let {
            dialogBinding.etClassName.setText(it.className)
            dialogBinding.etMethodName.setText(it.methodNames?.joinToString(", "))
            dialogBinding.etReturnValue.setText(it.returnValue)
            dialogBinding.etParameterTypes.setText(it.parameterTypes?.joinToString(", "))
            dialogBinding.etFieldName.setText(it.fieldName)
            dialogBinding.etFieldValue.setText(it.fieldValue)
            dialogBinding.spinnerHookMethodType.setText(it.hookMethodType.displayName, false)
        }

        val updateVisibility = { selectedType: HookMethodType ->
            dialogBinding.tilMethodName.isVisible = selectedType in listOf(HookMethodType.HOOK_MULTIPLE_METHODS, HookMethodType.FIND_AND_HOOK_METHOD, HookMethodType.HOOK_ALL_METHODS)
            dialogBinding.tilReturnValue.isVisible = selectedType in listOf(HookMethodType.HOOK_MULTIPLE_METHODS, HookMethodType.FIND_AND_HOOK_METHOD, HookMethodType.HOOK_ALL_METHODS)
            dialogBinding.tilParameterTypes.isVisible = selectedType == HookMethodType.FIND_AND_HOOK_METHOD
            dialogBinding.tilFieldName.isVisible = selectedType == HookMethodType.SET_STATIC_OBJECT_FIELD
            dialogBinding.tilFieldValue.isVisible = selectedType == HookMethodType.SET_STATIC_OBJECT_FIELD
        }

        val initialHookMethodType = config?.hookMethodType ?: HookMethodType.HOOK_MULTIPLE_METHODS
        updateVisibility(initialHookMethodType)

        dialogBinding.spinnerHookMethodType.setOnItemClickListener { _, _, position, _ ->
            updateVisibility(HookMethodType.values()[position])
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.cancel() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val className = dialogBinding.etClassName.text.toString().trim()
                val selectedHookMethodTypeDisplayName = dialogBinding.spinnerHookMethodType.text.toString().trim()
                val selectedHookMethodType = HookMethodType.values().firstOrNull { it.displayName == selectedHookMethodTypeDisplayName }

                if (selectedHookMethodType == null) {
                    Toast.makeText(requireContext(), getString(R.string.select_valid_hook_method), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val methodNames = dialogBinding.etMethodName.text.toString().trim()
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .takeIf { it.isNotEmpty() }
                val returnValue = dialogBinding.etReturnValue.text.toString().trim().takeIf { it.isNotBlank() }
                val parameterTypes = dialogBinding.etParameterTypes.text.toString().trim()
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .takeIf { it.isNotEmpty() }
                val fieldName = dialogBinding.etFieldName.text.toString().trim().takeIf { it.isNotBlank() }
                val fieldValue = dialogBinding.etFieldValue.text.toString().trim().takeIf { it.isNotBlank() }

                var errorMessage: String? = null

                if (className.isBlank()) {
                    errorMessage = getString(R.string.error_class_name_empty)
                } else {
                    when (selectedHookMethodType) {
                        HookMethodType.HOOK_MULTIPLE_METHODS,
                        HookMethodType.HOOK_ALL_METHODS -> {
                            if (methodNames.isNullOrEmpty()) {
                                errorMessage = getString(R.string.error_method_name_empty)
                            }
                        }
                        HookMethodType.FIND_AND_HOOK_METHOD -> {
                            if (methodNames.isNullOrEmpty()) {
                                errorMessage = getString(R.string.error_method_name_empty)
                            } else if (parameterTypes.isNullOrEmpty()) {
                                errorMessage = getString(R.string.error_parameter_types_empty)
                            }
                        }
                        HookMethodType.SET_STATIC_OBJECT_FIELD -> {
                            if (fieldName == null) {
                                errorMessage = getString(R.string.error_field_name_empty)
                            }
                        }
                    }
                }

                if (errorMessage != null) {
                    Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_SHORT).show()
                } else {
                    val newConfig = CustomHookInfo(
                        className = className,
                        methodNames = methodNames,
                        returnValue = returnValue,
                        hookMethodType = selectedHookMethodType,
                        parameterTypes = parameterTypes,
                        fieldName = fieldName,
                        fieldValue = fieldValue,
                        isEnabled = config?.isEnabled ?: false
                    )
                    onPositive(newConfig)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun showAddHookDialog() {
        showHookDialog(title = getString(R.string.add_hook_config_title)) { newConfig ->
            viewModel.addHook(newConfig)
            Snackbar.make(binding.root, getString(R.string.hook_added_successfully), Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun showEditHookDialog(config: CustomHookInfo) {
        showHookDialog(config = config, title = getString(R.string.edit_hook_config_title)) { newConfig ->
            viewModel.updateHook(config, newConfig)
            Snackbar.make(binding.root, getString(R.string.hook_updated_successfully), Snackbar.LENGTH_SHORT).show()
        }
    }

    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            viewModel.setQuery(s.toString())
        }

        override fun afterTextChanged(s: Editable) {}
    }

    private fun initEditText() {
        binding.editTextSearch.onFocusChangeListener =
            View.OnFocusChangeListener { _, hasFocus ->
                setIconAndFocus(
                    if (hasFocus) R.drawable.ic_magnifier_to_back else R.drawable.ic_back_to_magnifier,
                    hasFocus
                )
            }
        binding.editTextSearch.addTextChangedListener(textWatcher)
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

    private fun initButton() {
        binding.apply {
            searchIcon.setOnClickListener {
                if (binding.editTextSearch.isFocused) {
                    binding.editTextSearch.setText("")
                    setIconAndFocus(R.drawable.ic_back_to_magnifier, false)
                } else {
                    setIconAndFocus(R.drawable.ic_magnifier_to_back, true)
                }
            }

            exportHooks.setOnClickListener {
                val filename = viewModel.getTargetPackageName()?.let { packageName ->
                    "${packageName}_custom_hooks.json"
                } ?: "global_custom_hooks.json"
                backupSAFLauncher.launch(filename)
            }

            restoreHooks.setOnClickListener {
                restoreSAFLauncher.launch(arrayOf("application/json"))
            }

            clearSearch.setOnClickListener {
                binding.editTextSearch.text = null
            }
        }
    }

    override fun onBackPressed(): Boolean {
        return if (binding.editTextSearch.isFocused) {
            binding.editTextSearch.setText("")
            setIconAndFocus(R.drawable.ic_back_to_magnifier, false)
            true
        } else if (viewModel.selectedHooks.value.isNotEmpty()) {
            actionMode?.finish()
            true
        } else false
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
