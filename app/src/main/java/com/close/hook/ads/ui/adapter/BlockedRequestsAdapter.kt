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
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.R
import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.util.AppUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date


class BlockedRequestsAdapter(
    private val context: Context,
) : RecyclerView.Adapter<BlockedRequestsAdapter.ViewHolder?>() {


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

    private val differ: AsyncListDiffer<BlockedRequest> =
        AsyncListDiffer<BlockedRequest>(this, DIFF_CALLBACK)

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    }

    fun submitList(list: List<BlockedRequest?>?) {
        differ.submitList(list)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.blocked_request_item, parent, false)
        return ViewHolder(view).apply {
            itemView.setOnLongClickListener {
                val clipboardManager =
                    parent.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                ClipData.newPlainText("request", request.text.toString())
                    ?.let { clipboardManager.setPrimaryClip(it) }
                Toast.makeText(parent.context, "已复制: ${request.text}", Toast.LENGTH_SHORT)
                    .show()
                true
            }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val request = differ.currentList[position]
        with(holder) {
            appName.text = request.appName
            this.request.text = request.request
            timestamp.text = DATE_FORMAT.format(Date(request.timestamp))
            icon.setImageDrawable(AppUtils.getAppIcon(request.packageName))

            val textColor = if (request.requestType == "all" && request.isBlocked == true) {
                ThemeUtils.getThemeAttrColor(context, com.google.android.material.R.attr.colorError)
            } else {
                ThemeUtils.getThemeAttrColor(context, com.google.android.material.R.attr.colorControlNormal)
            }
            this.request.setTextColor(textColor)
        }
    }

    override fun getItemCount(): Int {
        return differ.currentList.size
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val appName: TextView = view.findViewById(R.id.app_name)
        val request: TextView = view.findViewById(R.id.request)
        val timestamp: TextView = view.findViewById(R.id.timestamp)
        val icon: ImageView = view.findViewById(R.id.icon)
    }
}
