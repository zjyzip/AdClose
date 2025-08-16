package com.close.hook.ads.ui.fragment.hook

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.os.BundleCompat
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.close.hook.ads.R
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.data.model.HookField
import com.close.hook.ads.data.model.HookMethodType
import com.close.hook.ads.databinding.DialogAddCustomHookBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import java.util.UUID

class CustomHookDialogFragment : DialogFragment() {

    private var _binding: DialogAddCustomHookBinding? = null
    private val binding get() = _binding!!
    
    private var initialConfig: CustomHookInfo? = null

    private val fieldViews by lazy {
        mapOf(
            HookField.CLASS_NAME to (binding.tilClassName to binding.etClassName),
            HookField.METHOD_NAME to (binding.tilMethodName to binding.etMethodName),
            HookField.PARAMETER_TYPES to (binding.tilParameterTypes to binding.etParameterTypes),
            HookField.RETURN_VALUE to (binding.tilReturnValue to binding.etReturnValue),
            HookField.FIELD_NAME to (binding.tilFieldName to binding.etFieldName),
            HookField.FIELD_VALUE to (binding.tilFieldValue to binding.etFieldValue),
            HookField.SEARCH_STRINGS to (binding.tilSearchStrings to binding.etSearchStrings)
        )
    }

    companion object {
        const val TAG = "CustomHookDialogFragment"
        private const val ARG_CONFIG = "config"
        const val REQUEST_KEY_HOOK_CONFIG = "hook_config_request"
        const val BUNDLE_KEY_HOOK_CONFIG = "hook_config_bundle"

        fun newInstance(config: CustomHookInfo? = null): CustomHookDialogFragment =
            CustomHookDialogFragment().apply {
                arguments = Bundle().apply { putParcelable(ARG_CONFIG, config) }
            }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogAddCustomHookBinding.inflate(LayoutInflater.from(context))
        initialConfig = arguments?.let { BundleCompat.getParcelable(it, ARG_CONFIG, CustomHookInfo::class.java) }

        setupSpinner()
        setupInitialState()
        setupInputValidationListeners()

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .setTitle(if (initialConfig == null) getString(R.string.add_hook_config_title) else getString(R.string.edit_hook_config_title))
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(Dialog.BUTTON_POSITIVE)?.setOnClickListener {
                if (validateInputs()) {
                    handlePositiveButtonClick(dialog)
                }
            }
        }
        return dialog
    }

    private fun setupSpinner() {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, HookMethodType.entries.map { it.displayName })
        binding.spinnerHookMethodType.adapter = adapter
        binding.spinnerHookMethodType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateFieldVisibility(HookMethodType.entries[position])
                clearAllErrors()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupInitialState() {
        val config = initialConfig
        val initialType = config?.hookMethodType ?: HookMethodType.HOOK_ALL_METHODS
        
        binding.spinnerHookMethodType.setSelection(initialType.ordinal)
        updateFieldVisibility(initialType)

        if (config != null) {
            binding.etClassName.setText(config.className)
            binding.etMethodName.setText(config.methodNames?.joinToString(", "))
            binding.etReturnValue.setText(config.returnValue)
            binding.etParameterTypes.setText(config.parameterTypes?.joinToString(", "))
            binding.etFieldName.setText(config.fieldName)
            binding.etFieldValue.setText(config.fieldValue)
            binding.etSearchStrings.setText(config.searchStrings?.joinToString(", "))
            
            val hookPointButtonId = when (config.hookPoint) {
                "before" -> R.id.btn_hook_before
                else -> R.id.btn_hook_after
            }
            binding.toggleGroupHookPoint.check(hookPointButtonId)
        } else {
            binding.toggleGroupHookPoint.check(R.id.btn_hook_before)
        }
    }

    private fun updateFieldVisibility(selectedType: HookMethodType) {
        val visibleFields = selectedType.visibleFields
        fieldViews.forEach { (field, views) ->
            views.first.isVisible = field in visibleFields
        }
        binding.toggleGroupHookPoint.isVisible = HookField.HOOK_POINT in visibleFields
        binding.tvHookPointLabel.isVisible = binding.toggleGroupHookPoint.isVisible
    }
    
    private fun setupInputValidationListeners() {
        fieldViews.forEach { (field, views) ->
            val (layout, editText) = views
            getErrorResIdForField(field)?.let { errorResId ->
                editText.addTextChangedListener(createValidationTextWatcher(layout, errorResId))
            }
        }
    }

    private fun createValidationTextWatcher(inputLayout: TextInputLayout, errorResId: Int): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            inputLayout.error = if (inputLayout.isVisible && s.isNullOrBlank()) getString(errorResId) else null
        }
        override fun afterTextChanged(s: Editable?) {}
    }

    private fun validateInputs(): Boolean {
        clearAllErrors()
        var isValid = true
        val selectedType = HookMethodType.entries[binding.spinnerHookMethodType.selectedItemPosition]

        selectedType.requiredFields.forEach { field ->
            val (layout, editText) = fieldViews[field]!!
            if (editText.text.isNullOrBlank()) {
                layout.error = getString(getErrorResIdForField(field)!!)
                isValid = false
            }
        }
        return isValid
    }

    private fun clearAllErrors() {
        fieldViews.values.forEach { it.first.error = null }
    }

    private fun handlePositiveButtonClick(dialog: Dialog) {
        val selectedType = HookMethodType.entries[binding.spinnerHookMethodType.selectedItemPosition]
        
        fun getTextFor(field: HookField): String? = fieldViews[field]?.second?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        fun getListFor(field: HookField): List<String>? = getTextFor(field)?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }

        val newConfig = CustomHookInfo(
            id = initialConfig?.id ?: UUID.randomUUID().toString(),
            isEnabled = initialConfig?.isEnabled ?: true,
            packageName = initialConfig?.packageName,
            hookMethodType = selectedType,
            className = getTextFor(HookField.CLASS_NAME) ?: "",
            methodNames = getListFor(HookField.METHOD_NAME),
            parameterTypes = getListFor(HookField.PARAMETER_TYPES),
            returnValue = getTextFor(HookField.RETURN_VALUE),
            fieldName = getTextFor(HookField.FIELD_NAME),
            fieldValue = getTextFor(HookField.FIELD_VALUE),
            searchStrings = getListFor(HookField.SEARCH_STRINGS),
            hookPoint = when (binding.toggleGroupHookPoint.checkedButtonId) {
                R.id.btn_hook_before -> "before"
                else -> "after"
            }.takeIf { HookField.HOOK_POINT in selectedType.visibleFields }
        )

        setFragmentResult(REQUEST_KEY_HOOK_CONFIG, Bundle().apply { putParcelable(BUNDLE_KEY_HOOK_CONFIG, newConfig) })
        dialog.dismiss()
    }
    
    private fun getErrorResIdForField(field: HookField): Int? {
        return when (field) {
            HookField.CLASS_NAME -> R.string.error_class_name_empty
            HookField.METHOD_NAME -> R.string.error_method_name_empty
            HookField.PARAMETER_TYPES -> R.string.error_parameter_types_empty
            HookField.FIELD_NAME -> R.string.error_field_name_empty
            HookField.SEARCH_STRINGS -> R.string.error_search_strings_empty
            else -> null
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
