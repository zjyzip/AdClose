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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.R
import com.close.hook.ads.data.model.RequestInfo
import com.close.hook.ads.databinding.ItemRequestBinding
import com.close.hook.ads.ui.activity.RequestInfoActivity
import com.close.hook.ads.util.AppIconLoader
import com.close.hook.ads.util.resolveColorAttr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

class RequestListAdapter(
    private val onToggleBlock: (RequestInfo) -> Unit
) : ListAdapter<RequestInfo, RequestListAdapter.ViewHolder>(DIFF_CALLBACK) {

    var tracker: SelectionTracker<RequestInfo>? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, tracker?.isSelected(item) ?: false)
    }

    inner class ViewHolder(
        private val binding: ItemRequestBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val targetIconSizePx by lazy { AppIconLoader.calculateTargetIconSizePx(binding.root.context) }

        init {
            setupListeners()
        }

        fun getItemDetails(): ItemDetailsLookup.ItemDetails<RequestInfo> =
            object : ItemDetailsLookup.ItemDetails<RequestInfo>() {
                override fun getPosition(): Int = bindingAdapterPosition
                override fun getSelectionKey(): RequestInfo? = getItem(bindingAdapterPosition)
            }

        @SuppressLint("SetTextI1n")
        fun bind(request: RequestInfo, isSelected: Boolean) = with(binding) {
            root.tag = request
            cardView.isChecked = isSelected

            appName.text = request.appName + if (request.stack.isNullOrEmpty()) "" else " LOG"
            this.request.text = request.request
            timestamp.text = DATE_FORMAT.format(Date(request.timestamp))

            blockType.text = request.blockType.takeUnless { it.isNullOrEmpty() } ?: run {
                if (request.appName.trim().endsWith("DNS", ignoreCase = true)) "Domain" else "URL"
            }
            blockType.visibility = if (request.blockType.isNullOrEmpty()) View.GONE else View.VISIBLE

            updateBlockStatusUI(request)
            loadAppIcon(request.packageName)
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
            (binding.root.context as? LifecycleOwner)?.lifecycleScope?.launch {
                val iconDrawable = AppIconLoader.loadAndCompressIcon(binding.root.context, packageName, targetIconSizePx)
                if ((binding.root.tag as? RequestInfo)?.packageName == packageName) {
                    withContext(Dispatchers.Main) {
                        binding.icon.setImageDrawable(iconDrawable)
                    }
                }
            }
        }

        private fun openRequestInfoActivity(request: RequestInfo) {
            Intent(itemView.context, RequestInfoActivity::class.java).apply {
                putExtra("method", request.method)
                putExtra("urlString", request.urlString)
                putExtra("requestHeaders", request.requestHeaders)
                putExtra("requestBodyUriString", request.requestBodyUriString)
                putExtra("responseCode", request.responseCode.toString())
                putExtra("responseMessage", request.responseMessage)
                putExtra("responseHeaders", request.responseHeaders)
                putExtra("responseBodyUriString", request.responseBodyUriString)
                putExtra("stack", request.stack)
                putExtra("dnsHost", request.dnsHost)
                putExtra("fullAddress", request.fullAddress)
            }.also { itemView.context.startActivity(it) }
        }

        private fun copyToClipboard(text: String) {
            val context = itemView.context
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("request", text))
            Toast.makeText(context, context.getString(R.string.copied_to_clipboard_single, text), Toast.LENGTH_SHORT).show()
        }

        private fun updateBlockStatusUI(request: RequestInfo) {
            val context = itemView.context
            val isBlocked = request.isBlocked == true

            binding.block.text = if (isBlocked) {
                context.getString(R.string.remove_from_blocklist)
            } else {
                context.getString(R.string.add_to_blocklist)
            }

            val textColor = if (isBlocked) {
                context.resolveColorAttr(android.R.attr.colorError)
            } else {
                context.resolveColorAttr(android.R.attr.colorControlNormal)
            }
            binding.request.setTextColor(textColor)
        }
    }

    companion object {
        @SuppressLint("SimpleDateFormat")
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<RequestInfo>() {
            override fun areItemsTheSame(oldItem: RequestInfo, newItem: RequestInfo): Boolean =
                oldItem.timestamp == newItem.timestamp

            override fun areContentsTheSame(oldItem: RequestInfo, newItem: RequestInfo): Boolean =
                oldItem == newItem
        }
    }
}
