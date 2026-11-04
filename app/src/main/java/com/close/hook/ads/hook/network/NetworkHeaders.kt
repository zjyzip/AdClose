package com.close.hook.ads.hook.network

internal object NetworkHeaders {
    /** JNI supplies alternating name/value lines, including possibly empty values. */
    fun alternating(raw: String?): List<Pair<String, String>> {
        if (raw.isNullOrEmpty()) return emptyList()
        val lines = raw.split('\n').let { if (it.last().isEmpty()) it.dropLast(1) else it }
        return (0 until lines.size - 1 step 2).map { lines[it] to lines[it + 1] }
    }

    fun http2Url(headers: List<Pair<String, String>>): String? {
        fun value(name: String) = headers.firstOrNull { it.first.equals(name, true) }?.second
        val host = (value(":authority")?.takeIf(String::isNotBlank) ?: value("host"))
            ?.takeIf(String::isNotBlank) ?: return null
        val scheme = value(":scheme")?.takeIf(String::isNotBlank) ?: "https"
        val path = value(":path")?.takeIf(String::isNotBlank) ?: "/"
        return "$scheme://$host$path"
    }

    fun format(headers: List<Pair<String, String>>): String? =
        headers.takeIf { it.isNotEmpty() }?.joinToString("\n") { (name, value) -> "$name: $value" }
}
