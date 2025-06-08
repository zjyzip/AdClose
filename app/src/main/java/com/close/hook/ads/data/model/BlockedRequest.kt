package com.close.hook.ads.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class BlockedRequest(
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
    var responseCode: Int,
    var responseMessage: String?,
    var responseHeaders: String?,
    var stack: String?,
    var dnsHost: String?,
    var dnsCidr: String?,
    var fullAddress: String?
) : Parcelable
