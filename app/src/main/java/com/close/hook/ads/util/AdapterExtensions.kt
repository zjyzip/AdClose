package com.close.hook.ads.util

import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.ui.adapter.FooterAdapter

fun RecyclerView.setSpaceFooterView(footerAdapter: FooterAdapter) {
    val adapter = adapter as ConcatAdapter
    val hasFooter = adapter.adapters.contains(footerAdapter)
    val a = childCount
    val b = adapter.itemCount
    val should = if (hasFooter) {
        a >= b - 1
    } else {
        a >= b
    }
    if (should) {
        if (!hasFooter && a != 0) {
            adapter.addAdapter(footerAdapter)
        }
    } else {
        if (hasFooter) {
            adapter.removeAdapter(footerAdapter)
        }
    }
}