package com.close.hook.ads.data.model

data class AppFilterState(
    val appType: String,
    val filterOrder: Int,
    val isReverse: Boolean,
    val keyword: String,
    val showConfigured: Boolean,
    val showUpdated: Boolean,
    val showDisabled: Boolean
)
