package com.close.hook.ads.ui.adapter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis

class AppsAdapter(
    private val onItemClickListener: OnItemClickListener,
    private val onIconLoadListener: OnIconLoadListener? = null
) : ListAdapter<AppInfo, AppsAdapter.AppViewHolder>(DIFF_CALLBACK) {

    private val iconCache = object : LruCache<String, Drawable>(200) {}
    private val loadingJobs = ConcurrentHashMap<String, Job>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = InstallsItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AppViewHolder(binding, onItemClickListener, onIconLoadListener, iconCache, loadingJobs)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: AppViewHolder) {
        holder.cancelIconLoadJob()
        super.onViewRecycled(holder)
    }

    class AppViewHolder(
        private val binding: InstallsItemAppBinding,
        private val onItemClickListener: OnItemClickListener,
        private val onIconLoadListener: OnIconLoadListener?,
        private val iconCache: LruCache<String, Drawable>,
        private val loadingJobs: ConcurrentHashMap<String, Job>
    ) : RecyclerView.ViewHolder(binding.root) {

        private val targetSizePx by lazy { (binding.root.context.resources.displayMetrics.density * 48).roundToInt() }

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
            iconCache[packageName]?.let {
                binding.appIcon.setImageDrawable(it)
                return
            }

            binding.appIcon.setImageDrawable(null)
            cancelIconLoadJob()

            (context as? LifecycleOwner)?.lifecycleScope?.launch(Dispatchers.Main.immediate) {
                if (loadingJobs.containsKey(packageName)) return@launch
                val job = launch(Dispatchers.IO) {
                    loadAndDisplayIcon(context, packageName, targetSizePx)
                    loadingJobs.remove(packageName)
                }
                loadingJobs[packageName] = job
            }
        }

        fun cancelIconLoadJob() {
        }

        private suspend fun loadAndDisplayIcon(context: Context, packageName: String, targetSize: Int) {
            var icon: Drawable? = null
            var loadTime = 0L
            var sizeBytes = 0
            var width = 0
            var height = 0
            var memoryBytes = 0

            loadTime = measureTimeMillis {
                icon = loadAndCompressIcon(context, packageName, targetSize)?.also { drawable ->
                    if (drawable is BitmapDrawable) {
                        drawable.bitmap?.let { bmp ->
                            width = bmp.width
                            height = bmp.height
                            memoryBytes = bmp.byteCount
                            sizeBytes = bmp.allocationByteCount
                        }
                    }
                }
            }

            icon?.let {
                iconCache.put(packageName, it)
                withContext(Dispatchers.Main) {
                    binding.appIcon.setImageDrawable(it)
                }
                onIconLoadListener?.onIconLoaded(loadTime, sizeBytes, width, height, memoryBytes)
            }
        }

        private fun loadAndCompressIcon(context: Context, packageName: String, targetSize: Int): Drawable? {
            val file = File(context.cacheDir, "icons/$packageName.png")
            file.takeIf { it.exists() }?.let {
                BitmapFactory.decodeFile(it.absolutePath)?.let { bmp ->
                    return BitmapDrawable(context.resources, bmp)
                }
            }
            return try {
                context.packageManager.getApplicationIcon(packageName)?.let { original ->
                    if (original is BitmapDrawable) {
                        original.bitmap?.let {
                            val resized = Bitmap.createScaledBitmap(it, targetSize, targetSize, true)
                            saveBitmapToCache(resized, file)
                            BitmapDrawable(context.resources, resized)
                        } ?: original
                    } else original
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun saveBitmapToCache(bitmap: Bitmap, file: File) {
            try {
                file.parentFile?.mkdirs()
                FileOutputStream(file).use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 85, it)
                }
            } catch (_: Exception) {
            }
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
