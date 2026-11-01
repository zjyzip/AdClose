package com.close.hook.ads.data

import com.close.hook.ads.data.model.LogEntry
import com.close.hook.ads.data.repository.LogRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LogUndoTest {
    @Before fun reset() = LogRepository.clearLogs()
    @After fun cleanUp() = LogRepository.clearLogs()

    private fun entry(id: String, time: Long, scope: String) =
        LogEntry(id, time, "test", id, "app", null, scope)

    @Test
    fun undoRetainsNewMessagesAndDoesNotDuplicateRestoredIds() = runBlocking {
        val old = entry("old", 1, "app")
        val other = entry("global", 2, LogEntry.GLOBAL_HOOK_SCOPE)
        LogRepository.addLogs(listOf(old, other))
        val removed = LogRepository.clearLogsForPackage("app")
        val fresh = entry("fresh", 3, "app")
        LogRepository.addLogs(listOf(fresh))
        LogRepository.restoreLogs(removed)
        LogRepository.restoreLogs(removed)
        assertEquals(listOf("fresh", "old"), LogRepository.getLogsForPackage("app").map { it.id })
        assertEquals(listOf("global"), LogRepository.getLogsForPackage(null).map { it.id })
    }

    @Test
    fun restorationHonorsCacheLimitAndKeepsNewestMessages() = runBlocking {
        val entries = List(1000) { entry("old-$it", it.toLong(), "app") }
        LogRepository.addLogs(entries)
        val removed = LogRepository.clearLogsForPackage("app")
        LogRepository.addLogs(listOf(entry("fresh", 2000, "app")))
        LogRepository.restoreLogs(removed)
        val restored = LogRepository.getLogsForPackage("app")
        assertEquals(1000, restored.size)
        assertEquals("fresh", restored.first().id)
        assertEquals("old-1", restored.last().id)
    }
}
