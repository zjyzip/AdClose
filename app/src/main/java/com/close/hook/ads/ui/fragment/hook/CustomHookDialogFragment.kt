package com.close.hook.ads.ui.fragment.hook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import com.close.hook.ads.R
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.data.model.HookMethodType
import com.close.hook.ads.databinding.DialogAddCustomHookBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class CustomHookDialogFragment : DialogFragment() {

    private var initialConfig: CustomHookInfo? = null
    private lateinit var onPositiveAction: (CustomHookInfo) -> Unit

    companion object {
        const val TAG = "CustomHookDialogFragment"
        private const val ARG_CONFIG = "config"

        fun newInstance(
            config: CustomHookInfo? = null,
            onPositive: (CustomHookInfo) -> Unit
        ): CustomHookDialogFragment =
            CustomHookDialogFragment().apply {
                initialConfig = config
                arguments = Bundle().apply {
                    putParcelable(ARG_CONFIG, config)
                }
                onPositiveAction = onPositive
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialConfig = arguments?.getParcelable(ARG_CONFIG)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return null
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val binding = DialogAddCustomHookBinding.inflate(LayoutInflater.from(context))

        val hookMethodTypeAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, HookMethodType.values().map { it.displayName })
        binding.spinnerHookMethodType.setAdapter(hookMethodTypeAdapter)

        val hookPointsDisplay = resources.getStringArray(R.array.hook_points_array).toList()
        val hookPointAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, hookPointsDisplay)
        binding.spinnerHookPoint.setAdapter(hookPointAdapter)

        initialConfig?.let { config ->
            binding.etClassName.setText(config.className)
            binding.etMethodName.setText(config.methodNames?.joinToString(", "))
            binding.etReturnValue.setText(config.returnValue)
            binding.etParameterTypes.setText(config.parameterTypes?.joinToString(", "))
            binding.etFieldName.setText(config.fieldName)
            binding.etFieldValue.setText(config.fieldValue)
            binding.spinnerHookMethodType.setText(config.hookMethodType.displayName, false)

            val selectedHookPointIndex = when (config.hookPoint) {
                "before" -> hookPointsDisplay.indexOf(resources.getStringArray(R.array.hook_points_array)[1])
                "after" -> hookPointsDisplay.indexOf(resources.getStringArray(R.array.hook_points_array)[0])
                else -> 0
            }
            if (selectedHookPointIndex != -1) {
                binding.spinnerHookPoint.setText(hookPointsDisplay[selectedHookPointIndex], false)
            } else {
                binding.spinnerHookPoint.setText(hookPointsDisplay[0], false)
            }
        } ?: run {
            binding.spinnerHookMethodType.setText(HookMethodType.HOOK_MULTIPLE_METHODS.displayName, false)
            binding.spinnerHookPoint.setText(hookPointsDisplay[0], false)
        }

        val updateVisibility = { selectedType: HookMethodType ->
            binding.tilHookPoint.isVisible = selectedType != HookMethodType.HOOK_MULTIPLE_METHODS
            binding.tilMethodName.isVisible = selectedType in listOf(HookMethodType.HOOK_MULTIPLE_METHODS, HookMethodType.FIND_AND_HOOK_METHOD, HookMethodType.HOOK_ALL_METHODS)
            binding.tilReturnValue.isVisible = selectedType in listOf(HookMethodType.HOOK_MULTIPLE_METHODS, HookMethodType.FIND_AND_HOOK_METHOD, HookMethodType.HOOK_ALL_METHODS)
            binding.tilParameterTypes.isVisible = selectedType == HookMethodType.FIND_AND_HOOK_METHOD
            binding.tilFieldName.isVisible = selectedType == HookMethodType.SET_STATIC_OBJECT_FIELD
            binding.tilFieldValue.isVisible = selectedType == HookMethodType.SET_STATIC_OBJECT_FIELD
        }

        val initialHookMethodType = initialConfig?.hookMethodType ?: HookMethodType.HOOK_MULTIPLE_METHODS
        updateVisibility(initialHookMethodType)

        binding.spinnerHookMethodType.setOnItemClickListener { _, _, position, _ ->
            updateVisibility(HookMethodType.values()[position])
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .setTitle(if (initialConfig == null) getString(R.string.add_hook_config_title) else getString(R.string.edit_hook_config_title))
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.cancel() }
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                handlePositiveButtonClick(binding, dialog)
            }
        }

        return dialog
    }

    private fun handlePositiveButtonClick(binding: DialogAddCustomHookBinding, dialog: android.app.Dialog) {
        val className = binding.etClassName.text.toString().trim()
        val selectedHookMethodTypeDisplayName = binding.spinnerHookMethodType.text.toString().trim()
        val selectedHookMethodType = HookMethodType.values().firstOrNull { it.displayName == selectedHookMethodTypeDisplayName }

        if (selectedHookMethodType == null) {
            Toast.makeText(requireContext(), getString(R.string.select_valid_hook_method), Toast.LENGTH_SHORT).show()
            return
        }

        val selectedHookPointText = binding.spinnerHookPoint.text.toString().trim()
        val hookPointsArray = resources.getStringArray(R.array.hook_points_array)
        val hookPoint = when {
            selectedHookPointText.contains(hookPointsArray[0]) -> "after"
            selectedHookPointText.contains(hookPointsArray[1]) -> "before"
            else -> "after"
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

        var errorMessage: String? = null

        if (className.isBlank()) {
            errorMessage = getString(R.string.error_class_name_empty)
        } else {
            when (selectedHookMethodType) {
                HookMethodType.HOOK_MULTIPLE_METHODS -> {
                    if (methodNames.isNullOrEmpty()) {
                        errorMessage = getString(R.string.error_method_name_empty)
                    }
                }
                HookMethodType.HOOK_ALL_METHODS,
                HookMethodType.FIND_AND_HOOK_METHOD -> {
                    if (methodNames.isNullOrEmpty()) {
                        errorMessage = getString(R.string.error_method_name_empty)
                    }
                    if (selectedHookMethodType == HookMethodType.FIND_AND_HOOK_METHOD && parameterTypes.isNullOrEmpty()) {
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
            return
        } else {
            val newConfig = CustomHookInfo(
                id = initialConfig?.id ?: java.util.UUID.randomUUID().toString(),
                className = className,
                methodNames = methodNames,
                returnValue = returnValue,
                hookMethodType = selectedHookMethodType,
                parameterTypes = parameterTypes,
                fieldName = fieldName,
                fieldValue = fieldValue,
                isEnabled = initialConfig?.isEnabled ?: false,
                packageName = initialConfig?.packageName,
                hookPoint = hookPoint
            )
            onPositiveAction(newConfig)
            dialog.dismiss()
        }
    }
}
