package com.close.hook.ads.ui.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.close.hook.ads.R
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.databinding.InstallsItemAppBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppsAdapter(
    private val context: Context,
    private val onItemClickListener: OnItemClickListener
) : ListAdapter<AppInfo, AppsAdapter.AppViewHolder>(DIFF_CALLBACK) {

    private val requestOptions = RequestOptions()
        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
        .override(context.resources.getDimensionPixelSize(R.dimen.app_icon_size))

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = InstallsItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AppViewHolder(binding, onItemClickListener, context)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AppViewHolder(
        private val binding: InstallsItemAppBinding,
        private val onItemClickListener: OnItemClickListener,
        private val context: Context
    ) : RecyclerView.ViewHolder(binding.root) {

        private lateinit var appInfo: AppInfo

        init {
            binding.root.setOnClickListener {
                if (::appInfo.isInitialized) onItemClickListener.onItemClick(appInfo)
            }
            binding.root.setOnLongClickListener {
                if (::appInfo.isInitialized) onItemClickListener.onItemLongClick(appInfo)
                true
            }
        }

        fun bind(appInfo: AppInfo) {
            this.appInfo = appInfo
            binding.appName.text = appInfo.appName
            binding.packageName.text = appInfo.packageName
            binding.appVersion.text = "${appInfo.versionName} (${appInfo.versionCode})"

            Glide.with(binding.appIcon).clear(binding.appIcon)

            if (context is LifecycleOwner) {
                context.lifecycleScope.launch {
                    val icon = withContext(Dispatchers.IO) {
                        try {
                            context.packageManager.getApplicationIcon(appInfo.packageName)
                        } catch (e: Exception) {
                            null
                        }
                    }

                    if (icon != null && this@AppViewHolder.appInfo.packageName == appInfo.packageName) {
                        Glide.with(binding.appIcon)
                            .load(icon)
                            .apply(requestOptions)
                            .into(binding.appIcon)
                    }
                }
            }
        }
    }

    interface OnItemClickListener {
        fun onItemClick(appInfo: AppInfo)
        fun onItemLongClick(appInfo: AppInfo)
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppInfo>() {
            override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean =
                oldItem.packageName == newItem.packageName

            override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean =
                oldItem == newItem
        }
    }
}
