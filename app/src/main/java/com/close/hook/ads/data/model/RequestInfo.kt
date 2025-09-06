package com.close.hook.ads.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.IgnoredOnParcel

@Parcelize
data class RequestInfo(
    var appName: String,
    var packageName: String,
    var request: String,
    var timestamp: Long,
    var requestType: String?,
    var isBlocked: Boolean?,
    var url: String?,
    var blockType: String?,
    var method: String?,
    var urlString: String?,
    var requestHeaders: String?,
    var requestBodyUriString: String?,
    var responseCode: Int,
    var responseMessage: String?,
    var responseHeaders: String?,
    var responseBodyUriString: String?,
    var responseBodyContentType: String?,
    var stack: String?,
    var dnsHost: String?,
    var fullAddress: String?
) : Parcelable {
    @IgnoredOnParcel
    @Transient var requestBody: ByteArray? = null
    @IgnoredOnParcel
    @Transient var responseBody: ByteArray? = null
}