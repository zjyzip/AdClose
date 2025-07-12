package com.close.hook.ads.ui.adapter

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
            LayoutInflater.from(parent.context), parent, false)
        return HookViewHolder(binding, onDeleteItem, onEditItem, onLongClickItem, onClickItem, onToggleEnabled)
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

    class HookViewHolder(
        private val binding: ItemCustomHookConfigBinding,
        private val onDeleteItem: (CustomHookInfo) -> Unit,
        private val onEditItem: (CustomHookInfo) -> Unit,
        private val onLongClickItem: (CustomHookInfo) -> Unit,
        private val onClickItem: (CustomHookInfo) -> Unit,
        private val onToggleEnabled: (CustomHookInfo, Boolean) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private val highlightColor = Color.YELLOW
        private val context = binding.root.context
        private val noneString = context.getString(R.string.none)
        private val hookPointsArray = context.resources.getStringArray(R.array.hook_points_array)
        private val afterHookPoint = hookPointsArray.getOrNull(0) ?: ""
        private val beforeHookPoint = hookPointsArray.getOrNull(1) ?: ""

        private var currentIsInMultiSelectMode: Boolean = false

        init {
            binding.btnDelete.setOnClickListener { onDeleteItem(getItem(bindingAdapterPosition)) }
            binding.btnEdit.setOnClickListener { onEditItem(getItem(bindingAdapterPosition)) }
            binding.root.setOnLongClickListener {
                onLongClickItem(getItem(bindingAdapterPosition))
                true
            }
            binding.root.setOnClickListener {
                if (currentIsInMultiSelectMode) {
                    onClickItem(getItem(bindingAdapterPosition))
                } else {
                    binding.switchEnabled.performClick()
                }
            }
            binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggleEnabled(getItem(bindingAdapterPosition), isChecked)
            }
        }

        private fun getItem(position: Int): CustomHookInfo {
            return (bindingAdapter as? CustomHookAdapter)?.getItem(position)
                ?: throw IllegalStateException("Adapter not attached or is not CustomHookAdapter")
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
            this.currentIsInMultiSelectMode = isInMultiSelectMode

            binding.tvHookMethodType.text = highlightText(context.getString(R.string.hook_method_type_format, config.hookMethodType.displayName), searchQuery)

            binding.tvClassName.apply {
                visibility = if (config.hookMethodType !in listOf(HookMethodType.HOOK_METHODS_BY_STRING_MATCH, HookMethodType.FIND_METHODS_WITH_STRING)) View.VISIBLE else View.GONE
                text = highlightText(context.getString(R.string.class_name_format, config.className), searchQuery)
            }

            updateContentVisibilityAndText(config, searchQuery)
            updateSelectionState(config.isEnabled, isSelected, isInMultiSelectMode)
        }

        private fun updateContentVisibilityAndText(config: CustomHookInfo, searchQuery: String) {
            binding.tvMethodNames.visibility = View.GONE
            binding.tvReturnValue.visibility = View.GONE
            binding.tvParameterTypes.visibility = View.GONE
            binding.tvFieldName.visibility = View.GONE
            binding.tvFieldValue.visibility = View.GONE
            binding.tvHookPoint.visibility = View.GONE
            binding.tvSearchStrings.visibility = View.GONE

            getDisplayHookPoint(config.hookPoint)?.let { displayHookPoint ->
                binding.tvHookPoint.apply {
                    text = highlightText(context.getString(R.string.hook_point_format, displayHookPoint), searchQuery)
                    visibility = View.VISIBLE
                }
            }

            when (config.hookMethodType) {
                HookMethodType.HOOK_MULTIPLE_METHODS,
                HookMethodType.HOOK_ALL_METHODS -> {
                    binding.tvMethodNames.apply {
                        text = highlightText(context.getString(R.string.method_names_format, config.methodNames?.joinToString(", ") ?: noneString), searchQuery)
                        visibility = View.VISIBLE
                    }
                    binding.tvReturnValue.apply {
                        text = highlightText(context.getString(R.string.return_value_format, config.returnValue ?: noneString), searchQuery)
                        visibility = View.VISIBLE
                    }
                }
                HookMethodType.FIND_AND_HOOK_METHOD -> {
                    binding.tvMethodNames.apply {
                        text = highlightText(context.getString(R.string.method_names_format, config.methodNames?.joinToString(", ") ?: noneString), searchQuery)
                        visibility = View.VISIBLE
                    }
                    binding.tvParameterTypes.apply {
                        text = highlightText(context.getString(R.string.parameter_types_format, config.parameterTypes?.joinToString(", ") ?: noneString), searchQuery)
                        visibility = View.VISIBLE
                    }
                    binding.tvReturnValue.apply {
                        text = highlightText(context.getString(R.string.return_value_format, config.returnValue ?: noneString), searchQuery)
                        visibility = View.VISIBLE
                    }
                }
                HookMethodType.SET_STATIC_OBJECT_FIELD -> {
                    binding.tvFieldName.apply {
                        text = highlightText(context.getString(R.string.field_name_format, config.fieldName ?: noneString), searchQuery)
                        visibility = View.VISIBLE
                    }
                    binding.tvFieldValue.apply {
                        text = highlightText(context.getString(R.string.field_value_format, config.fieldValue ?: noneString), searchQuery)
                        visibility = View.VISIBLE
                    }
                }
                HookMethodType.HOOK_METHODS_BY_STRING_MATCH -> {
                    binding.tvSearchStrings.apply {
                        text = highlightText(context.getString(R.string.search_strings_format, config.searchStrings?.joinToString(", ") ?: noneString), searchQuery)
                        visibility = View.VISIBLE
                    }
                    binding.tvReturnValue.apply {
                        text = highlightText(context.getString(R.string.return_value_format, config.returnValue ?: noneString), searchQuery)
                        visibility = View.VISIBLE
                    }
                }
                HookMethodType.FIND_METHODS_WITH_STRING -> {
                    binding.tvSearchStrings.apply {
                        text = highlightText(context.getString(R.string.search_strings_format, config.searchStrings?.joinToString(", ") ?: noneString), searchQuery)
                        visibility = View.VISIBLE
                    }
                    binding.tvMethodNames.apply {
                        text = highlightText(context.getString(R.string.method_names_format, config.methodNames?.joinToString(", ") ?: noneString), searchQuery)
                        visibility = View.VISIBLE
                    }
                    binding.tvReturnValue.apply {
                        text = highlightText(context.getString(R.string.return_value_format, config.returnValue ?: noneString), searchQuery)
                        visibility = View.VISIBLE
                    }
                }
            }
        }

        fun updateSelectionState(isEnabled: Boolean, isSelected: Boolean, isInMultiSelectMode: Boolean) {
            binding.switchEnabled.visibility = if (isInMultiSelectMode) View.GONE else View.VISIBLE
            binding.root.isChecked = isSelected

            if (!isInMultiSelectMode) {
                binding.switchEnabled.isChecked = isEnabled
            }
        }

        fun updateSearchHighlight(config: CustomHookInfo, searchQuery: String) {
            if (config.hookMethodType !in listOf(HookMethodType.HOOK_METHODS_BY_STRING_MATCH, HookMethodType.FIND_METHODS_WITH_STRING)) {
                binding.tvClassName.text = highlightText(context.getString(R.string.class_name_format, config.className), searchQuery)
            } else {
                binding.tvClassName.text = ""
            }

            binding.tvHookMethodType.text = highlightText(context.getString(R.string.hook_method_type_format, config.hookMethodType.displayName), searchQuery)

            getDisplayHookPoint(config.hookPoint)?.let { displayHookPoint ->
                binding.tvHookPoint.text = highlightText(context.getString(R.string.hook_point_format, displayHookPoint), searchQuery)
            }

            when (config.hookMethodType) {
                HookMethodType.HOOK_MULTIPLE_METHODS,
                HookMethodType.HOOK_ALL_METHODS -> {
                    binding.tvMethodNames.text = highlightText(context.getString(R.string.method_names_format, config.methodNames?.joinToString(", ") ?: noneString), searchQuery)
                    binding.tvReturnValue.text = highlightText(context.getString(R.string.return_value_format, config.returnValue ?: noneString), searchQuery)
                }
                HookMethodType.FIND_AND_HOOK_METHOD -> {
                    binding.tvMethodNames.text = highlightText(context.getString(R.string.method_names_format, config.methodNames?.joinToString(", ") ?: noneString), searchQuery)
                    binding.tvParameterTypes.text = highlightText(context.getString(R.string.parameter_types_format, config.parameterTypes?.joinToString(", ") ?: noneString), searchQuery)
                    binding.tvReturnValue.text = highlightText(context.getString(R.string.return_value_format, config.returnValue ?: noneString), searchQuery)
                }
                HookMethodType.SET_STATIC_OBJECT_FIELD -> {
                    binding.tvFieldName.text = highlightText(context.getString(R.string.field_name_format, config.fieldName ?: noneString), searchQuery)
                    binding.tvFieldValue.text = highlightText(context.getString(R.string.field_value_format, config.fieldValue ?: noneString), searchQuery)
                }
                HookMethodType.HOOK_METHODS_BY_STRING_MATCH -> {
                    binding.tvSearchStrings.text = highlightText(context.getString(R.string.search_strings_format, config.searchStrings?.joinToString(", ") ?: noneString), searchQuery)
                    binding.tvReturnValue.text = highlightText(context.getString(R.string.return_value_format, config.returnValue ?: noneString), searchQuery)
                }
                HookMethodType.FIND_METHODS_WITH_STRING -> {
                    binding.tvSearchStrings.text = highlightText(context.getString(R.string.search_strings_format, config.searchStrings?.joinToString(", ") ?: noneString), searchQuery)
                    binding.tvMethodNames.text = highlightText(context.getString(R.string.method_names_format, config.methodNames?.joinToString(", ") ?: noneString), searchQuery)
                    binding.tvReturnValue.text = highlightText(context.getString(R.string.return_value_format, config.returnValue ?: noneString), searchQuery)
                }
            }
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
