package com.close.hook.ads.data.model

data class RuleMatch(
    val matched: Boolean,
    val ruleType: String? = null,
    val ruleUrl: String? = null
) {
    companion object {
        val NOT_MATCHED = RuleMatch(false, null, null)
    }
}