package com.close.hook.ads.util

/** Literal, case-insensitive search with bounded output and cooperative cancellation. */
object BodyTextSearch {
    const val MAX_MATCHES = 10_000
    const val PAGE_CHARS = 32 * 1024
    const val PAGING_THRESHOLD = 128 * 1024

    data class Result(val starts: IntArray, val truncated: Boolean)
    data class Window(val start: Int, val end: Int, val page: Int, val pageCount: Int)

    fun find(text: String, query: String, checkCancelled: () -> Unit = {}): Result {
        if (query.isEmpty() || text.isEmpty()) return Result(IntArray(0), false)
        val pattern = CharArray(query.length) { fold(query[it]) }
        val prefix = IntArray(pattern.size)
        var matched = 0
        for (i in 1 until pattern.size) {
            if (i % 4096 == 0) checkCancelled()
            while (matched > 0 && pattern[i] != pattern[matched]) matched = prefix[matched - 1]
            if (pattern[i] == pattern[matched]) matched++
            prefix[i] = matched
        }
        var positions = IntArray(64)
        var count = 0
        matched = 0
        for (index in text.indices) {
            if (index % 4096 == 0) checkCancelled()
            val char = fold(text[index])
            while (matched > 0 && char != pattern[matched]) matched = prefix[matched - 1]
            if (char == pattern[matched]) matched++
            if (matched == pattern.size) {
                if (count == MAX_MATCHES) return Result(positions.copyOf(count), true)
                if (count == positions.size) positions = positions.copyOf(minOf(MAX_MATCHES, count * 2))
                positions[count++] = index - pattern.size + 1
                matched = prefix[matched - 1]
            }
        }
        checkCancelled()
        return Result(positions.copyOf(count), false)
    }

    fun window(text: String, requestedPage: Int, overlap: Int = 0): Window {
        val pages = if (text.length <= PAGING_THRESHOLD) 1 else (text.length - 1) / PAGE_CHARS + 1
        val page = requestedPage.coerceIn(0, pages - 1)
        if (pages == 1) return Window(0, text.length, 0, 1)
        fun boundary(offset: Int): Int = if (offset in 1 until text.length &&
            text[offset].isLowSurrogate() && text[offset - 1].isHighSurrogate()) offset + 1 else offset
        val start = boundary(page * PAGE_CHARS)
        val end = boundary(minOf(text.length.toLong(), (page + 1L) * PAGE_CHARS + overlap).toInt())
        return Window(start, end, page, pages)
    }

    fun pageForOffset(text: String, offset: Int): Int =
        if (text.length <= PAGING_THRESHOLD) 0 else offset / PAGE_CHARS

    private fun fold(char: Char): Char = char.uppercaseChar().lowercaseChar()
}
