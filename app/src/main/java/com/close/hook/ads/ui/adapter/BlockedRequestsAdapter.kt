package com.close.hook.ads.ui.adapter

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.R
import com.close.hook.ads.data.DataSource
import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.databinding.ItemBlockedRequestBinding
import com.close.hook.ads.ui.activity.RequestInfoActivity
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

class BlockedRequestsAdapter(
    private val dataSource: DataSource,
    private val onGetAppIcon: suspend (String) -> Drawable?
) : ListAdapter<BlockedRequest, BlockedRequestsAdapter.ViewHolder>(DIFF_CALLBACK) {

    var tracker: SelectionTracker<BlockedRequest>? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemBlockedRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false), tracker)

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, tracker?.isSelected(item) ?: false)
    }

    inner class ViewHolder(
        private val binding: ItemBlockedRequestBinding,
        private val tracker: SelectionTracker<BlockedRequest>?
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            setupListeners()
        }

        fun getItemDetails(): ItemDetailsLookup.ItemDetails<BlockedRequest> =
            object : ItemDetailsLookup.ItemDetails<BlockedRequest>() {
                override fun getPosition(): Int = bindingAdapterPosition
                override fun getSelectionKey(): BlockedRequest? = getItem(bindingAdapterPosition)
            }

        @SuppressLint("SetTextI18n")
        fun bind(request: BlockedRequest, isSelected: Boolean) = with(binding) {
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
                    if (tracker == null || !tracker.hasSelection()) {
                        (root.tag as? BlockedRequest)?.let { openRequestInfoActivity(it) }
                    }
                }
                copy.setOnClickListener {
                    (root.tag as? BlockedRequest)?.request?.let { copyToClipboard(it) }
                }
                block.setOnClickListener {
                    (root.tag as? BlockedRequest)?.let { toggleBlockStatus(it) }
                }
            }
        }

        private fun loadAppIcon(packageName: String) {
            CoroutineScope(Dispatchers.Main).launch {
                val iconDrawable = withContext(Dispatchers.IO) {
                    onGetAppIcon(packageName)
                }
                if ((binding.root.tag as? BlockedRequest)?.packageName == packageName) {
                    binding.icon.setImageDrawable(iconDrawable)
                }
            }
        }

        private fun openRequestInfoActivity(request: BlockedRequest) {
            Intent(itemView.context, RequestInfoActivity::class.java).apply {
                putExtra("method", request.method)
                putExtra("urlString", request.urlString)
                putExtra("requestHeaders", request.requestHeaders)
                putExtra("responseCode", request.responseCode)
                putExtra("responseMessage", request.responseMessage)
                putExtra("responseHeaders", request.responseHeaders)
                putExtra("responseBody", request.responseBody)
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

        private fun toggleBlockStatus(request: BlockedRequest) {
            CoroutineScope(Dispatchers.IO).launch {
                val requestType = request.blockType.takeUnless { it.isNullOrEmpty() } ?: run {
                    if (request.appName.trim().endsWith("DNS", ignoreCase = true)) "Domain" else "URL"
                }
                val urlToToggle = request.url ?: request.request.orEmpty()

                val newIsBlocked = if (request.isBlocked == true) {
                    dataSource.removeUrlString(requestType, urlToToggle)
                    false
                } else {
                    dataSource.addUrl(Url(requestType, urlToToggle))
                    true
                }

                withContext(Dispatchers.Main) {
                    val currentListCopy = currentList.toMutableList()
                    val index = currentListCopy.indexOfFirst { it.timestamp == request.timestamp }
                    if (index != -1) {
                        currentListCopy[index] = request.copy(isBlocked = newIsBlocked)
                        submitList(currentListCopy)
                    }
                }
            }
        }

        private fun updateBlockStatusUI(request: BlockedRequest) {
            val context = itemView.context
            val isBlocked = request.isBlocked == true

            binding.block.text = if (isBlocked) {
                context.getString(R.string.remove_from_blocklist)
            } else {
                context.getString(R.string.add_to_blocklist)
            }

            val textColor = if (isBlocked) {
                MaterialColors.getColor(context, com.google.android.material.R.attr.colorError, 0)
            } else {
                MaterialColors.getColor(context, com.google.android.material.R.attr.colorControlNormal, 0)
            }
            binding.request.setTextColor(textColor)
        }
    }

    companion object {
        @SuppressLint("SimpleDateFormat")
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<BlockedRequest>() {
            override fun areItemsTheSame(oldItem: BlockedRequest, newItem: BlockedRequest): Boolean =
                oldItem.timestamp == newItem.timestamp

            override fun areContentsTheSame(oldItem: BlockedRequest, newItem: BlockedRequest): Boolean =
                oldItem == newItem
        }
    }
}
