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

class BlockListAdapter(private val context: Context) :
    ListAdapter<Item, BlockListAdapter.ViewHolder>(diffCallback),
    PopupMenu.OnMenuItemClickListener {

    private var callBack: CallBack? = null
    fun setCallBack(callBack: CallBack) {
        this.callBack = callBack
    }

    private var position = -1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val viewHolder = ViewHolder.from(parent)
        viewHolder.itemView.setOnLongClickListener {
            position = viewHolder.bindingAdapterPosition
            val popup = PopupMenu(parent.context, it)
            val inflater = popup.menuInflater
            inflater.inflate(R.menu.menu_request, popup.menu)
            popup.menu.findItem(R.id.block).title = "移除黑名单"
            popup.setOnMenuItemClickListener(this@BlockListAdapter)
            popup.show()
            true
        }
        return viewHolder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(currentList[position])
    }

    class ViewHolder(private val binding: ItemBlockListBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Item) {
            binding.url.text = item.url
        }

        companion object {
            fun from(parent: ViewGroup): ViewHolder {
                val inflater = LayoutInflater.from(parent.context)
                val binding = ItemBlockListBinding.inflate(inflater, parent, false)
                return ViewHolder(binding)
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.copy -> {
                val clipboardManager =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                ClipData.newPlainText("request", currentList[position].url)
                    ?.let { clipboardManager.setPrimaryClip(it) }
                Toast.makeText(context, "已复制: ${currentList[position].url}", Toast.LENGTH_SHORT)
                    .show()
            }

            R.id.block -> {
                callBack?.onRemoveUrl(position)
            }

            R.id.edit -> {
                callBack?.onEditUrl(position)
            }
        }
        return false
    }

    interface CallBack {
        fun onRemoveUrl(position: Int)
        fun onEditUrl(position: Int)
    }

    companion object {
        val diffCallback = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(oldItem: Item, newItem: Item): Boolean {
                return oldItem == newItem
            }

            override fun areContentsTheSame(oldItem: Item, newItem: Item): Boolean {
                return oldItem == newItem
            }
        }
    }

}
