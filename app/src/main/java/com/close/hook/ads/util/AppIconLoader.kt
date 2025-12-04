package com.close.hook.ads.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

object AppIconLoader {

    private val iconCache = object : LruCache<String, Drawable>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Drawable): Int {
            return if (value is BitmapDrawable) {
                value.bitmap.byteCount
            } else {
                4096 
            }
        }
    }

    private val loadingMutexs = ConcurrentHashMap<String, Mutex>()

    suspend fun loadAndCompressIcon(context: Context, packageName: String, targetSizePx: Int): Drawable? {
        iconCache[packageName]?.let { return it }

        val mutex = loadingMutexs.getOrPut(packageName) { Mutex() }

        return mutex.withLock {
            iconCache[packageName]?.let { return@withLock it }

            withContext(Dispatchers.IO) {
                val iconsDir = File(context.cacheDir, "icons")
                val file = File(iconsDir, "$packageName.png")

                if (file.exists()) {
                    try {
                        val bmp = BitmapFactory.decodeFile(file.absolutePath)
                        if (bmp != null) {
                            val drawable = BitmapDrawable(context.resources, bmp)
                            iconCache.put(packageName, drawable)
                            return@withContext drawable
                        }
                    } catch (e: Exception) {
                        file.delete()
                    }
                }

                try {
                    if (!iconsDir.exists()) iconsDir.mkdirs()

                    val pm = context.packageManager
                    val originalDrawable = pm.getApplicationIcon(packageName)
                    
                    val resizedBitmap = originalDrawable.toBitmap(targetSizePx)
                    
                    saveBitmapToFileCache(resizedBitmap, file)
                    
                    val drawable = BitmapDrawable(context.resources, resizedBitmap)
                    iconCache.put(packageName, drawable)
                    drawable
                } catch (e: Exception) {
                    null
                }
            }
        }.also {
            loadingMutexs.remove(packageName)
        }
    }

    private fun Drawable.toBitmap(size: Int): Bitmap {
        if (this is BitmapDrawable) {
            if (bitmap.width <= size && bitmap.height <= size) {
                return bitmap
            }
            return Bitmap.createScaledBitmap(bitmap, size, size, true)
        }

        val config = if (opacity != android.graphics.PixelFormat.OPAQUE) {
            Bitmap.Config.ARGB_8888
        } else {
            Bitmap.Config.RGB_565
        }

        val bitmap = Bitmap.createBitmap(size, size, config)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, size, size)
        draw(canvas)
        return bitmap
    }

    private fun saveBitmapToFileCache(bitmap: Bitmap, file: File) {
        try {
            FileOutputStream(file).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.flush()
            }
        } catch (e: Exception) {
        }
    }

    fun calculateTargetIconSizePx(context: Context): Int {
        return (context.resources.displayMetrics.density * 48).roundToInt()
    }
}
