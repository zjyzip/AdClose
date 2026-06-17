package com.close.hook.ads.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Paints a circular swatch as three sectors — primary on the top half,
 * secondary on the bottom-left quarter, tertiary on the bottom-right quarter —
 * the way the system wallpaper colour picker renders palette previews.
 *
 * The host MaterialCardView clips the rectangles to a circle via its corner
 * radius, so this view only paints colored rectangles.
 */
class PaletteCircleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var top: Int = 0
    private var bottomLeft: Int = 0
    private var bottomRight: Int = 0
    private var solid: Boolean = true

    fun setSolid(color: Int) {
        top = color
        solid = true
        invalidate()
    }

    fun setQuadrants(topColor: Int, bottomLeftColor: Int, bottomRightColor: Int) {
        top = topColor
        bottomLeft = bottomLeftColor
        bottomRight = bottomRightColor
        solid = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (solid) {
            paint.color = top
            canvas.drawRect(0f, 0f, w, h, paint)
            return
        }
        val cx = w / 2f
        val cy = h / 2f
        paint.color = top
        canvas.drawRect(0f, 0f, w, cy, paint)
        paint.color = bottomLeft
        canvas.drawRect(0f, cy, cx, h, paint)
        paint.color = bottomRight
        canvas.drawRect(cx, cy, w, h, paint)
    }
}
