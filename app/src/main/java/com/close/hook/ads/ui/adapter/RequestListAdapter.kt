package com.close.hook.ads.ui.adapter

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.R
import com.close.hook.ads.data.model.RequestInfo
import com.close.hook.ads.data.model.detailArguments
import com.close.hook.ads.data.RequestDetailUpdates
import com.close.hook.ads.data.RequestListReducer
import com.close.hook.ads.databinding.ItemRequestBinding
import com.close.hook.ads.ui.activity.RequestInfoActivity
import com.close.hook.ads.util.AppIconLoader
import com.close.hook.ads.util.showFeedback
import com.close.hook.ads.util.resolveColorAttr
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class RequestListAdapter(
    private val onToggleBlock: (RequestInfo) -> Unit,
    private val lifecycleOwner: LifecycleOwner
) : ListAdapter<RequestInfo, RequestListAdapter.ViewHolder>(DIFF_CALLBACK) {

    var tracker: SelectionTracker<RequestInfo>? = null

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == COUNT_UPDATE }) holder.bindCount(getItem(position))
        else super.onBindViewHolder(holder, position, payloads)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, tracker?.isSelected(item) ?: false)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.itemView.tag = null
    }

    inner class ViewHolder(
        private val binding: ItemRequestBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val targetIconSizePx by lazy { AppIconLoader.calculateTargetIconSizePx(binding.root.context) }
        private val defaultTextColor: Int
        private val errorTextColor: Int

        init {
            defaultTextColor = binding.request.currentTextColor
            errorTextColor = binding.root.context.resolveColorAttr(android.R.attr.colorError)
            setupListeners()
        }

        fun getItemDetails(): ItemDetailsLookup.ItemDetails<RequestInfo> =
            object : ItemDetailsLookup.ItemDetails<RequestInfo>() {
                override fun getPosition(): Int = bindingAdapterPosition
                override fun getSelectionKey(): RequestInfo? {
                    val position = bindingAdapterPosition
                    return if (position == RecyclerView.NO_POSITION) null else getItem(position)
                }
            }

        @SuppressLint("SetTextI18n")
        fun bind(request: RequestInfo, isSelected: Boolean) = with(binding) {
            root.tag = request
            cardView.isChecked = isSelected

            appName.text = request.appName
            this.request.text = request.request
            bindCount(request)

            blockType.text = request.blockType.takeUnless { it.isNullOrEmpty() } ?: run {
                if (RequestListReducer.isDns(request)) "Domain" else "URL"
            }
            blockType.visibility = if (request.blockType.isNullOrEmpty()) View.GONE else View.VISIBLE

            updateBlockStatusUI(request)
            loadAppIcon(request.packageName)
        }

        fun bindCount(request: RequestInfo) {
            binding.root.tag = request
            binding.timestamp.text = DATE_FORMATTER.format(Instant.ofEpochMilli(request.lastTimestamp).atZone(ZONE_ID))
            binding.occurrenceCount.isVisible = request.occurrenceCount > 1
            binding.occurrenceCount.text = binding.root.context.getString(R.string.request_occurrence_count, request.occurrenceCount)
            binding.occurrenceCount.contentDescription = binding.root.context.getString(
                R.string.request_occurrence_description, request.occurrenceCount)
        }

        private fun setupListeners() {
            binding.apply {
                cardView.setOnClickListener {
                    val currentTracker = tracker
                    if (currentTracker == null || !currentTracker.hasSelection()) {
                        (root.tag as? RequestInfo)?.let { openRequestInfoActivity(it) }
                    }
                }
                copy.setOnClickListener {
                    (root.tag as? RequestInfo)?.request?.let { copyToClipboard(it) }
                }
                block.setOnClickListener {
                    (root.tag as? RequestInfo)?.let { onToggleBlock(it) }
                }
            }
        }

        private fun loadAppIcon(packageName: String) {
            lifecycleOwner.lifecycleScope.launch {
                val iconDrawable = AppIconLoader.loadAndCompressIcon(
                    binding.root.context, packageName, targetIconSizePx
                )
                if ((binding.root.tag as? RequestInfo)?.packageName == packageName) {
                    binding.icon.setImageDrawable(iconDrawable)
                }
            }
        }

        private fun openRequestInfoActivity(request: RequestInfo) {
            Intent(itemView.context, RequestInfoActivity::class.java).apply {
                putExtras(RequestDetailUpdates.current(request).detailArguments())
            }.also { itemView.context.startActivity(it) }
        }

        private fun copyToClipboard(text: String) {
            val context = itemView.context
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.setPrimaryClip(ClipData.newPlainText("request", text))
            itemView.showFeedback(context.getString(R.string.copied_to_clipboard))
        }

        private fun updateBlockStatusUI(request: RequestInfo) {
            val context = itemView.context
            val isBlocked = request.isBlocked == true

            binding.block.text = if (isBlocked) {
                context.getString(R.string.remove_from_blocklist)
            } else {
                context.getString(R.string.add_to_blocklist)
            }

            val textColor = if (isBlocked) errorTextColor else defaultTextColor
            binding.request.setTextColor(textColor)
        }
    }

    companion object {
        private const val COUNT_UPDATE = "dns_count_update"
        private val ZONE_ID: ZoneId = ZoneId.systemDefault()

        private val DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<RequestInfo>() {
            override fun areItemsTheSame(oldItem: RequestInfo, newItem: RequestInfo): Boolean =
                oldItem.requestId.isNotEmpty() && oldItem.requestId == newItem.requestId &&
                    oldItem.packageName == newItem.packageName

            override fun areContentsTheSame(oldItem: RequestInfo, newItem: RequestInfo): Boolean =
                oldItem == newItem

            override fun getChangePayload(oldItem: RequestInfo, newItem: RequestInfo): Any? =
                if (RequestListReducer.isDns(oldItem) && oldItem.copy(
                    occurrenceCount = newItem.occurrenceCount, lastTimestamp = newItem.lastTimestamp,
                    fullAddress = newItem.fullAddress, stack = newItem.stack, lastRequestId = newItem.lastRequestId
                ) == newItem) COUNT_UPDATE else null
        }
    }
}
