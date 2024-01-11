package com.close.hook.ads.util

import com.close.hook.ads.data.model.FilterBean

interface OnClearClickListener {
    fun onClearAll()
    fun search(keyWord: String?)
    fun updateSortList(filterBean: FilterBean, keyWord: String, isReverse: Boolean)
}
