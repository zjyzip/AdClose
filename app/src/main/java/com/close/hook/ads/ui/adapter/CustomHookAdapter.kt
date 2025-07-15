package com.close.hook.ads.ui.adapter

import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
) : ListAdapter<CustomHookInfo, CustomHookAdapter.HookViewHolder>(DIFF_CALLBACK) {

    private var currentSearchQuery: String = ""
    private var currentSelectedItems: Set<CustomHookInfo> = emptySet()
    var isInMultiSelectMode: Boolean = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HookViewHolder {
        val binding = ItemCustomHookConfigBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        val context = parent.context
        val noneString = context.getString(R.string.none)
        val hookPointsArray = context.resources.getStringArray(R.array.hook_points_array)
        val highlightColor = Color.YELLOW

        return HookViewHolder(
            binding,
            onDeleteItem,
            onEditItem,
            onLongClickItem,
            onClickItem,
            onToggleEnabled,
            noneString,
            hookPointsArray,
            highlightColor,
            this
        )
    }

    override fun onBindViewHolder(holder: HookViewHolder, position: Int) {
        val currentItem = getItem(position)
        holder.bind(
            currentItem,
            currentSelectedItems.contains(currentItem),
            currentSearchQuery,
            isInMultiSelectMode
        )
    }

    override fun onBindViewHolder(holder: HookViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            val currentItem = getItem(position)
            payloads.forEach { payload ->
                when (payload) {
                    PAYLOAD_SELECTION_CHANGED, PAYLOAD_MULTI_SELECT_MODE_CHANGED -> {
                        holder.updateSelectionState(currentItem.isEnabled, currentSelectedItems.contains(currentItem), isInMultiSelectMode)
                    }
                    PAYLOAD_SEARCH_QUERY_CHANGED -> {
                        holder.updateSearchHighlight(currentItem, currentSearchQuery)
                    }
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

    class HookViewHolder(
        private val binding: ItemCustomHookConfigBinding,
        private val onDeleteItem: (CustomHookInfo) -> Unit,
        private val onEditItem: (CustomHookInfo) -> Unit,
        private val onLongClickItem: (CustomHookInfo) -> Unit,
        private val onClickItem: (CustomHookInfo) -> Unit,
        private val onToggleEnabled: (CustomHookInfo, Boolean) -> Unit,
        private val noneString: String,
        private val hookPointsArray: Array<String>,
        private val highlightColor: Int,
        private val adapter: CustomHookAdapter
    ) : RecyclerView.ViewHolder(binding.root) {

        private val context = binding.root.context
        private val afterHookPoint = hookPointsArray.getOrNull(0) ?: ""
        private val beforeHookPoint = hookPointsArray.getOrNull(1) ?: ""

        private var currentIsInMultiSelectMode: Boolean = false

        init {
            binding.btnDelete.setOnClickListener { onDeleteItem(adapter.getItem(bindingAdapterPosition)) }
            binding.btnEdit.setOnClickListener { onEditItem(adapter.getItem(bindingAdapterPosition)) }
            binding.root.setOnLongClickListener {
                onLongClickItem(adapter.getItem(bindingAdapterPosition))
                true
            }
            binding.root.setOnClickListener {
                if (currentIsInMultiSelectMode) {
                    onClickItem(adapter.getItem(bindingAdapterPosition))
                } else {
                    val config = adapter.getItem(bindingAdapterPosition)
                    onToggleEnabled(config, !config.isEnabled)
                }
            }
            binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggleEnabled(adapter.getItem(bindingAdapterPosition), isChecked)
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

        private data class DisplayContent(
            val classNameText: SpannableString,
            val classNameVisible: Boolean,
            val hookMethodTypeText: SpannableString,
            val hookPointText: SpannableString?,
            val hookPointVisible: Boolean,
            val searchStringsText: SpannableString?,
            val searchStringsVisible: Boolean,
            val methodNamesText: SpannableString?,
            val methodNamesVisible: Boolean,
            val parameterTypesText: SpannableString?,
            val parameterTypesVisible: Boolean,
            val fieldNameText: SpannableString?,
            val fieldNameVisible: Boolean,
            val fieldValueText: SpannableString?,
            val fieldValueVisible: Boolean,
            val returnValueText: SpannableString?,
            val returnValueVisible: Boolean
        )

        private fun getDisplayContent(config: CustomHookInfo, searchQuery: String): DisplayContent {
            val displayHookPoint = getDisplayHookPoint(config.hookPoint)

            return DisplayContent(
                classNameText = highlightText(context.getString(R.string.class_name_format, config.className), searchQuery),
                classNameVisible = config.hookMethodType !in listOf(HookMethodType.HOOK_METHODS_BY_STRING_MATCH, HookMethodType.FIND_METHODS_WITH_STRING),

                hookMethodTypeText = highlightText(context.getString(R.string.hook_method_type_format, config.hookMethodType.displayName), searchQuery),

                hookPointText = displayHookPoint?.let { highlightText(context.getString(R.string.hook_point_format, it), searchQuery) },
                hookPointVisible = displayHookPoint != null,

                searchStringsText = config.searchStrings?.joinToString(", ")?.let { highlightText(context.getString(R.string.search_strings_format, it), searchQuery) },
                searchStringsVisible = config.hookMethodType in listOf(HookMethodType.HOOK_METHODS_BY_STRING_MATCH, HookMethodType.FIND_METHODS_WITH_STRING),

                methodNamesText = config.methodNames?.joinToString(", ")?.let { highlightText(context.getString(R.string.method_names_format, it), searchQuery) },
                methodNamesVisible = config.hookMethodType in listOf(HookMethodType.HOOK_MULTIPLE_METHODS, HookMethodType.FIND_AND_HOOK_METHOD, HookMethodType.HOOK_ALL_METHODS, HookMethodType.FIND_METHODS_WITH_STRING),

                parameterTypesText = config.parameterTypes?.joinToString(", ")?.let { highlightText(context.getString(R.string.parameter_types_format, it), searchQuery) },
                parameterTypesVisible = config.hookMethodType == HookMethodType.FIND_AND_HOOK_METHOD,

                fieldNameText = config.fieldName?.let { highlightText(context.getString(R.string.field_name_format, it), searchQuery) },
                fieldNameVisible = config.hookMethodType == HookMethodType.SET_STATIC_OBJECT_FIELD,

                fieldValueText = config.fieldValue?.let { highlightText(context.getString(R.string.field_value_format, it), searchQuery) },
                fieldValueVisible = config.hookMethodType == HookMethodType.SET_STATIC_OBJECT_FIELD,

                returnValueText = config.returnValue?.let { highlightText(context.getString(R.string.return_value_format, it), searchQuery) },
                returnValueVisible = config.hookMethodType in listOf(HookMethodType.HOOK_MULTIPLE_METHODS, HookMethodType.FIND_AND_HOOK_METHOD, HookMethodType.HOOK_ALL_METHODS, HookMethodType.HOOK_METHODS_BY_STRING_MATCH, HookMethodType.FIND_METHODS_WITH_STRING)
            )
        }


        fun bind(
            config: CustomHookInfo,
            isSelected: Boolean,
            searchQuery: String,
            isInMultiSelectMode: Boolean
        ) {
            this.currentIsInMultiSelectMode = isInMultiSelectMode

            val content = getDisplayContent(config, searchQuery)

            binding.tvHookMethodType.text = content.hookMethodTypeText

            binding.tvClassName.apply {
                visibility = if (content.classNameVisible) View.VISIBLE else View.GONE
                text = content.classNameText
            }

            binding.tvHookPoint.apply {
                visibility = if (content.hookPointVisible) View.VISIBLE else View.GONE
                text = content.hookPointText
            }

            binding.tvSearchStrings.apply {
                visibility = if (content.searchStringsVisible) View.VISIBLE else View.GONE
                text = content.searchStringsText ?: highlightText(context.getString(R.string.search_strings_format, noneString), searchQuery)
            }

            binding.tvMethodNames.apply {
                visibility = if (content.methodNamesVisible) View.VISIBLE else View.GONE
                text = content.methodNamesText ?: highlightText(context.getString(R.string.method_names_format, noneString), searchQuery)
            }

            binding.tvParameterTypes.apply {
                visibility = if (content.parameterTypesVisible) View.VISIBLE else View.GONE
                text = content.parameterTypesText ?: highlightText(context.getString(R.string.parameter_types_format, noneString), searchQuery)
            }

            binding.tvFieldName.apply {
                visibility = if (content.fieldNameVisible) View.VISIBLE else View.GONE
                text = content.fieldNameText ?: highlightText(context.getString(R.string.field_name_format, noneString), searchQuery)
            }

            binding.tvFieldValue.apply {
                visibility = if (content.fieldValueVisible) View.VISIBLE else View.GONE
                text = content.fieldValueText ?: highlightText(context.getString(R.string.field_value_format, noneString), searchQuery)
            }

            binding.tvReturnValue.apply {
                visibility = if (content.returnValueVisible) View.VISIBLE else View.GONE
                text = content.returnValueText ?: highlightText(context.getString(R.string.return_value_format, noneString), searchQuery)
            }

            updateSelectionState(config.isEnabled, isSelected, isInMultiSelectMode)
        }

        fun updateSelectionState(isEnabled: Boolean, isSelected: Boolean, isInMultiSelectMode: Boolean) {
            binding.switchEnabled.visibility = if (isInMultiSelectMode) View.GONE else View.VISIBLE
            binding.root.isChecked = isSelected

            if (!isInMultiSelectMode) {
                binding.switchEnabled.isChecked = isEnabled
            }
        }

        fun updateSearchHighlight(config: CustomHookInfo, searchQuery: String) {
            val content = getDisplayContent(config, searchQuery)

            binding.tvHookMethodType.text = content.hookMethodTypeText

            binding.tvClassName.text = content.classNameText

            binding.tvHookPoint.text = content.hookPointText

            binding.tvSearchStrings.text = content.searchStringsText ?: highlightText(context.getString(R.string.search_strings_format, noneString), searchQuery)
            binding.tvMethodNames.text = content.methodNamesText ?: highlightText(context.getString(R.string.method_names_format, noneString), searchQuery)
            binding.tvParameterTypes.text = content.parameterTypesText ?: highlightText(context.getString(R.string.parameter_types_format, noneString), searchQuery)
            binding.tvFieldName.text = content.fieldNameText ?: highlightText(context.getString(R.string.field_name_format, noneString), searchQuery)
            binding.tvFieldValue.text = content.fieldValueText ?: highlightText(context.getString(R.string.field_value_format, noneString), searchQuery)
            binding.tvReturnValue.text = content.returnValueText ?: highlightText(context.getString(R.string.return_value_format, noneString), searchQuery)
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
