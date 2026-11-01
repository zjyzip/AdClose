package com.close.hook.ads.util

import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.close.hook.ads.R
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar

/** Shared feedback above the current page's navigation and floating action area. */
fun View.showFeedback(message: CharSequence, error: Boolean = false, undo: (() -> Unit)? = null): Snackbar {
    val host = feedbackHost()
    val snackbar = Snackbar.make(host, message,
        if (error || undo != null) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT)
    snackbar.setTextMaxLines(4)
    if (undo != null) snackbar.setAction(R.string.action_undo) { undo() }
    FeedbackPlacement(host, snackbar).attach()
    snackbar.show()
    return snackbar
}

/** Use the outer CoordinatorLayout so a nested page's Snackbar cannot sit behind a sibling bottom bar. */
private fun View.feedbackHost(): ViewGroup {
    var current: View? = this
    var coordinator: CoordinatorLayout? = null
    var content: ViewGroup? = null
    while (current != null) {
        if (current is CoordinatorLayout) coordinator = current
        if (current.id == android.R.id.content && current is ViewGroup) content = current
        current = current.parent as? View
    }
    return coordinator ?: content ?: (rootView as ViewGroup)
}

/**
 * Follow actual visible bounds, including scroll/translation animations. Nothing moves the FAB itself.
 * The listener exists only while this bar is attached; queued/replaced bars clean up independently.
 */
private class FeedbackPlacement(
    private val host: ViewGroup,
    private val snackbar: Snackbar
) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
    private val hostRect = Rect()
    private val visibleRect = Rect()
    private val screenPosition = IntArray(2)
    private val gap = 8.dp
    private var observing: ViewTreeObserver? = null

    fun attach() {
        // Gesture/navigation/IME avoidance is calculated below, not added twice by Material's gesture offset.
        snackbar.setGestureInsetBottomIgnored(true)
        snackbar.view.addOnAttachStateChangeListener(this)
    }

    override fun onViewAttachedToWindow(view: View) {
        observing = host.viewTreeObserver.also { it.addOnPreDrawListener(this) }
        updatePlacement()
    }

    override fun onViewDetachedFromWindow(view: View) {
        observing?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        observing = null
        view.removeOnAttachStateChangeListener(this)
    }

    override fun onPreDraw(): Boolean {
        updatePlacement()
        return true
    }

    private fun updatePlacement() {
        if (!host.isLaidOut) return
        host.getLocationOnScreen(screenPosition)
        hostRect.set(screenPosition[0], screenPosition[1],
            screenPosition[0] + host.width, screenPosition[1] + host.height)

        val insets = ViewCompat.getRootWindowInsets(host)
        val safeBottom = insets?.getInsets(WindowInsetsCompat.Type.systemBars() or
            WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.mandatorySystemGestures())?.bottom ?: 0
        // Compare to the window's bottom: a host already inset above navigation must not add it again.
        host.rootView.getLocationOnScreen(screenPosition)
        val windowBottom = screenPosition[1] + host.rootView.height
        val insetClearance = (hostRect.bottom - (windowBottom - safeBottom)).coerceAtLeast(0)
        var clearance = insetClearance

        fun visit(view: View) {
            if (view === snackbar.view || view.visibility != View.VISIBLE || view.alpha <= 0.01f) return
            if (isObstacle(view) && view.getGlobalVisibleRect(visibleRect)) {
                // getGlobalVisibleRect uses root coordinates; the host bounds above use screen coordinates.
                visibleRect.offset(screenPosition[0], screenPosition[1])
                if (Rect.intersects(visibleRect, hostRect)) {
                    clearance = maxOf(clearance, hostRect.bottom - visibleRect.top)
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    val child = view.getChildAt(index)
                    // ViewPager2 keeps neighboring pages alive. Only the selected page owns floating controls.
                    val pager = if (view is RecyclerView) view.parent as? ViewPager2 else null
                    if (view is RecyclerView && pager != null &&
                        view.getChildAdapterPosition(child) != pager.currentItem) continue
                    visit(child)
                }
            }
        }
        visit(host)

        val params = snackbar.view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val bottom = clearance + gap
        val left = maxOf(16.dp, insets?.getInsets(WindowInsetsCompat.Type.systemBars())?.left ?: 0)
        val right = maxOf(16.dp, insets?.getInsets(WindowInsetsCompat.Type.systemBars())?.right ?: 0)
        val gravity = when (params) {
            is CoordinatorLayout.LayoutParams -> params.gravity
            is FrameLayout.LayoutParams -> params.gravity
            else -> Gravity.BOTTOM
        }
        if (params.bottomMargin == bottom && params.leftMargin == left &&
            params.rightMargin == right && gravity == Gravity.BOTTOM) return
        params.bottomMargin = bottom
        params.leftMargin = left
        params.rightMargin = right
        when (params) {
            is CoordinatorLayout.LayoutParams -> params.gravity = Gravity.BOTTOM
            is FrameLayout.LayoutParams -> params.gravity = Gravity.BOTTOM
        }
        // SnackbarBaseLayout.setLayoutParams() records new "original" margins and adds its
        // own insets again. Mutate the attached params and request layout to avoid double insets.
        snackbar.view.requestLayout()
    }

    private fun isObstacle(view: View): Boolean =
        view is BottomNavigationView || view is FloatingActionButton ||
            view is ExtendedFloatingActionButton || view.id == R.id.body_page_navigation ||
            view.id == R.id.search_navigation_container
}
