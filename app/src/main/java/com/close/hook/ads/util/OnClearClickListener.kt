package com.close.hook.ads.util


interface OnClearClickListener {
    fun onClearAll() {}
    fun search(keyWord: String) {}
    fun updateSortList(filter: Pair<String, List<String>>, keyWord: String, isReverse: Boolean) {}
}
