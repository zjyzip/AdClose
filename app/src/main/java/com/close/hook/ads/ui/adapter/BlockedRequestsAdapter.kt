package com.close.hook.ads.ui.adapter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.R
import com.close.hook.ads.data.module.BlockedRequest
import java.text.SimpleDateFormat
import java.util.Date


class BlockedRequestsAdapter(
    private val context: Context,
    private val blockedRequests: List<BlockedRequest>
) : RecyclerView.Adapter<BlockedRequestsAdapter.ViewHolder?>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.blocked_request_item, parent, false)
        val viewHolder = ViewHolder(view)
        viewHolder.itemView.setOnLongClickListener {
            val clipboardManager =
                parent.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            ClipData.newPlainText("c001apk text", viewHolder.request.text.toString())
                ?.let { clipboardManager.setPrimaryClip(it) }
            Toast.makeText(parent.context, "已复制: ${viewHolder.request.text}", Toast.LENGTH_SHORT)
                .show()
            true
        }
        return viewHolder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val request = blockedRequests[position]
        holder.appName.text = request.appName
        holder.request.text = request.request
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        holder.timestamp.text = sdf.format(Date(request.timestamp))
        holder.icon.setImageDrawable(getAppIcon(request.packageName))
    }

    private fun getAppIcon(packageName: String): Drawable? {
        try {
            val pm: PackageManager = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            return info.loadIcon(pm)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    override fun getItemCount(): Int {
        return blockedRequests.size
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        var appName: TextView
        var request: TextView
        var timestamp: TextView
        var icon: ImageView

        init {
            appName = view.findViewById(R.id.app_name)
            request = view.findViewById(R.id.request)
            timestamp = view.findViewById(R.id.timestamp)
            icon = view.findViewById(R.id.icon)
        }
    }
}
