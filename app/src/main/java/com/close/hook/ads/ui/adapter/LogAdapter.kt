package com.close.hook.ads.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.data.model.LogEntry
import com.close.hook.ads.databinding.ItemLogBinding
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId

class LogAdapter(
    private val onItemClick: (LogEntry) -> Unit,
    private val onItemLongClick: (LogEntry) -> Unit
) : ListAdapter<LogEntry, LogAdapter.LogViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class LogViewHolder(private val binding: ItemLogBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            itemView.setOnClickListener {
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                    ?.let { onItemClick(getItem(it)) }
            }
            itemView.setOnLongClickListener {
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                    ?.let { onItemLongClick(getItem(it)) }
                true
            }
        }

        fun bind(logEntry: LogEntry) {
            binding.logTimestamp.text = dateFormatter
                .format(Instant.ofEpochMilli(logEntry.timestamp).atZone(ZoneId.systemDefault()))
            binding.logTag.text = logEntry.tag
            binding.logMessage.text = logEntry.message
        }
    }

    companion object {
        private val dateFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<LogEntry>() {
            override fun areItemsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean =
                oldItem == newItem
        }
    }
}
