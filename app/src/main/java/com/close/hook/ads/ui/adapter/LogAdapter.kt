package com.close.hook.ads.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.data.model.LogEntry
import com.close.hook.ads.databinding.ItemLogBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogAdapter(
    private val onItemClick: (LogEntry) -> Unit
) : ListAdapter<LogEntry, LogAdapter.LogViewHolder>(DIFF_CALLBACK) {

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

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
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(bindingAdapterPosition))
                }
            }
        }

        fun bind(logEntry: LogEntry) {
            binding.logTimestamp.text = dateFormat.format(Date(logEntry.timestamp))
            binding.logTag.text = logEntry.tag
            binding.logMessage.text = logEntry.message
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<LogEntry>() {
            override fun areItemsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean =
                oldItem == newItem
        }
    }
}
