package com.close.hook.ads.ui.adapter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.R
import com.close.hook.ads.data.model.Item
import com.close.hook.ads.databinding.ItemBlockListBinding

class BlockListAdapter(private val context: Context,
                       private val onRemoveUrl: (Int) -> Unit,
                       private val onEditUrl: (Int) -> Unit) :
    ListAdapter<Item, BlockListAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBlockListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, context, onRemoveUrl, onEditUrl)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemBlockListBinding,
        private val context: Context,
        private val onRemoveUrl: (Int) -> Unit,
        private val onEditUrl: (Int) -> Unit
    ) : RecyclerView.ViewHolder(binding.root), PopupMenu.OnMenuItemClickListener {

        init {
            itemView.setOnLongClickListener {
                showPopupMenu()
                true
            }
        }

        private fun showPopupMenu() {
            PopupMenu(context, itemView).apply {
                menuInflater.inflate(R.menu.menu_request, menu)
                menu.findItem(R.id.block).title = "移除黑名单"
                setOnMenuItemClickListener(this@ViewHolder)
                show()
            }
        }

        fun bind(item: Item) {
            with(binding) {
                url.text = item.url
                type.text = item.type.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        }

        override fun onMenuItemClick(menuItem: MenuItem?): Boolean {
            when (menuItem?.itemId) {
                R.id.copy -> copyToClipboard()
                R.id.block -> onRemoveUrl(adapterPosition)
                R.id.edit -> onEditUrl(adapterPosition)
            }
            return true
        }

        private fun copyToClipboard() {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = ClipData.newPlainText("request", binding.url.text)
            clipboardManager.setPrimaryClip(clipData)
            Toast.makeText(context, "已复制: ${binding.url.text}", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(oldItem: Item, newItem: Item): Boolean =
                oldItem.url == newItem.url && oldItem.type == newItem.type

            override fun areContentsTheSame(oldItem: Item, newItem: Item): Boolean =
                oldItem == newItem
        }
    }
}
