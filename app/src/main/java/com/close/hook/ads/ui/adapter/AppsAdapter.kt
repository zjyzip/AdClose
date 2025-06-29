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
import kotlinx.coroutines.*
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
    private val loadingMap = ConcurrentHashMap<String, Job>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = InstallsItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AppViewHolder(binding, onItemClickListener, onIconLoadListener, iconCache, loadingMap)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: AppViewHolder) {
        holder.cancelIconLoadJob()
        super.onViewRecycled(holder)
    }

    class AppViewHolder(
        val binding: InstallsItemAppBinding,
        private val onItemClickListener: OnItemClickListener,
        private val onIconLoadListener: OnIconLoadListener?,
        private val iconCache: LruCache<String, Drawable>,
        private val loadingMap: ConcurrentHashMap<String, Job>
    ) : RecyclerView.ViewHolder(binding.root) {

        private lateinit var currentAppInfo: AppInfo
        private var iconLoadJob: Job? = null
        private val targetSizePx by lazy {
            (binding.root.context.resources.displayMetrics.density * 48).roundToInt() // 48dp
        }

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

            val context = binding.root.context
            val key = appInfo.packageName
            val cached = iconCache[key]

            if (cached != null) {
                binding.appIcon.setImageDrawable(cached)
                return
            }

            binding.appIcon.setImageDrawable(null)
            val lifecycleOwner = context as? LifecycleOwner ?: return

            iconLoadJob = lifecycleOwner.lifecycleScope.launch(Dispatchers.Main.immediate) {
                if (loadingMap.containsKey(key)) return@launch

                val job = launch(Dispatchers.IO) {
                    var icon: Drawable? = null
                    var sizeBytes = 0
                    var width = 0
                    var height = 0
                    var memoryBytes = 0

                    val loadTime = measureTimeMillis {
                        icon = loadAndCompressIcon(context, key, targetSizePx)?.also {
                            if (it is BitmapDrawable) {
                                val bmp = it.bitmap
                                width = bmp.width
                                height = bmp.height
                                memoryBytes = bmp.byteCount
                                sizeBytes = bmp.allocationByteCount
                            }
                        }
                    }

                    icon?.let {
                        iconCache.put(key, it)
                        withContext(Dispatchers.Main) {
                            binding.appIcon.setImageDrawable(it)
                        }
                        onIconLoadListener?.onIconLoaded(loadTime, sizeBytes, width, height, memoryBytes)
                    }

                    loadingMap.remove(key)
                }

                loadingMap[key] = job
                job.join()
            }
        }

        fun cancelIconLoadJob() {
            iconLoadJob?.cancel()
            iconLoadJob = null
        }

        private fun loadAndCompressIcon(context: Context, packageName: String, targetSize: Int): Drawable? {
            val cacheFile = File(context.cacheDir, "icons/$packageName.png")
            if (cacheFile.exists()) {
                BitmapFactory.decodeFile(cacheFile.absolutePath)?.let {
                    return BitmapDrawable(context.resources, it)
                }
            }

            val original = try {
                context.packageManager.getApplicationIcon(packageName)
            } catch (_: Exception) {
                null
            }

            if (original is BitmapDrawable) {
                val resized = Bitmap.createScaledBitmap(original.bitmap, targetSize, targetSize, true)
                try {
                    cacheFile.parentFile?.mkdirs()
                    FileOutputStream(cacheFile).use {
                        resized.compress(Bitmap.CompressFormat.PNG, 85, it)
                    }
                } catch (_: Exception) {}
                return BitmapDrawable(context.resources, resized)
            }

            return original
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
