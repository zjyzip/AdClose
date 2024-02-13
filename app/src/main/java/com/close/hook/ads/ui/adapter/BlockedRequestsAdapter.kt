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
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

class BlockedRequestsAdapter(
    private val context: Context
) : ListAdapter<BlockedRequest, BlockedRequestsAdapter.ViewHolder>(DIFF_CALLBACK),
    PopupMenu.OnMenuItemClickListener {

    private var url: String? = null
    private var isExist: Boolean? = null
    private val urlDao by lazy {
        UrlDatabase.getDatabase(context).urlDao
    }

    companion object {
        @SuppressLint("SimpleDateFormat")
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    
        private val DIFF_CALLBACK: DiffUtil.ItemCallback<BlockedRequest> = object : DiffUtil.ItemCallback<BlockedRequest>() {
            override fun areItemsTheSame(oldItem: BlockedRequest, newItem: BlockedRequest): Boolean {
                return oldItem.timestamp == newItem.timestamp
            }
    
            override fun areContentsTheSame(oldItem: BlockedRequest, newItem: BlockedRequest): Boolean {
                return oldItem == newItem
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_blocked_request, parent, false)
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
            itemView.setOnLongClickListener { view ->
                url = request.text.toString()
                checkUrlExistAndShowMenu(url, view, parent.context)
                true
            }
        }
    }

    private fun checkUrlExistAndShowMenu(url: String?, view: View, context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val urlExists = url?.let { urlDao.isExist(it) } ?: false
            withContext(Dispatchers.Main) {
                PopupMenu(context, view).apply {
                    menuInflater.inflate(R.menu.menu_request, menu)
                    menu.findItem(R.id.edit).isVisible = false
                    menu.findItem(R.id.block).title = if (urlExists) {
                        "移除黑名单"
                    } else {
                        "加入黑名单"
                    }
                    setOnMenuItemClickListener(this@BlockedRequestsAdapter)
                    show()
                }
            }
        }
    }

    @SuppressLint("RestrictedApi")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val request = getItem(position)
        holder.apply {
            appName.text = "${request.appName} ${if (request.urlString.isNullOrEmpty()) "" else " LOG"}"
            this.request.text = request.request
            timestamp.text = DATE_FORMAT.format(Date(request.timestamp))
            icon.setImageDrawable(AppUtils.getAppIcon(request.packageName))
            blockType.visibility = if (request.blockType.isNullOrEmpty()) View.GONE else View.VISIBLE
            blockType.text = request.blockType
    
            val textColor = ThemeUtils.getThemeAttrColor(itemView.context, if (request.requestType == "block" || request.isBlocked == true) {
                com.google.android.material.R.attr.colorError
            } else {
                com.google.android.material.R.attr.colorControlNormal
            })
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
        val blockType: TextView = view.findViewById(R.id.blockType)
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
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).apply {
                    setPrimaryClip(ClipData.newPlainText("request", url))
                }
                Toast.makeText(context, "已复制: $url", Toast.LENGTH_SHORT).show()
            }
            R.id.block -> {
                CoroutineScope(Dispatchers.IO).launch {
                    if (isExist == true) {
                        urlDao.delete(url.toString())
                    } else {
                        urlDao.insert(Url("url", url.toString()))
                    }
                }
            }
        }
        return true
    }

}
