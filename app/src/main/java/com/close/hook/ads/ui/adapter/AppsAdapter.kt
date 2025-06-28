package com.close.hook.ads.ui.adapter

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.databinding.InstallsItemAppBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis
import java.io.ByteArrayOutputStream

class AppsAdapter(
    private val onItemClickListener: OnItemClickListener,
    private val onIconLoadListener: OnIconLoadListener? = null
) : ListAdapter<AppInfo, AppsAdapter.AppViewHolder>(DIFF_CALLBACK) {

    private val iconCache = object : LruCache<String, Drawable>(100) {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = InstallsItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AppViewHolder(binding, onItemClickListener, onIconLoadListener, iconCache)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: AppViewHolder) {
        holder.cancelIconLoadJob()
        holder.binding.appIcon.setImageDrawable(null)
        super.onViewRecycled(holder)
    }

    class AppViewHolder(
        val binding: InstallsItemAppBinding,
        private val onItemClickListener: OnItemClickListener,
        private val onIconLoadListener: OnIconLoadListener?,
        private val iconCache: LruCache<String, Drawable>
    ) : RecyclerView.ViewHolder(binding.root) {

        private lateinit var currentAppInfo: AppInfo
        private var iconLoadJob: Job? = null

        init {
            binding.root.setOnClickListener {
                onItemClickListener.onItemClick(currentAppInfo, binding.appIcon.drawable)
            }
            binding.root.setOnLongClickListener {
                onItemClickListener.onItemLongClick(currentAppInfo, binding.appIcon.drawable)
                true
            }
        }

        fun bind(appInfo: AppInfo) {
            currentAppInfo = appInfo
            binding.appName.text = appInfo.appName
            binding.packageName.text = appInfo.packageName
            binding.appVersion.text = "${appInfo.versionName} (${appInfo.versionCode})"

            cancelIconLoadJob()
            val context = binding.root.context
            val cachedIcon = iconCache[appInfo.packageName]

            if (cachedIcon != null) {
                binding.appIcon.setImageDrawable(cachedIcon)
            } else {
                binding.appIcon.setImageDrawable(null)
                val lifecycleOwner = context as? LifecycleOwner
                iconLoadJob = lifecycleOwner?.lifecycleScope?.launch {
                    var icon: Drawable? = null
                    var sizeBytes: Int = 0
                    var width: Int = 0
                    var height: Int = 0
                    var memoryBytes: Int = 0

                    val loadTime = measureTimeMillis {
                        icon = withContext(Dispatchers.IO) {
                            try {
                                context.packageManager.getApplicationIcon(appInfo.packageName)
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }

                    icon?.let { loadedIcon ->
                        iconCache.put(appInfo.packageName, loadedIcon)

                        if (loadedIcon is BitmapDrawable) {
                            val bitmap = loadedIcon.bitmap
                            width = bitmap.width
                            height = bitmap.height
                            memoryBytes = bitmap.byteCount

                            try {
                                ByteArrayOutputStream().use { os ->
                                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, os)
                                    sizeBytes = os.size()
                                }
                            } catch (e: Exception) {
                                sizeBytes = memoryBytes
                            }
                        } else {
                            width = loadedIcon.intrinsicWidth
                            height = loadedIcon.intrinsicHeight
                        }

                        withContext(Dispatchers.Main) {
                            binding.appIcon.setImageDrawable(loadedIcon)
                        }
                        onIconLoadListener?.onIconLoaded(loadTime, sizeBytes, width, height, memoryBytes)
                    }
                }
            }
        }

        fun cancelIconLoadJob() {
            iconLoadJob?.cancel()
            iconLoadJob = null
        }
    }

    interface OnItemClickListener {
        fun onItemClick(appInfo: AppInfo, icon: Drawable?)
        fun onItemLongClick(appInfo: AppInfo, icon: Drawable?)
    }

    interface OnIconLoadListener {
        fun onIconLoaded(loadTimeMs: Long, sizeBytes: Int, width: Int, height: Int, memoryBytes: Int)
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
