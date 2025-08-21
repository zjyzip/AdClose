package com.close.hook.ads.ui.adapter

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.data.model.ManagedItem
import com.close.hook.ads.databinding.ItemDataManagerBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DataManagerAdapter(
    private val onDeleteClick: (ManagedItem) -> Unit
) : ListAdapter<ManagedItem, DataManagerAdapter.ViewHolder>(DiffCallback) {

    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDataManagerBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemDataManagerBinding) :
        RecyclerView.ViewHolder(binding.root) {
        
        fun bind(item: ManagedItem) {
            binding.itemName.text = item.name
            binding.itemSize.text = Formatter.formatShortFileSize(binding.root.context, item.size)
            binding.itemDate.text = dateFormat.format(Date(item.lastModified))
            binding.deleteButton.setOnClickListener { onDeleteClick(item) }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<ManagedItem>() {
            override fun areItemsTheSame(oldItem: ManagedItem, newItem: ManagedItem): Boolean {
                return oldItem.name == newItem.name && oldItem.type == newItem.type
            }
            
            override fun areContentsTheSame(oldItem: ManagedItem, newItem: ManagedItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
