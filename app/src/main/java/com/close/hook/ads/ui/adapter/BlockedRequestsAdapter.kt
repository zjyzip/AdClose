package com.close.hook.ads.ui.adapter

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.ThemeUtils
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.R
import com.close.hook.ads.data.database.UrlDatabase
import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.util.AppUtils
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

class BlockedRequestsAdapter(
    private val context: Context
) : ListAdapter<BlockedRequest, BlockedRequestsAdapter.ViewHolder>(DIFF_CALLBACK) {

    private val urlDao by lazy {
        UrlDatabase.getDatabase(context).urlDao
    }
    var tracker: SelectionTracker<String>? = null

    companion object {
        @SuppressLint("SimpleDateFormat")
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

        private val DIFF_CALLBACK: DiffUtil.ItemCallback<BlockedRequest> =
            object : DiffUtil.ItemCallback<BlockedRequest>() {
                override fun areItemsTheSame(
                    oldItem: BlockedRequest,
                    newItem: BlockedRequest
                ): Boolean {
                    return oldItem.timestamp == newItem.timestamp
                }

                @SuppressLint("DiffUtilEquals")
                override fun areContentsTheSame(
                    oldItem: BlockedRequest,
                    newItem: BlockedRequest
                ): Boolean {
                    return oldItem == newItem
                }
            }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_blocked_request, parent, false)
        return ViewHolder(view).apply {
            itemView.setOnClickListener {
                urlString.takeUnless { it.isNullOrEmpty() }?.let {
                    MaterialAlertDialogBuilder(parent.context).apply {
                        setTitle("请求参数")
                        setMessage(
                            """
method: $method
urlString: $urlString
requestHeaders: $requestHeaders
responseCode: $responseCode
responseMessage: $responseMessage
responseHeaders: $responseHeaders
                """.trimIndent()
                        )
                        setPositiveButton("关闭", null)
                        show()
                    }
                }
            }
            copy.setOnClickListener {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).apply {
                    setPrimaryClip(ClipData.newPlainText("request", request.text.toString()))
                }
                Toast.makeText(context, "已复制: ${request.text}", Toast.LENGTH_SHORT).show()
            }

            block.setOnClickListener {
                val isExist = block.text != "加入黑名单"
                block.text = if (isExist) "加入黑名单"
                else "移除黑名单"
                CoroutineScope(Dispatchers.IO).launch {
                    val url = request.text.toString()
                    if (isExist) {
                        if (urlDao.isExist(url))
                            urlDao.delete(url)
                    } else {
                        if (!urlDao.isExist(url))
                            urlDao.insert(Url("url", url))
                    }
                }
            }
        }
    }

    @SuppressLint("RestrictedApi")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val request = getItem(position)
        holder.apply {
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
                cardView.isChecked = it.isSelected(getItem(position).request)
            }

            method = request.method
            urlString = request.urlString
            requestHeaders = request.requestHeaders
            responseCode = request.responseCode
            responseMessage = request.responseMessage
            responseHeaders = request.responseHeaders

            CoroutineScope(Dispatchers.IO).launch {
                val isExist = request.request?.let { urlDao.isExist(it) } == true
                withContext(Dispatchers.Main) {
                    block.text = if (isExist) {
                        "移除黑名单"
                    } else {
                        "加入黑名单"
                    }
                }
            }
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val appName: TextView = view.findViewById(R.id.app_name)
        val request: TextView = view.findViewById(R.id.request)
        val timestamp: TextView = view.findViewById(R.id.timestamp)
        val icon: ImageView = view.findViewById(R.id.icon)
        val blockType: TextView = view.findViewById(R.id.blockType)
        var method: String? = null
        var urlString: String? = null
        var requestHeaders: String? = null
        var responseCode = -1
        var responseMessage: String? = null
        var responseHeaders: String? = null
        val check: ImageView = view.findViewById(R.id.check)
        val copy: TextView = view.findViewById(R.id.copy)
        val block: TextView = view.findViewById(R.id.block)
        val cardView: MaterialCardView = view.findViewById(R.id.cardView)

        fun getItemDetails(): ItemDetailsLookup.ItemDetails<String> =
            object : ItemDetailsLookup.ItemDetails<String>() {
                override fun getPosition(): Int = absoluteAdapterPosition
                override fun getSelectionKey(): String? = getItem(absoluteAdapterPosition).request
            }

    }

}
