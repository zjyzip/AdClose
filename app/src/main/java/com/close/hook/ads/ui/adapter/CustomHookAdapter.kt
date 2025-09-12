package com.close.hook.ads.ui.adapter

import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.R
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.data.model.HookField
import com.close.hook.ads.databinding.ItemCustomHookConfigBinding

class CustomHookAdapter(
    private val onDeleteItem: (CustomHookInfo) -> Unit,
    private val onEditItem: (CustomHookInfo) -> Unit,
    private val onLongClickItem: (CustomHookInfo) -> Unit,
    private val onClickItem: (CustomHookInfo) -> Unit,
    private val onToggleEnabled: (CustomHookInfo, Boolean) -> Unit
) : ListAdapter<CustomHookInfo, CustomHookAdapter.ViewHolder>(DIFF_CALLBACK) {

    var tracker: SelectionTracker<CustomHookInfo>? = null
    private var currentSearchQuery: String = ""

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCustomHookConfigBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val currentItem = getItem(position)
        val isSelected = tracker?.isSelected(currentItem) ?: false
        holder.bind(
            config = currentItem,
            isSelected = isSelected,
            searchQuery = currentSearchQuery,
            isInMultiSelectMode = tracker?.hasSelection() ?: false
        )
    }

    fun setSearchQuery(query: String) {
        if (currentSearchQuery != query) {
            currentSearchQuery = query
            notifyItemRangeChanged(0, itemCount)
        }
    }

    inner class ViewHolder(
        private val binding: ItemCustomHookConfigBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val context: Context = binding.root.context
        private val noneString: String = context.getString(R.string.none)
        private val afterHookPoint: String = context.resources.getStringArray(R.array.hook_points_array)[0]
        private val beforeHookPoint: String = context.resources.getStringArray(R.array.hook_points_array)[1]
        private val highlightColor: Int = Color.YELLOW

        private val fieldViewMap: Map<HookField, Pair<TextView, Int>> = mapOf(
            HookField.CLASS_NAME to (binding.tvClassName to R.string.class_name_format),
            HookField.METHOD_NAME to (binding.tvMethodNames to R.string.method_names_format),
            HookField.PARAMETER_TYPES to (binding.tvParameterTypes to R.string.parameter_types_format),
            HookField.RETURN_VALUE to (binding.tvReturnValue to R.string.return_value_format),
            HookField.FIELD_NAME to (binding.tvFieldName to R.string.field_name_format),
            HookField.FIELD_VALUE to (binding.tvFieldValue to R.string.field_value_format),
            HookField.SEARCH_STRINGS to (binding.tvSearchStrings to R.string.search_strings_format),
            HookField.HOOK_POINT to (binding.tvHookPoint to R.string.hook_point_format),
            HookField.PARAMETER_REPLACEMENTS to (binding.tvParameterReplacements to R.string.parameter_replacements_format)
        )

        init {
            binding.btnDelete.setOnClickListener {
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) onDeleteItem(getItem(bindingAdapterPosition))
            }
            binding.btnEdit.setOnClickListener {
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) onEditItem(getItem(bindingAdapterPosition))
            }
            binding.root.setOnLongClickListener {
                onLongClickItem(getItem(bindingAdapterPosition))
                true
            }
            binding.root.setOnClickListener {
                onClickItem(getItem(bindingAdapterPosition))
            }
            binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) onToggleEnabled(getItem(bindingAdapterPosition), isChecked)
            }
        }
        
        fun bind(config: CustomHookInfo, isSelected: Boolean, searchQuery: String, isInMultiSelectMode: Boolean) {
            updateContent(config, searchQuery)
            updateSelectionState(config.isEnabled, isSelected, isInMultiSelectMode)
        }

        private fun updateContent(config: CustomHookInfo, searchQuery: String) {
            binding.tvHookMethodType.text = highlightText(
                context.getString(R.string.hook_method_type_format, config.hookMethodType.displayName),
                searchQuery
            )
            
            val visibleFields = config.hookMethodType.visibleFields

            fieldViewMap.forEach { (field, viewPair) ->
                val (textView, formatResId) = viewPair
                if (field in visibleFields) {
                    val content = getContentForField(config, field)
                    textView.text = highlightText(context.getString(formatResId, content), searchQuery)
                    textView.visibility = View.VISIBLE
                } else {
                    textView.visibility = View.GONE
                }
            }
        }

        private fun getContentForField(config: CustomHookInfo, field: HookField): String {
            return when (field) {
                HookField.CLASS_NAME -> config.className
                HookField.METHOD_NAME -> config.methodNames?.joinToString(", ") ?: noneString
                HookField.PARAMETER_TYPES -> config.parameterTypes?.joinToString(", ") ?: noneString
                HookField.RETURN_VALUE -> config.returnValue ?: noneString
                HookField.FIELD_NAME -> config.fieldName ?: noneString
                HookField.FIELD_VALUE -> config.fieldValue ?: noneString
                HookField.SEARCH_STRINGS -> config.searchStrings?.joinToString(", ") ?: noneString
                HookField.HOOK_POINT -> when (config.hookPoint) {
                    "before" -> beforeHookPoint
                    "after" -> afterHookPoint
                    else -> config.hookPoint ?: noneString
                }
                HookField.PARAMETER_REPLACEMENTS ->
                    config.parameterReplacements?.entries?.joinToString(", ") { "arg[${it.key}]=${it.value}" } ?: noneString
            }
        }
        
        private fun updateSelectionState(isEnabled: Boolean, isSelected: Boolean, isInMultiSelectMode: Boolean) {
            binding.root.isChecked = isSelected
            binding.switchEnabled.visibility = if (isInMultiSelectMode) View.GONE else View.VISIBLE
            if (!isInMultiSelectMode) {
                binding.switchEnabled.isChecked = isEnabled
            }
        }

        private fun highlightText(fullText: String, query: String): SpannableString {
            if (query.isBlank()) return SpannableString(fullText)
            
            val spannableString = SpannableString(fullText)
            val lowerCaseFullText = fullText.lowercase()
            val lowerCaseQuery = query.lowercase()

            var lastIndex = lowerCaseFullText.indexOf(lowerCaseQuery)
            while (lastIndex != -1) {
                spannableString.setSpan(
                    BackgroundColorSpan(highlightColor),
                    lastIndex,
                    lastIndex + query.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                lastIndex = lowerCaseFullText.indexOf(lowerCaseQuery, lastIndex + query.length)
            }
            return spannableString
        }

        fun getItemDetails(): ItemDetailsLookup.ItemDetails<CustomHookInfo> =
            object : ItemDetailsLookup.ItemDetails<CustomHookInfo>() {
                override fun getPosition(): Int = bindingAdapterPosition
                override fun getSelectionKey(): CustomHookInfo? = getItem(bindingAdapterPosition)
            }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<CustomHookInfo>() {
            override fun areItemsTheSame(oldItem: CustomHookInfo, newItem: CustomHookInfo): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: CustomHookInfo, newItem: CustomHookInfo): Boolean =
                oldItem == newItem
        }
    }
}
