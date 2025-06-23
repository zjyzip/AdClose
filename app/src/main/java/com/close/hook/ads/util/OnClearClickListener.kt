package com.close.hook.ads.util

interface OnClearClickListener {
    fun onClearAll() {}
    fun search(keyWord: String) {}
    fun updateSortList(filter: Pair<Int, List<Int>>, keyWord: String, isReverse: Boolean) {}
}
