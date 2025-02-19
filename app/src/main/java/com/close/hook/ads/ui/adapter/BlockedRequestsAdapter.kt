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
import androidx.appcompat.widget.ThemeUtils
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aitsuki.swipe.SwipeLayout
import com.close.hook.ads.R
import com.close.hook.ads.data.DataSource
import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.databinding.ItemBlockedRequestBinding
import com.close.hook.ads.ui.activity.RequestInfoActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

class BlockedRequestsAdapter(
    private val dataSource: DataSource,
    private val onGetAppIcon: (String) -> Drawable?
) : ListAdapter<BlockedRequest, BlockedRequestsAdapter.ViewHolder>(DIFF_CALLBACK) {

    var tracker: SelectionTracker<BlockedRequest>? = null

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ItemBlockedRequestBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        item?.let { holder.bind(it) }
    }

    inner class ViewHolder(private val binding: ItemBlockedRequestBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var currentRequest: BlockedRequest? = null

        fun getItemDetails(): ItemDetailsLookup.ItemDetails<BlockedRequest> =
            object : ItemDetailsLookup.ItemDetails<BlockedRequest>() {
                override fun getPosition(): Int = bindingAdapterPosition
                override fun getSelectionKey(): BlockedRequest? = getItem(bindingAdapterPosition)
            }

        init {
            setupListeners()
        }

        @SuppressLint("SetTextI18n", "RestrictedApi")
        fun bind(request: BlockedRequest) = with(binding) {
            currentRequest = request

            val type = request.blockType ?: if (request.appName.trim().endsWith("DNS")) "Domain" else "URL"
            val url = request.url ?: request.request
            appName.text = "${request.appName} ${if (request.stack.isNullOrEmpty()) "" else " LOG"}"
            this.request.text = request.request
            timestamp.text = DATE_FORMAT.format(Date(request.timestamp))

            onGetAppIcon(request.packageName)?.let { icon.setImageDrawable(it) }

            blockType.visibility = if (request.blockType.isNullOrEmpty()) View.GONE else View.VISIBLE
            blockType.text = request.blockType

            updateBlockStatusUI(request.isBlocked)

            tracker?.let { cardView.isChecked = it.isSelected(request) }
        }

        private fun setupListeners() {
            binding.apply {
                cardView.setOnClickListener {
                    currentRequest?.let { openRequestInfoActivity(it) }
                }
                copy.setOnClickListener {
                    currentRequest?.request?.let { copyToClipboard(it) }
                }
                block.setOnClickListener {
                    currentRequest?.let { toggleBlockStatus(it) }
                }
            }
        }

        private fun openRequestInfoActivity(request: BlockedRequest) {
            val context = itemView.context
            Intent(context, RequestInfoActivity::class.java).apply {
                putExtra("method", request.method)
                putExtra("urlString", request.urlString)
                putExtra("requestHeaders", request.requestHeaders)
                putExtra("responseCode", request.responseCode)
                putExtra("responseMessage", request.responseMessage)
                putExtra("responseHeaders", request.responseHeaders)
                putExtra("stack", request.stack)
                putExtra("dnsHost", request.dnsHost)
                putExtra("dnsCidr", request.dnsCidr)
                putExtra("fullAddress", request.fullAddress)
            }.also {
                context.startActivity(it)
            }
        }

        private fun copyToClipboard(text: String) {
            val context = itemView.context
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("request", text))
            Toast.makeText(context, context.getString(R.string.copied_to_clipboard_single, text), Toast.LENGTH_SHORT).show()
        }

        private fun toggleBlockStatus(request: BlockedRequest) {
            CoroutineScope(Dispatchers.IO).launch {
                val type = request.blockType ?: "URL"
                val url = request.url ?: request.request
                if (request.isBlocked == true) {
                    dataSource.removeUrlString(type, url)
                    request.isBlocked = false
                } else {
                    dataSource.addUrl(Url(type, url))
                    request.isBlocked = true
                }
                withContext(Dispatchers.Main) {
                    updateBlockStatusUI(request.isBlocked)
                }
            }
        }

        @SuppressLint("RestrictedApi")
        private fun updateBlockStatusUI(isBlocked: Boolean?) {
            val context = itemView.context
            binding.block.text = if (isBlocked == true) {
                context.getString(R.string.remove_from_blocklist)
            } else {
                context.getString(R.string.add_to_blocklist)
            }

            val colorAttr = if (isBlocked == true) {
                com.google.android.material.R.attr.colorError
            } else {
                com.google.android.material.R.attr.colorControlNormal
            }
            binding.request.setTextColor(ThemeUtils.getThemeAttrColor(context, colorAttr))
        }
    }
}
