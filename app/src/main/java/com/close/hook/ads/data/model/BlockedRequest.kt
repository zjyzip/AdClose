package com.close.hook.ads.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class BlockedRequest(
    var requestType: String,
    var requestValue: String,
    var method: String?,
    var urlString: String?,
    var requestHeaders: String?,
    var requestBody: ByteArray?,
    var responseCode: Int,
    var responseMessage: String?,
    var responseHeaders: String?,
    var responseBody: ByteArray?,
    var responseBodyContentType: String?,
    var stack: String?,
    var dnsHost: String?,
    var fullAddress: String?
) : Parcelable
