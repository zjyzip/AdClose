package com.close.hook.ads.ui.adapter

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.databinding.InstallsItemAppBinding
import com.close.hook.ads.util.AppIconLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        super.onViewRecycled(holder)
    }

    class AppViewHolder(
        private val binding: InstallsItemAppBinding,
        private val onItemClickListener: OnItemClickListener
    ) : RecyclerView.ViewHolder(binding.root) {

        private val targetSizePx by lazy { AppIconLoader.calculateTargetIconSizePx(binding.root.context) }

        init {
            binding.root.setOnClickListener {
                (binding.root.tag as? AppInfo)?.let { app ->
                    onItemClickListener.onItemClick(app, binding.appIcon.drawable)
                }
            }
            binding.root.setOnLongClickListener {
                (binding.root.tag as? AppInfo)?.let { app ->
                    onItemClickListener.onItemLongClick(app, binding.appIcon.drawable)
                }
                true
            }
        }

        fun bind(appInfo: AppInfo) {
            binding.root.tag = appInfo
            binding.appName.text = appInfo.appName
            binding.packageName.text = appInfo.packageName
            binding.appVersion.text = "${appInfo.versionName} (${appInfo.versionCode})"

            val context = binding.root.context
            val packageName = appInfo.packageName

            (context as? LifecycleOwner)?.lifecycleScope?.launch {
                val icon = AppIconLoader.loadAndCompressIcon(context, packageName, targetSizePx)

                if (binding.root.tag == appInfo) {
                    withContext(Dispatchers.Main) {
                        binding.appIcon.setImageDrawable(icon)
                    }
                }
            }
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
