package com.close.hook.ads.util

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.util.dp

class FooterSpaceItemDecoration(private val footerHeight: Int = 96.dp) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State
    ) {
        val adapter = parent.adapter ?: return
        val position = parent.getChildAdapterPosition(view)
        if (position == adapter.itemCount - 1 && position != RecyclerView.NO_POSITION) {
            outRect.bottom = footerHeight
        } else {
            outRect.bottom = 0
        }
    }
}
