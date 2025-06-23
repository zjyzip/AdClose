package com.close.hook.ads.ui.adapter

import android.graphics.drawable.Drawable
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
import kotlinx.coroutines.Job

class AppsAdapter(
    private val onItemClickListener: OnItemClickListener
) : ListAdapter<AppInfo, AppsAdapter.AppViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = InstallsItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AppViewHolder(binding, onItemClickListener)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: AppViewHolder) {
        Glide.with(holder.binding.appIcon.context).clear(holder.binding.appIcon)
        holder.cancelIconLoadJob()
        super.onViewRecycled(holder)
    }

    class AppViewHolder(
        val binding: InstallsItemAppBinding,
        private val onItemClickListener: OnItemClickListener
    ) : RecyclerView.ViewHolder(binding.root) {

        private var iconLoadJob: Job? = null
        private lateinit var appInfo: AppInfo
        private var loadedIcon: Drawable? = null

        private val requestOptions: RequestOptions by lazy {
            RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .override(binding.root.resources.getDimensionPixelSize(R.dimen.app_icon_size))
                .dontAnimate()
        }

        init {
            binding.root.setOnClickListener {
                if (::appInfo.isInitialized) onItemClickListener.onItemClick(appInfo, loadedIcon)
            }
            binding.root.setOnLongClickListener {
                if (::appInfo.isInitialized) onItemClickListener.onItemLongClick(appInfo, loadedIcon)
                true
            }
        }

        fun bind(appInfo: AppInfo) {
            this.appInfo = appInfo
            binding.appName.text = appInfo.appName
            binding.packageName.text = appInfo.packageName
            binding.appVersion.text = "${appInfo.versionName} (${appInfo.versionCode})"

            binding.appIcon.tag = appInfo.packageName

            cancelIconLoadJob()

            val lifecycleOwner = binding.root.context as? LifecycleOwner
            lifecycleOwner?.let { owner ->
                iconLoadJob = owner.lifecycleScope.launch {
                    val icon = withContext(Dispatchers.IO) {
                        try {
                            binding.root.context.packageManager.getApplicationIcon(appInfo.packageName)
                        } catch (e: Exception) {
                            null
                        }
                    }

                    if (binding.appIcon.tag == appInfo.packageName) {
                        loadedIcon = icon
                        Glide.with(binding.appIcon)
                            .load(icon)
                            .apply(requestOptions)
                            .into(binding.appIcon)
                    } else {
                        Glide.with(binding.appIcon).clear(binding.appIcon)
                        binding.appIcon.setImageDrawable(null)
                        loadedIcon = null
                    }
                }
            }
        }

        fun cancelIconLoadJob() {
            iconLoadJob?.cancel()
            iconLoadJob = null
            loadedIcon = null
        }
    }

    interface OnItemClickListener {
        fun onItemClick(appInfo: AppInfo, icon: Drawable?)
        fun onItemLongClick(appInfo: AppInfo, icon: Drawable?)
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
