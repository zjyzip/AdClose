package com.close.hook.ads.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

object AppIconLoader {

    private val iconCache = object : LruCache<String, Drawable>(200) {
        override fun sizeOf(key: String, value: Drawable): Int {
            return 1
        }
    }

    suspend fun loadAndCompressIcon(context: Context, packageName: String, targetSizePx: Int): Drawable? =
        withContext(Dispatchers.IO) {
            iconCache[packageName]?.let {
                return@withContext it
            }

            val file = File(context.cacheDir, "icons/$packageName.png")
            file.takeIf { it.exists() }?.let {
                BitmapFactory.decodeFile(it.absolutePath)?.let { bmp ->
                    val drawable = BitmapDrawable(context.resources, bmp)
                    iconCache.put(packageName, drawable)
                    return@withContext drawable
                }
            }

            return@withContext try {
                context.packageManager.getApplicationIcon(packageName)?.let { original ->
                    val drawableToCache = if (original is BitmapDrawable && original.bitmap != null) {
                        val bitmap = original.bitmap
                        val resized = Bitmap.createScaledBitmap(bitmap, targetSizePx, targetSizePx, true)
                        saveBitmapToFileCache(resized, file)
                        BitmapDrawable(context.resources, resized)
                    } else {
                        original
                    }
                    iconCache.put(packageName, drawableToCache)
                    drawableToCache
                }
            } catch (e: Exception) {
                null
            }
        }

    private fun saveBitmapToFileCache(bitmap: Bitmap, file: File) {
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun calculateTargetIconSizePx(context: Context): Int {
        return (context.resources.displayMetrics.density * 48).roundToInt()
    }
}
