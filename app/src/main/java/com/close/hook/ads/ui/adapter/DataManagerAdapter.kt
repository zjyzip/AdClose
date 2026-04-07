package com.close.hook.ads.ui.adapter

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.data.model.ManagedItem
import com.close.hook.ads.databinding.ItemDataManagerBinding
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DataManagerAdapter(
    private val onExportClick: (ManagedItem) -> Unit,
    private val onDeleteClick: (ManagedItem) -> Unit,
    private val onItemClick: (ManagedItem) -> Unit
) : ListAdapter<ManagedItem, DataManagerAdapter.ViewHolder>(DiffCallback) {

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

        init {
            binding.exportButton.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onExportClick(getItem(pos))
            }
            binding.deleteButton.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onDeleteClick(getItem(pos))
            }
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onItemClick(getItem(pos))
            }
        }

        fun bind(item: ManagedItem) {
            binding.itemName.text = item.name
            binding.itemSize.text = Formatter.formatShortFileSize(binding.root.context, item.size)
            binding.itemDate.text = dateFormatter.format(
                Instant.ofEpochMilli(item.lastModified).atZone(ZoneId.systemDefault())
            )
        }
    }

    companion object {
        private val dateFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        private val DiffCallback = object : DiffUtil.ItemCallback<ManagedItem>() {
            override fun areItemsTheSame(oldItem: ManagedItem, newItem: ManagedItem): Boolean =
                oldItem.name == newItem.name && oldItem.type == newItem.type

            override fun areContentsTheSame(oldItem: ManagedItem, newItem: ManagedItem): Boolean =
                oldItem == newItem
        }
    }
}
