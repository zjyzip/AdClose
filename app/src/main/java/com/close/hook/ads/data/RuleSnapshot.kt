package com.close.hook.ads.data

import com.close.hook.ads.data.model.RuleMatch
import com.close.hook.ads.data.model.Url
import java.util.Locale

data class RuleSnapshot(
    val exactUrls: Set<String>,
    val domains: Set<String>,
    val keywords: List<KeywordRule>
) {
    private val keywordMatcher = KeywordMatcher.fromRules(keywords)

    data class KeywordRule(
        val value: String,
        val normalized: String
    )
    fun match(requestValue: String, host: String?): RuleMatch {
        val normalizedRequest = requestValue.trim()
        val normalizedHost = host?.trim().orEmpty()

        if (normalizedRequest.isEmpty()) return RuleMatch.NOT_MATCHED

        val lowerRequest = normalizedRequest.lowercase(Locale.ROOT)
        val lowerHost = normalizedHost.lowercase(Locale.ROOT)

        if (lowerRequest in exactUrls) {
            return RuleMatch(matched = true, ruleType = "URL", ruleUrl = normalizedRequest)
        }

        if (lowerHost.isNotEmpty()) {
            var candidate = lowerHost.removeSuffix(".")
            while (candidate.isNotEmpty()) {
                if (candidate in domains) {
                    return RuleMatch(matched = true, ruleType = "Domain", ruleUrl = candidate)
                }
                val separator = candidate.indexOf('.')
                if (separator == -1) break
                candidate = candidate.substring(separator + 1)
            }
        }

        keywordMatcher.firstMatch(lowerRequest)?.let { keyword ->
            return RuleMatch(matched = true, ruleType = "KeyWord", ruleUrl = keyword)
        }

        return RuleMatch.NOT_MATCHED
    }

    companion object {
        val EMPTY = RuleSnapshot(
            exactUrls = emptySet(),
            domains = emptySet(),
            keywords = emptyList()
        )

        fun fromUrls(urls: List<Url>): RuleSnapshot {
            if (urls.isEmpty()) return EMPTY

            val exactUrls = LinkedHashSet<String>()
            val domains = LinkedHashSet<String>()
            val keywords = LinkedHashMap<String, String>()

            urls.forEach { rule ->
                val type = rule.type.trim()
                val value = rule.url.trim()
                if (value.isEmpty()) return@forEach

                when (type.lowercase(Locale.ROOT)) {
                    "url"     -> exactUrls += value.lowercase(Locale.ROOT)
                    "domain"  -> domains   += value.lowercase(Locale.ROOT)
                    "keyword" -> keywords.putIfAbsent(value.lowercase(Locale.ROOT), value)
                }
            }

            val keywordRules = keywords.map { (normalized, value) ->
                KeywordRule(value = value, normalized = normalized)
            }

            return RuleSnapshot(
                exactUrls = exactUrls,
                domains = domains,
                keywords = keywordRules
            )
        }
    }
}
