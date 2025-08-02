package com.close.hook.ads.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class RequestInfo(
    val requestType: String,
    val requestValue: String,
    val method: String?,
    val urlString: String?,
    val requestHeaders: String?,
    val responseCode: Int,
    val responseMessage: String?,
    val responseHeaders: String?,
    val responseBody: ByteArray?,
    val responseBodyContentType: String?,
    val stack: String?,
    val dnsHost: String?,
    val fullAddress: String?
) : Parcelable
