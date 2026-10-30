package com.close.hook.ads.data

import com.close.hook.ads.data.model.Url
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

class KeywordMatcherTest {
    private fun snapshot(vararg values: String): RuleSnapshot =
        RuleSnapshot.fromUrls(values.map { Url("KeyWord", it) })

    @Test
    fun preservesRulePriorityInsteadOfTextPositionOrMatchLength() {
        assertEquals("late", snapshot("late", "early").match("early then late", null).ruleUrl)
        assertEquals("hers", snapshot("hers", "he", "she").match("ushers", null).ruleUrl)
        assertEquals("he", snapshot("he", "hers", "she").match("ushers", null).ruleUrl)
        assertEquals("abcd", snapshot("abcd", "bc").match("xabcd", null).ruleUrl)
        assertEquals("bc", snapshot("bc", "abcd").match("xabcd", null).ruleUrl)
    }

    @Test
    fun handlesEmptyDuplicateCaseAndUnicodeRules() {
        assertNull(RuleSnapshot.EMPTY.match("anything", null).ruleUrl)
        assertNull(snapshot("", "  ").match("anything", null).ruleUrl)
        assertNull(snapshot("ads").match("", null).ruleUrl)
        assertEquals("AdS", snapshot("AdS", "ads").match("HTTPS://SITE/ADS", null).ruleUrl)
        assertEquals(1, snapshot("AdS", "ads").keywords.size)
        assertEquals("广告", snapshot("广告").match("/广告/配置", null).ruleUrl)
        assertEquals("İ", snapshot("İ").match("İ", null).ruleUrl)
    }

    @Test
    fun retainsUrlAndDomainPrecedenceAndRebuildsOnCopy() {
        val rules = RuleSnapshot.fromUrls(listOf(
            Url("KeyWord", "ads"), Url("Domain", "example.com"),
            Url("URL", "https://example.com/ads")
        ))
        assertEquals("URL", rules.match("https://example.com/ads", "example.com").ruleType)
        assertEquals("Domain", rules.match("https://sub.example.com/ads", "sub.example.com").ruleType)
        val copy = rules.copy(keywords = snapshot("tracker").keywords)
        assertEquals("tracker", copy.match("/tracker", null).ruleUrl)
        assertNull(copy.match("/ads", null).ruleUrl)
    }

    @Test
    fun matchesSequentialReferenceOnSeededRandomCases() {
        val random = Random(20261029)
        val alphabet = "abcs/广告İΣ"
        repeat(300) {
            val values = List(random.nextInt(1, 60)) {
                buildString { repeat(random.nextInt(1, 10)) { append(alphabet.random(random)) } }
            }
            val rules = snapshot(*values.toTypedArray())
            repeat(40) {
                val text = buildString { repeat(random.nextInt(0, 120)) { append(alphabet.random(random)) } }
                val normalized = text.lowercase(Locale.ROOT)
                val expected = rules.keywords.firstOrNull { normalized.contains(it.normalized) }?.value
                assertEquals("text=$text rules=$values", expected, rules.match(text, null).ruleUrl)
            }
        }
    }
}
