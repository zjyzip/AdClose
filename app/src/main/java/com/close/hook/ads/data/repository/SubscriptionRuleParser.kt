package com.close.hook.ads.data.repository

import com.close.hook.ads.data.model.SubscriptionRule
import java.io.InputStream
import java.net.IDN
import java.net.URI
import java.nio.charset.StandardCharsets

object SubscriptionRuleParser {

    const val MAX_RULES_PER_SOURCE = 200_000
    private const val MAX_KEYWORDS = 10_000
    private val hostToken = Regex("(?i)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}\\.?")
    private val ipv4 = Regex("(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}")
    private val adGuardDomain = Regex("^\\|\\|([^/^$*|]+)\\^$")

    data class Result(
        val rules: List<ParsedRule>,
        val skippedCount: Int
    )

    data class ParsedRule(val type: String, val value: String)

    fun parse(input: InputStream): Result {
        val domains = LinkedHashSet<String>()
        val keywords = LinkedHashSet<String>()
        var skipped = 0

        input.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.forEachIndexed { index, rawLine ->
                val line = rawLine.removePrefix(if (index == 0) "\uFEFF" else "").trim()
                if (line.isEmpty() || line.startsWith("!") || line.startsWith("#") || line.startsWith("[")) {
                    return@forEachIndexed
                }

                when {
                    line.startsWith("@@") || line.contains("##") || line.contains("#@#") ||
                        line.startsWith("/") || line.contains('$') || line.contains('*') -> skipped++

                    line.startsWith("keyword:", ignoreCase = true) -> {
                        val keyword = line.substringAfter(':').trim()
                        if (isValidKeyword(keyword) && keywords.size < MAX_KEYWORDS) keywords += keyword else skipped++
                    }

                    line.startsWith("keyword,", ignoreCase = true) -> {
                        val keyword = line.substringAfter(',').trim()
                        if (isValidKeyword(keyword) && keywords.size < MAX_KEYWORDS) keywords += keyword else skipped++
                    }

                    else -> {
                        val accepted = parseDomainLine(line, domains)
                        if (!accepted) skipped++
                    }
                }

                if (domains.size + keywords.size > MAX_RULES_PER_SOURCE) {
                    throw IllegalArgumentException("Subscription contains more than $MAX_RULES_PER_SOURCE rules")
                }
            }
        }

        return Result(
            rules = buildList(domains.size + keywords.size) {
                domains.forEach { add(ParsedRule("Domain", it)) }
                keywords.forEach { add(ParsedRule("KeyWord", it)) }
            },
            skippedCount = skipped
        )
    }

    private fun parseDomainLine(line: String, domains: MutableSet<String>): Boolean {
        adGuardDomain.matchEntire(line)?.groupValues?.getOrNull(1)?.let { domain ->
            return normalizeDomain(domain)?.let(domains::add) == true
        }

        val tokens = line.substringBefore('#').trim().split(Regex("\\s+"))
        if (tokens.size >= 2 && isIpAddress(tokens.first())) {
            var accepted = false
            tokens.drop(1).forEach { token ->
                normalizeDomain(token)?.let {
                    domains += it
                    accepted = true
                }
            }
            return accepted
        }

        if (line.startsWith("http://", true) || line.startsWith("https://", true)) {
            return runCatching {
                val uri = URI(line)
                if (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
                    normalizeDomain(uri.host)?.let(domains::add) != null
                } else {
                    false
                }
            }.getOrDefault(false)
        }

        normalizeDomain(line)?.let {
            domains += it
            return true
        }

        if (line.none { it in "|^$*/@" }) {
            var accepted = false
            hostToken.findAll(line).forEach { match ->
                normalizeDomain(match.value)?.let {
                    domains += it
                    accepted = true
                }
            }
            return accepted
        }
        return false
    }

    private fun isIpAddress(value: String): Boolean = ipv4.matches(value) ||
        value.startsWith("[") || value.count { it == ':' } >= 2

    private fun normalizeDomain(raw: String?): String? {
        val value = raw?.trim()?.removeSuffix(".")?.lowercase().orEmpty()
        if (value.isEmpty() || value == "localhost" || value.length > 253 || '.' !in value) return null
        return runCatching { IDN.toASCII(value) }
            .getOrNull()
            ?.takeIf { hostToken.matches(it) }
    }

    private fun isValidKeyword(value: String): Boolean =
        value.length in 3..256 && value.none { it.isWhitespace() || it in "|^$*@#" }
}
