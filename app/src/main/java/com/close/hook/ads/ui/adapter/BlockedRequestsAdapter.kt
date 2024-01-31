package com.close.hook.ads.ui.adapter

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.ThemeUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.R
import com.close.hook.ads.data.database.UrlDatabase
import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.util.AppUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

class BlockedRequestsAdapter(
    private val context: Context
) : ListAdapter<BlockedRequest, BlockedRequestsAdapter.ViewHolder>(DIFF_CALLBACK),
    PopupMenu.OnMenuItemClickListener {

    private var url: String? = null
    private var host: String? = null
    private var isExist: Boolean? = null
    private lateinit var block: MenuItem
    private val urlDao by lazy {
        UrlDatabase.getDatabase(context).urlDao
    }

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

        private val DIFF_CALLBACK: DiffUtil.ItemCallback<BlockedRequest> =
            object : DiffUtil.ItemCallback<BlockedRequest>() {
                override fun areItemsTheSame(
                    oldItem: BlockedRequest,
                    newItem: BlockedRequest
                ): Boolean {
                    return oldItem.hashCode() == newItem.hashCode()
                }

                @SuppressLint("DiffUtilEquals")
                override fun areContentsTheSame(
                    oldItem: BlockedRequest,
                    newItem: BlockedRequest
                ): Boolean {
                    return oldItem.hashCode() == newItem.hashCode()
                }
            }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.blocked_request_item, parent, false)
        return ViewHolder(view).apply {
            itemView.setOnClickListener {
                if (!urlString.isNullOrEmpty())
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
            itemView.setOnLongClickListener {
                url = request.text.toString()
                host = url.toString()
                    .replace("https://", "")
                    .replace("http://", "")
                if (host.toString().contains('/')) {
                    host = host.toString().substring(0, host.toString().indexOf('/'))
                }
                val popup = PopupMenu(parent.context, it)
                val inflater = popup.menuInflater
                inflater.inflate(R.menu.menu_request, popup.menu)
                block = popup.menu.findItem(R.id.block)
                block.title =
                    if (urlDao.isExist(host.toString())) {
                        isExist = true
                        "移除黑名单"
                    } else {
                        isExist = false
                        "加入黑名单"
                    }
                popup.setOnMenuItemClickListener(this@BlockedRequestsAdapter)
                popup.show()
                true
            }
        }
    }

    @SuppressLint("RestrictedApi")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val request = getItem(position)
        with(holder) {
            appName.text = if (request.urlString.isNullOrEmpty()) request.appName
            else "${request.appName} LOG"
            this.request.text = request.request
            timestamp.text = DATE_FORMAT.format(Date(request.timestamp))
            icon.setImageDrawable(AppUtils.getAppIcon(request.packageName))

            val textColor = if (request.requestType == "all" && request.isBlocked == true) {
                ThemeUtils.getThemeAttrColor(context, com.google.android.material.R.attr.colorError)
            } else {
                ThemeUtils.getThemeAttrColor(
                    context,
                    com.google.android.material.R.attr.colorControlNormal
                )
            }
            this.request.setTextColor(textColor)
            method = request.method
            urlString = request.urlString
            requestHeaders = request.requestHeaders
            responseCode = request.responseCode
            responseMessage = request.responseMessage
            responseHeaders = request.responseHeaders
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val appName: TextView = view.findViewById(R.id.app_name)
        val request: TextView = view.findViewById(R.id.request)
        val timestamp: TextView = view.findViewById(R.id.timestamp)
        val icon: ImageView = view.findViewById(R.id.icon)
        var method: String? = null
        var urlString: String? = null
        var requestHeaders: String? = null
        var responseCode = -1
        var responseMessage: String? = null
        var responseHeaders: String? = null
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {

            R.id.copy -> {
                val clipboardManager =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                ClipData.newPlainText("request", url)
                    ?.let { clipboardManager.setPrimaryClip(it) }
                Toast.makeText(context, "已复制: $url", Toast.LENGTH_SHORT)
                    .show()
            }

            R.id.block -> {
                CoroutineScope(Dispatchers.IO).launch {
                    if (isExist == true) {
                        urlDao.delete(host.toString())
                    } else {
                        urlDao.insert(
                            Url(
                                System.currentTimeMillis(),
                                host.toString()
                            )
                        )
                    }
                }
            }

        }
        return true
    }
}
