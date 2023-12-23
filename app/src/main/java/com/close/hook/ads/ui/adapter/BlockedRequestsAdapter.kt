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
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.R
import com.close.hook.ads.data.database.BlockRequestDatabase
import com.close.hook.ads.data.model.BlockRequest
import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.util.AppUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date


class BlockedRequestsAdapter(
    private val context: Context,
) : RecyclerView.Adapter<BlockedRequestsAdapter.ViewHolder?>(), PopupMenu.OnMenuItemClickListener {

    private val blockRequestDao by lazy {
        BlockRequestDatabase.getDatabase(context).blockRequestDao()
    }
    private var request = ""

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
        AsyncListDiffer(this, DIFF_CALLBACK)

    fun submitList(list: List<BlockedRequest?>?) {
        differ.submitList(list)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.blocked_request_item, parent, false)
        val viewHolder = ViewHolder(view)
        viewHolder.itemView.setOnLongClickListener {
            request = viewHolder.request.text.toString()
            val popup = PopupMenu(context, it)
            val inflater = popup.menuInflater
            inflater.inflate(R.menu.menu_block, popup.menu)
            popup.menu.findItem(R.id.block).isVisible = !blockRequestDao.isExist(request)
            popup.menu.findItem(R.id.unBlock).isVisible = blockRequestDao.isExist(request)
            popup.setOnMenuItemClickListener(this)
            popup.show()
            true
        }

        return viewHolder
    }

    @SuppressLint("RestrictedApi", "SimpleDateFormat")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val request = differ.currentList[position]
        holder.appName.text = request.appName
        holder.request.text = request.request
        if (request.requestType == "all" && request.isBlocked == true) {
            holder.request.setTextColor(
                ThemeUtils.getThemeAttrColor(
                    context,
                    com.google.android.material.R.attr.colorError
                )
            )
        } else {
            holder.request.setTextColor(
                ThemeUtils.getThemeAttrColor(
                    context,
                    com.google.android.material.R.attr.colorControlNormal
                )
            )
        }
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        holder.timestamp.text = sdf.format(Date(request.timestamp))
        holder.icon.setImageDrawable(AppUtils.getAppIcon(request.packageName))
    }

    override fun getItemCount(): Int {
        return differ.currentList.size
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

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.copy -> {
                val clipboardManager =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                ClipData.newPlainText("c001apk text", request)
                    ?.let { clipboardManager.setPrimaryClip(it) }
                Toast.makeText(context, "已复制: $request", Toast.LENGTH_SHORT)
                    .show()
            }

            R.id.block -> {
                CoroutineScope(Dispatchers.IO).launch {
                    blockRequestDao.insert(BlockRequest(request))
                }
            }

            R.id.unBlock -> {
                CoroutineScope(Dispatchers.IO).launch {
                    blockRequestDao.delete(request)
                }
            }

        }
        return true
    }
}
