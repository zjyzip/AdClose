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
    private val onItemDelete: (CustomHookInfo) -> Unit,
    private val onItemEdit: (CustomHookInfo) -> Unit,
    private val onItemLongClick: (CustomHookInfo) -> Unit,
    private val onItemClick: (CustomHookInfo) -> Unit,
    private val onToggleEnabled: (CustomHookInfo, Boolean) -> Unit
) : ListAdapter<CustomHookInfo, CustomHookAdapter.HookViewHolder>(DIFF_CALLBACK) {

    private var currentSearchQuery: String = ""
    private var currentSelectedItems: Set<CustomHookInfo> = emptySet()
    private var isInMultiSelectMode: Boolean = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HookViewHolder {
        val binding = ItemCustomHookConfigBinding.inflate(
            LayoutInflater.from(parent.context), parent, false)
        return HookViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HookViewHolder, position: Int) {
        val currentItem = getItem(position)
        holder.bind(
            currentItem,
            onItemDelete,
            onItemEdit,
            onItemLongClick,
            onItemClick,
            onToggleEnabled,
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
                notifyItemChanged(index)
            }
        }
    }

    fun setMultiSelectMode(enabled: Boolean) {
        if (isInMultiSelectMode != enabled) {
            isInMultiSelectMode = enabled
            notifyDataSetChanged()
        }
    }

    fun setSearchQuery(query: String) {
        currentSearchQuery = query
        notifyDataSetChanged()
    }

    class HookViewHolder(private val binding: ItemCustomHookConfigBinding) :
        RecyclerView.ViewHolder(binding.root) {

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
                    val backgroundColor = Color.parseColor("#FFFF00")
                    spannableString.setSpan(
                        BackgroundColorSpan(backgroundColor),
                        lastIndex,
                        lastIndex + query.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    lastIndex += query.length
                }
            }
            return spannableString
        }

        fun bind(
            config: CustomHookInfo,
            onItemDelete: (CustomHookInfo) -> Unit,
            onItemEdit: (CustomHookInfo) -> Unit,
            onItemLongClick: (CustomHookInfo) -> Unit,
            onItemClick: (CustomHookInfo) -> Unit,
            onToggleEnabled: (CustomHookInfo, Boolean) -> Unit,
            isSelected: Boolean,
            searchQuery: String,
            isInMultiSelectMode: Boolean
        ) {
            val context = binding.root.context
            val noneString = context.getString(R.string.none)

            binding.tvClassName.text = highlightText(context.getString(R.string.class_name_format, config.className), searchQuery)
            binding.tvHookMethodType.text = highlightText(context.getString(R.string.hook_method_type_format, config.hookMethodType.displayName), searchQuery)

            binding.tvMethodNames.visibility = View.GONE
            binding.tvReturnValue.visibility = View.GONE
            binding.tvParameterTypes.visibility = View.GONE
            binding.tvFieldName.visibility = View.GONE
            binding.tvFieldValue.visibility = View.GONE
            binding.tvHookPoint.visibility = View.GONE

            config.hookPoint?.takeIf { it.isNotBlank() }?.let {
                val displayHookPoint = when (it) {
                    "before" -> context.resources.getStringArray(R.array.hook_points_array)[1]
                    "after" -> context.resources.getStringArray(R.array.hook_points_array)[0]
                    else -> it
                }
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
            }

            binding.switchEnabled.visibility = if (isInMultiSelectMode) View.GONE else View.VISIBLE
            binding.switchEnabled.isChecked = config.isEnabled
            binding.switchEnabled.setOnCheckedChangeListener(null)
            if (!isInMultiSelectMode) {
                binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                    onToggleEnabled(config, isChecked)
                }
            }

            binding.root.isChecked = isSelected

            binding.btnDelete.setOnClickListener { onItemDelete(config) }
            binding.btnEdit.setOnClickListener { onItemEdit(config) }
            binding.root.setOnLongClickListener {
                onItemLongClick(config)
                true
            }
            binding.root.setOnClickListener { onItemClick(config) }
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
    }
}
