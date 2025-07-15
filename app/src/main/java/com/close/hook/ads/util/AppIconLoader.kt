package com.close.hook.ads.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

object AppIconLoader {

    private val iconCache = object : LruCache<String, Drawable>((20 * 1024 * 1024)) {
        override fun sizeOf(key: String, value: Drawable): Int {
            if (value is BitmapDrawable) {
                return value.bitmap?.byteCount ?: 0
            }
            return 0
        }
    }

    suspend fun loadAndCompressIcon(context: Context, packageName: String, targetSizePx: Int): Drawable? =
        withContext(Dispatchers.IO) {
            iconCache[packageName]?.let {
                return@withContext it
            }

            val iconsDir = File(context.cacheDir, "icons")
            if (!iconsDir.exists()) {
                iconsDir.mkdirs()
            }
            val file = File(iconsDir, "$packageName.png")

            file.takeIf { it.exists() }?.let {
                BitmapFactory.decodeFile(it.absolutePath)?.let { bmp ->
                    val drawable = BitmapDrawable(context.resources, bmp)
                    iconCache.put(packageName, drawable)
                    return@withContext drawable
                }
            }

            return@withContext try {
                context.packageManager.getApplicationIcon(packageName)?.let { original ->
                    val bitmapToProcess = original.toBitmap()

                    val drawableToCache = if (bitmapToProcess != null) {
                        val resized = Bitmap.createScaledBitmap(bitmapToProcess, targetSizePx, targetSizePx, true)
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

    private fun Drawable.toBitmap(): Bitmap? {
        if (this is BitmapDrawable) {
            return this.bitmap
        }

        val constantState = this.constantState ?: return null
        val tempDrawable = constantState.newDrawable()
        val bitmap = Bitmap.createBitmap(
            tempDrawable.intrinsicWidth.coerceAtLeast(1),
            tempDrawable.intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        tempDrawable.setBounds(0, 0, canvas.width, canvas.height)
        tempDrawable.draw(canvas)
        return bitmap
    }

    private fun saveBitmapToFileCache(bitmap: Bitmap, file: File) {
        try {
            FileOutputStream(file).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } catch (e: Exception) {
        }
    }

    fun calculateTargetIconSizePx(context: Context): Int {
        return (context.resources.displayMetrics.density * 48).roundToInt()
    }
}
