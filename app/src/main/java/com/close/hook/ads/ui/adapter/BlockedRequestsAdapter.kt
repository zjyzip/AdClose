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
import androidx.appcompat.widget.ThemeUtils
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.data.database.UrlDatabase
import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.databinding.ItemBlockedRequestBinding
import com.close.hook.ads.util.AppUtils
import com.close.hook.ads.ui.activity.RequestInfoActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

class BlockedRequestsAdapter(
    private val context: Context,
    private val addUrl: (Pair<String, String>) -> Unit,
    private val removeUrl: (String) -> Unit
) : ListAdapter<BlockedRequest, BlockedRequestsAdapter.ViewHolder>(DIFF_CALLBACK) {

    private val urlDao by lazy {
        UrlDatabase.getDatabase(context).urlDao
    }
    var tracker: SelectionTracker<BlockedRequest>? = null

    companion object {
        @SuppressLint("SimpleDateFormat")
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

        private val DIFF_CALLBACK: DiffUtil.ItemCallback<BlockedRequest> =
            object : DiffUtil.ItemCallback<BlockedRequest>() {
                override fun areItemsTheSame(
                    oldItem: BlockedRequest,
                    newItem: BlockedRequest
                ): Boolean =
                    oldItem.timestamp == newItem.timestamp

                @SuppressLint("DiffUtilEquals")
                override fun areContentsTheSame(
                    oldItem: BlockedRequest,
                    newItem: BlockedRequest
                ): Boolean =
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
        getItem(position)?.let { holder.bind(it) }
    }

    inner class ViewHolder(private val binding: ItemBlockedRequestBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var currentRequest: BlockedRequest? = null

        fun getItemDetails(): ItemDetailsLookup.ItemDetails<BlockedRequest> =
            object : ItemDetailsLookup.ItemDetails<BlockedRequest>() {
                override fun getPosition(): Int = absoluteAdapterPosition
                override fun getSelectionKey(): BlockedRequest? = getItem(absoluteAdapterPosition)
            }

        init {
            binding.apply {
                cardView.setOnClickListener {
                    currentRequest?.takeUnless { it.urlString.isNullOrEmpty() }?.let { request ->
                        val intent = Intent(context, RequestInfoActivity::class.java).apply {
                            putExtra("method", request.method)
                            putExtra("urlString", request.urlString)
                            putExtra("requestHeaders", request.requestHeaders)
                            putExtra("responseCode", request.responseCode)
                            putExtra("responseMessage", request.responseMessage)
                            putExtra("responseHeaders", request.responseHeaders)
                            putExtra("stack", request.stack)
                        }
                        context.startActivity(intent)
                    }
                }
                copy.setOnClickListener {
                    currentRequest?.request?.let { text ->
                        copyToClipboard(text)
                    }
                }
                block.setOnClickListener {
                    currentRequest?.let { request ->
                        toggleBlockStatus(request)
                    }
                }
            }
        }

        @SuppressLint("SetTextI18n", "RestrictedApi")
        fun bind(request: BlockedRequest) = with(binding) {
            currentRequest = request

            appName.text =
                "${request.appName} ${if (request.urlString.isNullOrEmpty()) "" else " LOG"}"
            this.request.text = request.request
            timestamp.text = DATE_FORMAT.format(Date(request.timestamp))
            icon.setImageDrawable(AppUtils.getAppIcon(request.packageName))
            blockType.visibility =
                if (request.blockType.isNullOrEmpty()) View.GONE else View.VISIBLE
            blockType.text = request.blockType

            val textColor = ThemeUtils.getThemeAttrColor(
                itemView.context, if (request.requestType == "block" || request.isBlocked == true) {
                    com.google.android.material.R.attr.colorError
                } else {
                    com.google.android.material.R.attr.colorControlNormal
                }
            )
            this.request.setTextColor(textColor)

            tracker?.let {
                cardView.isChecked = it.isSelected(request)
            }

            checkBlockStatus(request)
        }

        private fun copyToClipboard(text: String) {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                ClipData.newPlainText("request", text)
            )
            Toast.makeText(context, "已复制: $text", Toast.LENGTH_SHORT).show()
        }

        private fun toggleBlockStatus(request: BlockedRequest) =
            CoroutineScope(Dispatchers.IO).launch {
                val isExist = urlDao.isExist(request.request)
                if (isExist) {
                    removeUrl(request.request)
                    request.isBlocked = false
                } else {
                    addUrl(Pair(request.request, request.appName))
                    request.isBlocked = true
                }
                withContext(Dispatchers.Main) {
                    checkBlockStatus(request)
                }
            }

        private fun checkBlockStatus(request: BlockedRequest) {
            binding.block.text = if (request.isBlocked ?: false) "移除黑名单" else "加入黑名单"
        }
    }

}
