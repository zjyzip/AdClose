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
import com.close.hook.ads.R
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.databinding.ItemBlockListBinding
import com.close.hook.ads.util.dp

class BlockListAdapter(
    private val context: Context,
    private val onRemoveUrl: (Url) -> Unit,
    private val onEditUrl: (Url) -> Unit
) : ListAdapter<Url, BlockListAdapter.ViewHolder>(DIFF_CALLBACK) {

    var tracker: SelectionTracker<Url>? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding =
            ItemBlockListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, onRemoveUrl, onEditUrl)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        tracker?.let {
            holder.bind(item, it.isSelected(item))
        }
    }

    inner class ViewHolder(
        private val binding: ItemBlockListBinding,
        private val onRemoveUrl: (Url) -> Unit,
        private val onEditUrl: (Url) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun getItemDetails(): ItemDetailsLookup.ItemDetails<Url> =
            object : ItemDetailsLookup.ItemDetails<Url>() {
                override fun getPosition(): Int = bindingAdapterPosition
                override fun getSelectionKey(): Url? = getItem(bindingAdapterPosition)
            }

        init {
            setupListeners()
        }

        private fun setupListeners() {
            binding.apply {
                edit.setOnClickListener {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        val item = getItem(position)
                        onEditUrl(item)
                    }
                }
                delete.setOnClickListener {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        val item = getItem(position)
                        onRemoveUrl(item)
                    }
                }
                cardView.setOnClickListener {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        val item = getItem(position)
                        copyToClipboard(item.type, item.url)
                    }
                }
            }
        }

        fun bind(item: Url, isSelected: Boolean) {
            with(binding) {
                url.text = item.url
                type.text = item.type.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                cardView.isChecked = isSelected
                container.setPadding(16.dp, 12.dp, if (isSelected) 35.dp else 16.dp, 12.dp)
            }
        }

        private fun copyToClipboard(type: String, url: String) {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = ClipData.newPlainText(type, url)
            clipboardManager.setPrimaryClip(clipData)
            Toast.makeText(context, context.getString(R.string.copied_to_clipboard_single, url), Toast.LENGTH_SHORT).show()
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
