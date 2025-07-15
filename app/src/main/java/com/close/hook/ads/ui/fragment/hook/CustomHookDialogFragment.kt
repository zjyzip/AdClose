package com.close.hook.ads.ui.fragment.hook

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.os.BundleCompat
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.close.hook.ads.R
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.data.model.HookMethodType
import com.close.hook.ads.databinding.DialogAddCustomHookBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.UUID

class CustomHookDialogFragment : DialogFragment() {

    private lateinit var binding: DialogAddCustomHookBinding
    private var initialConfig: CustomHookInfo? = null

    companion object {
        const val TAG = "CustomHookDialogFragment"
        private const val ARG_CONFIG = "config"
        const val REQUEST_KEY_HOOK_CONFIG = "hook_config_request"
        const val BUNDLE_KEY_HOOK_CONFIG = "hook_config_bundle"

        fun newInstance(config: CustomHookInfo? = null): CustomHookDialogFragment =
            CustomHookDialogFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_CONFIG, config)
                }
            }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = DialogAddCustomHookBinding.inflate(LayoutInflater.from(context))
        initialConfig = arguments?.let { BundleCompat.getParcelable(it, ARG_CONFIG, CustomHookInfo::class.java) }

        setupSpinner()
        setupInitialConfig()
        setupFieldVisibilityLogic()
        setupInputValidationListeners()

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .setTitle(if (initialConfig == null) getString(R.string.add_hook_config_title) else getString(R.string.edit_hook_config_title))
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.cancel() }
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(Dialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                if (validateInputs()) {
                    handlePositiveButtonClick(dialog)
                }
            }
        }
        return dialog
    }

    private fun setupSpinner() {
        val hookMethodTypeAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, HookMethodType.values().map { it.displayName })
        binding.spinnerHookMethodType.adapter = hookMethodTypeAdapter

        binding.spinnerHookMethodType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateFieldVisibility(HookMethodType.values()[position])
                clearAllErrors()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }
    }

    private fun setupInitialConfig() {
        initialConfig?.let { config ->
            binding.etClassName.setText(config.className)
            binding.etMethodName.setText(config.methodNames?.joinToString(", "))
            binding.etReturnValue.setText(config.returnValue)
            binding.etParameterTypes.setText(config.parameterTypes?.joinToString(", "))
            binding.etFieldName.setText(config.fieldName)
            binding.etFieldValue.setText(config.fieldValue)
            binding.etSearchStrings.setText(config.searchStrings?.joinToString(", "))

            val initialMethodTypeIndex = HookMethodType.values().indexOfFirst { it == config.hookMethodType }
            if (initialMethodTypeIndex != -1) {
                binding.spinnerHookMethodType.setSelection(initialMethodTypeIndex)
            } else {
                binding.spinnerHookMethodType.setSelection(0)
            }

            when (config.hookPoint) {
                "after" -> binding.toggleGroupHookPoint.check(R.id.btn_hook_after)
                "before" -> binding.toggleGroupHookPoint.check(R.id.btn_hook_before)
                else -> binding.toggleGroupHookPoint.check(R.id.btn_hook_after)
            }
        } ?: run {
            binding.spinnerHookMethodType.setSelection(HookMethodType.values().indexOf(HookMethodType.HOOK_MULTIPLE_METHODS))
            binding.toggleGroupHookPoint.check(R.id.btn_hook_after)
        }
    }

    private fun setupFieldVisibilityLogic() {
        val initialHookMethodType = initialConfig?.hookMethodType ?: HookMethodType.HOOK_MULTIPLE_METHODS
        updateFieldVisibility(initialHookMethodType)
    }

    private fun updateFieldVisibility(selectedType: HookMethodType) {
        val fieldVisibilityMap = mapOf(
            binding.tilClassName to (selectedType !in listOf(
                HookMethodType.HOOK_METHODS_BY_STRING_MATCH,
                HookMethodType.FIND_METHODS_WITH_STRING
            )),
            binding.tilMethodName to (selectedType in listOf(
                HookMethodType.HOOK_MULTIPLE_METHODS,
                HookMethodType.FIND_AND_HOOK_METHOD,
                HookMethodType.HOOK_ALL_METHODS,
                HookMethodType.FIND_METHODS_WITH_STRING
            )),
            binding.tilParameterTypes to (selectedType == HookMethodType.FIND_AND_HOOK_METHOD),
            binding.tilReturnValue to (selectedType in listOf(
                HookMethodType.HOOK_MULTIPLE_METHODS,
                HookMethodType.FIND_AND_HOOK_METHOD,
                HookMethodType.HOOK_ALL_METHODS,
                HookMethodType.HOOK_METHODS_BY_STRING_MATCH,
                HookMethodType.FIND_METHODS_WITH_STRING
            )),
            binding.tilFieldName to (selectedType == HookMethodType.SET_STATIC_OBJECT_FIELD),
            binding.tilFieldValue to (selectedType == HookMethodType.SET_STATIC_OBJECT_FIELD),
            binding.tilSearchStrings to (selectedType in listOf(
                HookMethodType.HOOK_METHODS_BY_STRING_MATCH,
                HookMethodType.FIND_METHODS_WITH_STRING
            )),
            binding.toggleGroupHookPoint to (selectedType in listOf(
                HookMethodType.HOOK_MULTIPLE_METHODS,
                HookMethodType.FIND_AND_HOOK_METHOD,
                HookMethodType.HOOK_ALL_METHODS,
                HookMethodType.HOOK_METHODS_BY_STRING_MATCH,
                HookMethodType.FIND_METHODS_WITH_STRING
            )),
            binding.tvHookPointLabel to (selectedType in listOf(
                HookMethodType.HOOK_MULTIPLE_METHODS,
                HookMethodType.FIND_AND_HOOK_METHOD,
                HookMethodType.HOOK_ALL_METHODS,
                HookMethodType.HOOK_METHODS_BY_STRING_MATCH,
                HookMethodType.FIND_METHODS_WITH_STRING
            ))
        )

        fieldVisibilityMap.forEach { (view, isVisible) ->
            view.isVisible = isVisible
            if (!isVisible && view is TextInputLayout) {
                view.error = null
            }
        }
    }

    private fun setupInputValidationListeners() {
        binding.etClassName.addTextChangedListener(createValidationTextWatcher(binding.tilClassName, R.string.error_class_name_empty))
        binding.etMethodName.addTextChangedListener(createValidationTextWatcher(binding.tilMethodName, R.string.error_method_name_empty))
        binding.etParameterTypes.addTextChangedListener(createValidationTextWatcher(binding.tilParameterTypes, R.string.error_parameter_types_empty))
        binding.etFieldName.addTextChangedListener(createValidationTextWatcher(binding.tilFieldName, R.string.error_field_name_empty))
        binding.etSearchStrings.addTextChangedListener(createValidationTextWatcher(binding.tilSearchStrings, R.string.error_search_strings_empty))
    }

    private fun createValidationTextWatcher(inputLayout: TextInputLayout, errorResId: Int): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (inputLayout.isVisible) {
                    if (s.isNullOrBlank()) {
                        inputLayout.error = inputLayout.context.getString(errorResId)
                    } else {
                        inputLayout.error = null
                    }
                } else {
                    inputLayout.error = null
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        }
    }

    private fun validateInputs(): Boolean {
        clearAllErrors()

        val selectedHookMethodType = HookMethodType.fromDisplayName(binding.spinnerHookMethodType.selectedItem?.toString().orEmpty())
            ?: return false

        var isValid = true

        if (binding.tilClassName.isVisible && binding.etClassName.text.isNullOrBlank()) {
            binding.tilClassName.error = getString(R.string.error_class_name_empty)
            isValid = false
        }

        when (selectedHookMethodType) {
            HookMethodType.HOOK_MULTIPLE_METHODS,
            HookMethodType.HOOK_ALL_METHODS -> {
                if (binding.tilMethodName.isVisible && binding.etMethodName.text.isNullOrBlank()) {
                    binding.tilMethodName.error = getString(R.string.error_method_name_empty)
                    isValid = false
                }
            }
            HookMethodType.FIND_AND_HOOK_METHOD -> {
                if (binding.tilMethodName.isVisible && binding.etMethodName.text.isNullOrBlank()) {
                    binding.tilMethodName.error = getString(R.string.error_method_name_empty)
                    isValid = false
                }
                if (binding.tilParameterTypes.isVisible && binding.etParameterTypes.text.isNullOrBlank()) {
                    binding.tilParameterTypes.error = getString(R.string.error_parameter_types_empty)
                    isValid = false
                }
            }
            HookMethodType.SET_STATIC_OBJECT_FIELD -> {
                if (binding.tilFieldName.isVisible && binding.etFieldName.text.isNullOrBlank()) {
                    binding.tilFieldName.error = getString(R.string.error_field_name_empty)
                    isValid = false
                }
            }
            HookMethodType.HOOK_METHODS_BY_STRING_MATCH -> {
                if (binding.tilSearchStrings.isVisible && binding.etSearchStrings.text.isNullOrBlank()) {
                    binding.tilSearchStrings.error = getString(R.string.error_search_strings_empty)
                    isValid = false
                }
            }
            HookMethodType.FIND_METHODS_WITH_STRING -> {
                if (binding.tilSearchStrings.isVisible && binding.etSearchStrings.text.isNullOrBlank()) {
                    binding.tilSearchStrings.error = getString(R.string.error_search_strings_empty)
                    isValid = false
                }
                if (binding.tilMethodName.isVisible && binding.etMethodName.text.isNullOrBlank()) {
                    binding.tilMethodName.error = getString(R.string.error_method_name_empty)
                    isValid = false
                }
            }
            else -> {}
        }
        return isValid
    }

    private fun clearAllErrors() {
        binding.tilClassName.error = null
        binding.tilMethodName.error = null
        binding.tilParameterTypes.error = null
        binding.tilFieldName.error = null
        binding.tilReturnValue.error = null
        binding.tilFieldValue.error = null
        binding.tilSearchStrings.error = null
    }

    private fun handlePositiveButtonClick(dialog: Dialog) {
        val className = binding.etClassName.getTextIfVisibleAndNotBlank() ?: ""

        val selectedHookMethodType = HookMethodType.fromDisplayName(binding.spinnerHookMethodType.selectedItem?.toString().orEmpty())
            ?: return

        val hookPoint = when (binding.toggleGroupHookPoint.checkedButtonId) {
            R.id.btn_hook_after -> "after"
            R.id.btn_hook_before -> "before"
            else -> null
        }.takeIf { binding.toggleGroupHookPoint.isVisible }

        val methodNames = binding.etMethodName.getTextListIfVisibleAndNotBlank()
        val returnValue = binding.etReturnValue.getTextIfVisibleAndNotBlank()
        val parameterTypes = binding.etParameterTypes.getTextListIfVisibleAndNotBlank()
        val fieldName = binding.etFieldName.getTextIfVisibleAndNotBlank()
        val fieldValue = binding.etFieldValue.getTextIfVisibleAndNotBlank()
        val searchStrings = binding.etSearchStrings.getTextListIfVisibleAndNotBlank()

        val newConfig = CustomHookInfo(
            id = initialConfig?.id ?: UUID.randomUUID().toString(),
            className = className,
            methodNames = methodNames,
            returnValue = returnValue,
            hookMethodType = selectedHookMethodType,
            parameterTypes = parameterTypes,
            fieldName = fieldName,
            fieldValue = fieldValue,
            isEnabled = true,
            packageName = initialConfig?.packageName,
            hookPoint = hookPoint,
            searchStrings = searchStrings
        )

        val resultBundle = Bundle().apply {
            putParcelable(BUNDLE_KEY_HOOK_CONFIG, newConfig)
        }
        setFragmentResult(REQUEST_KEY_HOOK_CONFIG, resultBundle)

        dialog.dismiss()
    }
}

private fun TextInputEditText.getTextIfVisibleAndNotBlank(): String? {
    return text.toString().trim().takeIf { it.isNotBlank() && (parent.parent as? View)?.isVisible == true }
}

private fun TextInputEditText.getTextListIfVisibleAndNotBlank(): List<String>? {
    return text.toString().trim()
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .takeIf { it.isNotEmpty() && (parent.parent as? View)?.isVisible == true }
}
