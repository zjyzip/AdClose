package com.close.hook.ads.ui.adapter

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.close.hook.ads.R
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.databinding.InstallsItemAppBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AppsAdapter(
    private val context: Context,
    private val onItemClickListener: OnItemClickListener
) : RecyclerView.Adapter<AppsAdapter.AppViewHolder>() {

    private val differ = AsyncListDiffer(this, DIFF_CALLBACK)

    private val requestOptions = RequestOptions()
        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
        .override(context.resources.getDimensionPixelSize(R.dimen.app_icon_size))

    private val preloadDistance = 3

    fun submitList(list: List<AppInfo>) {
        differ.submitList(list)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = InstallsItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AppViewHolder(binding, onItemClickListener, requestOptions)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val appInfo = differ.currentList[position]
        holder.bind(appInfo)
        preloadImages(position)
    }

    override fun getItemCount(): Int = differ.currentList.size

    private fun preloadImages(currentPosition: Int) {
        val start = (currentPosition - preloadDistance).coerceAtLeast(0)
        val end = (currentPosition + preloadDistance).coerceAtMost(differ.currentList.size - 1)

        if (context is LifecycleOwner) {
            (context as LifecycleOwner).lifecycleScope.launch(Dispatchers.IO) {
                for (i in start..end) {
                    val appInfo = differ.currentList[i]
                    Glide.with(context)
                        .load(appInfo.appIcon)
                        .apply(requestOptions)
                        .preload()
                }
            }
        }
    }

    class AppViewHolder(
        private val binding: InstallsItemAppBinding,
        private val onItemClickListener: OnItemClickListener,
        private val requestOptions: RequestOptions
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
            loadAppIcon(appInfo.appIcon)
        }

        private fun loadAppIcon(icon: Any) {
            if (icon is String) {
                Glide.with(binding.appIcon.context)
                    .load(icon)
                    .apply(requestOptions)
                    .into(binding.appIcon)
            } else if (icon is Drawable) {
                binding.appIcon.setImageDrawable(icon)
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
