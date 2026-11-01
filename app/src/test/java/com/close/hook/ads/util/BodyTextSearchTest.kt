package com.close.hook.ads.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.random.Random

class BodyTextSearchTest {
    @Test
    fun preservesOverlapsAndUnicodeCaseMatching() {
        assertArrayEquals(intArrayOf(0, 1, 2), BodyTextSearch.find("aaaa", "aa").starts)
        assertArrayEquals(intArrayOf(0, 2), BodyTextSearch.find("Σxς", "σ").starts)
        assertTrue(BodyTextSearch.find("body", "").starts.isEmpty())
    }

    @Test
    fun capsStoredMatchesAndDistinguishesExactLimit() {
        val exact = BodyTextSearch.find("a".repeat(10_000), "a")
        assertEquals(10_000, exact.starts.size)
        assertFalse(exact.truncated)
        val overflow = BodyTextSearch.find("a".repeat(10_001), "a")
        assertEquals(10_000, overflow.starts.size)
        assertTrue(overflow.truncated)
    }

    @Test
    fun searchesTenMiBAndFindsTheFinalByte() {
        val text = "x".repeat(10 * 1024 * 1024 - 6) + "needle"
        val match = BodyTextSearch.find(text, "NEEDLE").starts.single()
        assertEquals(text.length - 6, match)
        val page = BodyTextSearch.pageForOffset(text, match)
        val window = BodyTextSearch.window(text, page, 6)
        assertTrue(window.end - window.start <= BodyTextSearch.PAGE_CHARS + 6)
        assertEquals("needle", text.substring(match, match + 6))
        assertTrue(match in window.start until window.end)
    }

    @Test
    fun findsMatchesAcrossPageBoundariesWithoutSplittingEmoji() {
        val start = BodyTextSearch.PAGE_CHARS - 2
        val text = "x".repeat(start) + "needle" + "y".repeat(BodyTextSearch.PAGING_THRESHOLD)
        val window = BodyTextSearch.window(text, 0, 6)
        assertTrue(start + 6 <= window.end)
        val emoji = "x".repeat(BodyTextSearch.PAGE_CHARS - 1) + "😀" + "y".repeat(BodyTextSearch.PAGING_THRESHOLD)
        val first = BodyTextSearch.window(emoji, 0)
        val second = BodyTextSearch.window(emoji, 1)
        assertEquals(first.end, second.start)
        assertFalse(emoji[first.end - 1].isHighSurrogate())
        assertFalse(emoji[second.start].isLowSurrogate())
    }

    @Test(expected = CancellationException::class)
    fun checksCancellationEvenForNoMatch() {
        BodyTextSearch.find("x".repeat(1024 * 1024), "missing") { throw CancellationException() }
    }

    @Test
    fun agreesWithLiteralReferenceOnRandomJsonLikeText() {
        val random = Random(20261030)
        val alphabet = "aAbB{\"}:,123广告Σςİ"
        repeat(500) {
            val text = buildString { repeat(800) { append(alphabet.random(random)) } }
            val query = buildString { repeat(random.nextInt(1, 8)) { append(alphabet.random(random)) } }
            val reference = text.indices.filter { text.regionMatches(it, query, 0, query.length, true) }.toIntArray()
            assertArrayEquals(reference, BodyTextSearch.find(text, query).starts)
        }
    }
}
