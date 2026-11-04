package com.close.hook.ads.hook.network

import java.net.URI
import java.util.Locale

internal object NetworkUrl {
    /** URI path decoding preserves literal '+'; URLDecoder is for form data, not URL paths. */
    fun withoutQuery(value: String): String = runCatching {
        val uri = URI(value)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return@runCatching value
        val authority = uri.rawAuthority ?: return@runCatching value.substringBefore('?').substringBefore('#')
        val host = uri.host
        val safeAuthority = if (host == null) authority.substringAfterLast('@') else {
            val bracketed = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
            val port = uri.port
            bracketed + if (port < 0 || (scheme == "http" && port == 80) || (scheme == "https" && port == 443)) "" else ":$port"
        }
        "$scheme://$safeAuthority${uri.path.orEmpty()}"
    }.getOrElse { value.substringBefore('?').substringBefore('#') }
}
