package com.close.hook.ads.ui.adapter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.databinding.ItemBlockListBinding
import com.close.hook.ads.util.dp

class BlockListAdapter(
    private val context: Context,
    private val onRemoveUrl: (Url) -> Unit,
    private val onEditUrl: (Url) -> Unit
) :
    ListAdapter<Url, BlockListAdapter.ViewHolder>(DIFF_CALLBACK) {

    var tracker: SelectionTracker<Url>? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding =
            ItemBlockListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, context, onRemoveUrl, onEditUrl)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        tracker?.let {
            holder.bind(getItem(position), it.isSelected(getItem(position)))
        }
    }

    inner class ViewHolder(
        private val binding: ItemBlockListBinding,
        private val context: Context,
        private val onRemoveUrl: (Url) -> Unit,
        private val onEditUrl: (Url) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun getItemDetails(): ItemDetailsLookup.ItemDetails<Url> =
            object : ItemDetailsLookup.ItemDetails<Url>() {
                override fun getPosition(): Int = bindingAdapterPosition
                override fun getSelectionKey(): Url = getItem(bindingAdapterPosition)
            }

        init {
            binding.edit.setOnClickListener {
                onEditUrl(currentList[bindingAdapterPosition])
            }
            binding.delete.setOnClickListener {
                onRemoveUrl(currentList[bindingAdapterPosition])
            }
            binding.cardView.setOnClickListener {
                copyToClipboard(binding.type.text.toString(), binding.url.text.toString())
            }
        }

        fun bind(item: Url, isSelected: Boolean) {
            with(binding) {
                url.text = item.url
                type.text =
                    item.type.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                cardView.isChecked = isSelected
                container.setPadding(16.dp, 12.dp, if (isSelected) 35.dp else 16.dp, 12.dp)
            }
        }

        private fun copyToClipboard(type: String, url: String) {
            val clipboardManager =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = ClipData.newPlainText(type, url)
            clipboardManager.setPrimaryClip(clipData)
            Toast.makeText(context, "已复制: $url", Toast.LENGTH_SHORT).show()
        }

    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Url>() {
            override fun areItemsTheSame(oldItem: Url, newItem: Url): Boolean =
                oldItem.url == newItem.url && oldItem.type == newItem.type

            override fun areContentsTheSame(oldItem: Url, newItem: Url): Boolean =
                oldItem == newItem
        }
    }
}
