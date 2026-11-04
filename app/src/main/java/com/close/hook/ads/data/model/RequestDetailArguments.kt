package com.close.hook.ads.data.model

import android.os.Bundle

internal fun RequestInfo.detailArguments() = Bundle().apply {
    putParcelable("requestInfo", this@detailArguments)
    putString("method", method)
    putString("urlString", urlString ?: request.takeIf { method != null })
    putString("requestHeaders", requestHeaders)
    putString("requestBodyUriString", requestBodyUriString)
    putString("responseCode", responseCode.toString())
    putString("responseMessage", responseMessage)
    putString("responseHeaders", responseHeaders)
    putString("responseBodyUriString", responseBodyUriString)
    putString("stack", stack)
    putString("dnsHost", dnsHost)
    putString("fullAddress", fullAddress)
}
