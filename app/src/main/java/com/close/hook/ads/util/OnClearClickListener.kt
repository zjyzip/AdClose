package com.close.hook.ads.util

interface OnClearClickListener {
    fun onClearAll() {}

    fun search(keyWord: String) {}

    fun updateSortList(filterOrder: Int, keyWord: String, isReverse: Boolean, showConfigured: Boolean, showUpdated: Boolean, showDisabled: Boolean) {}
}
