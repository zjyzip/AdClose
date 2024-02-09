package com.close.hook.ads.ui.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.close.hook.ads.R
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.databinding.InstallsItemAppBinding

class AppsAdapter(
    context: Context,
    private val onItemClickListener: OnItemClickListener
) : ListAdapter<AppInfo, AppsAdapter.AppViewHolder>(DIFF_CALLBACK) {

    private val requestOptions = RequestOptions()
        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
        .override(context.resources.getDimensionPixelSize(R.dimen.app_icon_size))

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder =
        AppViewHolder(
            InstallsItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onItemClickListener
        )

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position), requestOptions)
    }

    class AppViewHolder(
        private val binding: InstallsItemAppBinding,
        private val onItemClickListener: OnItemClickListener
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(appInfo: AppInfo, requestOptions: RequestOptions) {
            with(binding) {
                appName.text = appInfo.appName
                packageName.text = appInfo.packageName
                appVersion.text = "${appInfo.versionName} (${appInfo.versionCode})"
                appIcon.let { imageView ->
                    Glide.with(imageView.context).load(appInfo.appIcon).apply(requestOptions).into(imageView)
                }
                root.setOnClickListener { onItemClickListener.onItemClick(appInfo.packageName) }
                root.setOnLongClickListener {
                    onItemClickListener.onItemLongClick(appInfo.packageName)
                    true
                }
            }
        }
    }

    interface OnItemClickListener {
        fun onItemClick(packageName: String)
        fun onItemLongClick(packageName: String)
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
