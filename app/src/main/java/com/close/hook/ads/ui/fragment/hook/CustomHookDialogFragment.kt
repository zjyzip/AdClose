package com.close.hook.ads.ui.fragment.hook

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import com.close.hook.ads.R
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.data.model.HookMethodType
import com.close.hook.ads.databinding.DialogAddCustomHookBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import java.util.UUID

class CustomHookDialogFragment : DialogFragment() {

    private lateinit var onPositiveAction: (CustomHookInfo) -> Unit
    private lateinit var binding: DialogAddCustomHookBinding
    private var initialConfig: CustomHookInfo? = null

    companion object {
        const val TAG = "CustomHookDialogFragment"
        private const val ARG_CONFIG = "config"

        fun newInstance(
            config: CustomHookInfo? = null,
            onPositive: (CustomHookInfo) -> Unit
        ): CustomHookDialogFragment =
            CustomHookDialogFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_CONFIG, config)
                }
                (this as CustomHookDialogFragment).onPositiveAction = onPositive
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return null
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        binding = DialogAddCustomHookBinding.inflate(LayoutInflater.from(context))
        initialConfig = arguments?.getParcelable(ARG_CONFIG)

        val hookMethodTypeAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, HookMethodType.values().map { it.displayName })
        binding.spinnerHookMethodType.adapter = hookMethodTypeAdapter

        initialConfig?.let { config ->
            binding.etClassName.setText(config.className)
            binding.etMethodName.setText(config.methodNames?.joinToString(", "))
            binding.etReturnValue.setText(config.returnValue)
            binding.etParameterTypes.setText(config.parameterTypes?.joinToString(", "))
            binding.etFieldName.setText(config.fieldName)
            binding.etFieldValue.setText(config.fieldValue)

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

        val updateVisibility = { selectedType: HookMethodType ->
            val showMethodRelated = selectedType in listOf(HookMethodType.HOOK_MULTIPLE_METHODS, HookMethodType.FIND_AND_HOOK_METHOD, HookMethodType.HOOK_ALL_METHODS)
            val showParameterTypes = selectedType == HookMethodType.FIND_AND_HOOK_METHOD
            val showFieldRelated = selectedType == HookMethodType.SET_STATIC_OBJECT_FIELD
            val showHookPoint = selectedType in listOf(HookMethodType.FIND_AND_HOOK_METHOD, HookMethodType.HOOK_ALL_METHODS)

            binding.tilMethodName.isVisible = showMethodRelated
            binding.tilReturnValue.isVisible = showMethodRelated
            binding.tilParameterTypes.isVisible = showParameterTypes
            binding.tilFieldName.isVisible = showFieldRelated
            binding.tilFieldValue.isVisible = showFieldRelated

            binding.toggleGroupHookPoint.isVisible = showHookPoint
            binding.tvHookPointLabel.isVisible = showHookPoint
        }

        val initialHookMethodType = initialConfig?.hookMethodType ?: HookMethodType.HOOK_MULTIPLE_METHODS
        updateVisibility(initialHookMethodType)

        binding.spinnerHookMethodType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateVisibility(HookMethodType.values()[position])
                binding.tilMethodName.error = null
                binding.tilParameterTypes.error = null
                binding.tilFieldName.error = null
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }

        setupInputValidation()

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .setTitle(if (initialConfig == null) getString(R.string.add_hook_config_title) else getString(R.string.edit_hook_config_title))
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.cancel() }
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                if (validateInputs()) {
                    handlePositiveButtonClick(dialog)
                }
            }
        }
        return dialog
    }

    private fun setupInputValidation() {
        val classNameInputLayout = binding.etClassName.parent.parent as? TextInputLayout
        classNameInputLayout?.let { til ->
            binding.etClassName.addTextChangedListener(createValidationTextWatcher(til) { it.isNotBlank() })
        }
        binding.etMethodName.addTextChangedListener(createValidationTextWatcher(binding.tilMethodName) { it.isNotBlank() || !binding.tilMethodName.isVisible })
        binding.etParameterTypes.addTextChangedListener(createValidationTextWatcher(binding.tilParameterTypes) { it.isNotBlank() || !binding.tilParameterTypes.isVisible })
        binding.etFieldName.addTextChangedListener(createValidationTextWatcher(binding.tilFieldName) { it.isNotBlank() || !binding.tilFieldName.isVisible })
    }

    private fun createValidationTextWatcher(inputLayout: TextInputLayout, validation: (String) -> Boolean): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (inputLayout.isVisible) {
                    if (!validation(s.toString().trim())) {
                        inputLayout.error = context?.getString(R.string.error_field_name_empty)
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
        val className = binding.etClassName.text.toString().trim()
        val selectedHookMethodType = HookMethodType.values().firstOrNull { it.displayName == binding.spinnerHookMethodType.selectedItem?.toString()?.trim() }

        val classNameInputLayout = binding.etClassName.parent.parent as? TextInputLayout

        if (className.isBlank()) {
            classNameInputLayout?.error = getString(R.string.error_class_name_empty)
            return false
        } else {
            classNameInputLayout?.error = null
        }

        if (selectedHookMethodType == null) {
            Toast.makeText(requireContext(), getString(R.string.select_valid_hook_method), Toast.LENGTH_SHORT).show()
            return false
        }

        var isValid = true

        when (selectedHookMethodType) {
            HookMethodType.HOOK_MULTIPLE_METHODS,
            HookMethodType.HOOK_ALL_METHODS -> {
                if (binding.tilMethodName.isVisible && binding.etMethodName.text.isNullOrBlank()) {
                    binding.tilMethodName.error = getString(R.string.error_method_name_empty)
                    isValid = false
                } else {
                    binding.tilMethodName.error = null
                }
            }
            HookMethodType.FIND_AND_HOOK_METHOD -> {
                if (binding.tilMethodName.isVisible && binding.etMethodName.text.isNullOrBlank()) {
                    binding.tilMethodName.error = getString(R.string.error_method_name_empty)
                    isValid = false
                } else {
                    binding.tilMethodName.error = null
                }
                if (binding.tilParameterTypes.isVisible && binding.etParameterTypes.text.isNullOrBlank()) {
                    binding.tilParameterTypes.error = getString(R.string.error_parameter_types_empty)
                    isValid = false
                } else {
                    binding.tilParameterTypes.error = null
                }
            }
            HookMethodType.SET_STATIC_OBJECT_FIELD -> {
                if (binding.tilFieldName.isVisible && binding.etFieldName.text.isNullOrBlank()) {
                    binding.tilFieldName.error = getString(R.string.error_field_name_empty)
                    isValid = false
                } else {
                    binding.tilFieldName.error = null
                }
            }
        }

        return isValid
    }

    private fun handlePositiveButtonClick(dialog: android.app.Dialog) {
        val className = binding.etClassName.text.toString().trim()
        val selectedHookMethodTypeDisplayName = binding.spinnerHookMethodType.selectedItem?.toString()?.trim()
        val selectedHookMethodType = HookMethodType.values().firstOrNull { it.displayName == selectedHookMethodTypeDisplayName }

        val hookPoint = when (binding.toggleGroupHookPoint.checkedButtonId) {
            R.id.btn_hook_after -> "after"
            R.id.btn_hook_before -> "before"
            else -> null
        }

        val methodNames = binding.etMethodName.text.toString().trim()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
        val returnValue = binding.etReturnValue.text.toString().trim().takeIf { it.isNotBlank() }
        val parameterTypes = binding.etParameterTypes.text.toString().trim()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
        val fieldName = binding.etFieldName.text.toString().trim().takeIf { it.isNotBlank() }
        val fieldValue = binding.etFieldValue.text.toString().trim().takeIf { it.isNotBlank() }

        val newConfig = CustomHookInfo(
            id = initialConfig?.id ?: UUID.randomUUID().toString(),
            className = className,
            methodNames = methodNames,
            returnValue = returnValue,
            hookMethodType = selectedHookMethodType!!,
            parameterTypes = parameterTypes,
            fieldName = fieldName,
            fieldValue = fieldValue,
            isEnabled = initialConfig?.isEnabled ?: true,
            packageName = initialConfig?.packageName,
            hookPoint = hookPoint
        )
        onPositiveAction(newConfig)
        dialog.dismiss()
    }
}
