package com.close.hook.ads.ui.adapter

import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.R
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.data.model.HookMethodType
import com.close.hook.ads.databinding.ItemCustomHookConfigBinding

class CustomHookAdapter(
    private val onDeleteItem: (CustomHookInfo) -> Unit,
    private val onEditItem: (CustomHookInfo) -> Unit,
    private val onLongClickItem: (CustomHookInfo) -> Unit,
    private val onClickItem: (CustomHookInfo) -> Unit,
    private val onToggleEnabled: (CustomHookInfo, Boolean) -> Unit
) : ListAdapter<CustomHookInfo, CustomHookAdapter.ViewHolder>(DIFF_CALLBACK) {

    private var currentSearchQuery: String = ""
    private var currentSelectedItems: Set<CustomHookInfo> = emptySet()
    var isInMultiSelectMode: Boolean = false
    var tracker: SelectionTracker<CustomHookInfo>? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCustomHookConfigBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val currentItem = getItem(position)
        holder.bind(
            config = currentItem,
            isSelected = tracker?.isSelected(currentItem) ?: false,
            searchQuery = currentSearchQuery,
            isInMultiSelectMode = isInMultiSelectMode
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }

        val currentItem = getItem(position)
        payloads.forEach { payload ->
            when (payload) {
                PAYLOAD_SELECTION_CHANGED, PAYLOAD_MULTI_SELECT_MODE_CHANGED -> {
                    holder.updateSelectionState(
                        isEnabled = currentItem.isEnabled,
                        isSelected = tracker?.isSelected(currentItem) ?: false,
                        isInMultiSelectMode = isInMultiSelectMode
                    )
                }
                PAYLOAD_SEARCH_QUERY_CHANGED -> {
                    holder.updateContent(currentItem, currentSearchQuery, true)
                }
            }
        }
    }

    fun updateSelection(selectedItems: Set<CustomHookInfo>) {
        val oldSelectedItems = currentSelectedItems
        currentSelectedItems = selectedItems

        val added = currentSelectedItems.minus(oldSelectedItems)
        val removed = oldSelectedItems.minus(currentSelectedItems)

        (added + removed).forEach { config ->
            val index = currentList.indexOfFirst { it.id == config.id }
            if (index != -1) {
                notifyItemChanged(index, PAYLOAD_SELECTION_CHANGED)
            }
        }
    }

    fun setMultiSelectMode(enabled: Boolean) {
        if (isInMultiSelectMode != enabled) {
            isInMultiSelectMode = enabled
            notifyItemRangeChanged(0, itemCount, PAYLOAD_MULTI_SELECT_MODE_CHANGED)
        }
    }

    fun setSearchQuery(query: String) {
        if (currentSearchQuery != query) {
            currentSearchQuery = query
            notifyItemRangeChanged(0, itemCount, PAYLOAD_SEARCH_QUERY_CHANGED)
        }
    }

    inner class ViewHolder(
        private val binding: ItemCustomHookConfigBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val context: Context = binding.root.context
        private val noneString: String = context.getString(R.string.none)
        private val afterHookPoint: String = context.resources.getStringArray(R.array.hook_points_array).getOrNull(0) ?: ""
        private val beforeHookPoint: String = context.resources.getStringArray(R.array.hook_points_array).getOrNull(1) ?: ""
        private val highlightColor: Int = Color.YELLOW

        init {
            binding.btnDelete.setOnClickListener {
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) onDeleteItem(getItem(bindingAdapterPosition))
            }
            binding.btnEdit.setOnClickListener {
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) onEditItem(getItem(bindingAdapterPosition))
            }
            binding.root.setOnLongClickListener {
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) onLongClickItem(getItem(bindingAdapterPosition))
                true
            }
            binding.root.setOnClickListener {
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) onClickItem(getItem(bindingAdapterPosition))
            }
            binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) onToggleEnabled(getItem(bindingAdapterPosition), isChecked)
            }
        }

        private fun highlightText(fullText: String, query: String): SpannableString {
            if (query.isBlank()) {
                return SpannableString(fullText)
            }
            val spannableString = SpannableString(fullText)
            val lowerCaseFullText = fullText.lowercase()
            val lowerCaseQuery = query.lowercase()

            var lastIndex = 0
            while (lastIndex != -1) {
                lastIndex = lowerCaseFullText.indexOf(lowerCaseQuery, lastIndex)
                if (lastIndex != -1) {
                    spannableString.setSpan(
                        BackgroundColorSpan(highlightColor),
                        lastIndex,
                        lastIndex + query.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    lastIndex += query.length
                }
            }
            return spannableString
        }

        private fun getDisplayHookPoint(hookPoint: String?): String? {
            return hookPoint?.takeIf { it.isNotBlank() }?.let {
                when (it) {
                    "before" -> beforeHookPoint
                    "after" -> afterHookPoint
                    else -> it
                }
            }
        }

        fun bind(
            config: CustomHookInfo,
            isSelected: Boolean,
            searchQuery: String,
            isInMultiSelectMode: Boolean
        ) {
            updateContent(config, searchQuery, false)
            updateSelectionState(config.isEnabled, isSelected, isInMultiSelectMode)
        }

        fun updateContent(config: CustomHookInfo, searchQuery: String, onlyUpdateHighlight: Boolean) {
            val displayHookPoint = getDisplayHookPoint(config.hookPoint)

            fun setTextAndVisibility(textView: View, isVisible: Boolean, content: String?) {
                if (!onlyUpdateHighlight) {
                    textView.visibility = if (isVisible) View.VISIBLE else View.GONE
                }
                if (content != null) {
                    (textView as? android.widget.TextView)?.text = highlightText(content, searchQuery)
                }
            }

            binding.tvHookMethodType.text = highlightText(context.getString(R.string.hook_method_type_format, config.hookMethodType.displayName), searchQuery)

            setTextAndVisibility(
                binding.tvClassName,
                config.hookMethodType !in listOf(HookMethodType.HOOK_METHODS_BY_STRING_MATCH, HookMethodType.FIND_METHODS_WITH_STRING),
                context.getString(R.string.class_name_format, config.className)
            )

            setTextAndVisibility(
                binding.tvHookPoint,
                displayHookPoint != null,
                displayHookPoint?.let { context.getString(R.string.hook_point_format, it) }
            )

            val searchStringsText = config.searchStrings?.joinToString(", ")?.let { context.getString(R.string.search_strings_format, it) } ?: context.getString(R.string.search_strings_format, noneString)
            setTextAndVisibility(
                binding.tvSearchStrings,
                config.hookMethodType in listOf(HookMethodType.HOOK_METHODS_BY_STRING_MATCH, HookMethodType.FIND_METHODS_WITH_STRING),
                searchStringsText
            )

            val methodNamesText = config.methodNames?.joinToString(", ")?.let { context.getString(R.string.method_names_format, it) } ?: context.getString(R.string.method_names_format, noneString)
            setTextAndVisibility(
                binding.tvMethodNames,
                config.hookMethodType in listOf(HookMethodType.HOOK_MULTIPLE_METHODS, HookMethodType.FIND_AND_HOOK_METHOD, HookMethodType.HOOK_ALL_METHODS, HookMethodType.FIND_METHODS_WITH_STRING),
                methodNamesText
            )

            val parameterTypesText = config.parameterTypes?.joinToString(", ")?.let { context.getString(R.string.parameter_types_format, it) } ?: context.getString(R.string.parameter_types_format, noneString)
            setTextAndVisibility(
                binding.tvParameterTypes,
                config.hookMethodType == HookMethodType.FIND_AND_HOOK_METHOD,
                parameterTypesText
            )

            val fieldNameText = config.fieldName?.let { context.getString(R.string.field_name_format, it) } ?: context.getString(R.string.field_name_format, noneString)
            setTextAndVisibility(
                binding.tvFieldName,
                config.hookMethodType == HookMethodType.SET_STATIC_OBJECT_FIELD,
                fieldNameText
            )

            val fieldValueText = config.fieldValue?.let { context.getString(R.string.field_value_format, it) } ?: context.getString(R.string.field_value_format, noneString)
            setTextAndVisibility(
                binding.tvFieldValue,
                config.hookMethodType == HookMethodType.SET_STATIC_OBJECT_FIELD,
                fieldValueText
            )

            val returnValueText = config.returnValue?.let { context.getString(R.string.return_value_format, it) } ?: context.getString(R.string.return_value_format, noneString)
            setTextAndVisibility(
                binding.tvReturnValue,
                config.hookMethodType in listOf(HookMethodType.HOOK_MULTIPLE_METHODS, HookMethodType.FIND_AND_HOOK_METHOD, HookMethodType.HOOK_ALL_METHODS, HookMethodType.HOOK_METHODS_BY_STRING_MATCH, HookMethodType.FIND_METHODS_WITH_STRING),
                returnValueText
            )
        }

        fun updateSelectionState(isEnabled: Boolean, isSelected: Boolean, isInMultiSelectMode: Boolean) {
            binding.switchEnabled.visibility = if (isInMultiSelectMode) View.GONE else View.VISIBLE
            binding.root.isChecked = isSelected
            if (!isInMultiSelectMode) {
                binding.switchEnabled.isChecked = isEnabled
            }
        }

        fun getItemDetails(): ItemDetailsLookup.ItemDetails<CustomHookInfo> =
            object : ItemDetailsLookup.ItemDetails<CustomHookInfo>() {
                override fun getPosition(): Int = bindingAdapterPosition
                override fun getSelectionKey(): CustomHookInfo? = getItem(bindingAdapterPosition)
            }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<CustomHookInfo>() {
            override fun areItemsTheSame(oldItem: CustomHookInfo, newItem: CustomHookInfo): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: CustomHookInfo, newItem: CustomHookInfo): Boolean {
                return oldItem == newItem
            }
        }

        const val PAYLOAD_SELECTION_CHANGED = "selection_changed"
        const val PAYLOAD_MULTI_SELECT_MODE_CHANGED = "multi_select_mode_changed"
        const val PAYLOAD_SEARCH_QUERY_CHANGED = "search_query_changed"
    }
}

