package com.close.hook.ads.hook

import com.close.hook.ads.data.model.LogEntry
import com.close.hook.ads.hook.util.LogPayloadPolicy
import org.junit.Assert.*
import org.junit.Test

class LogPayloadPolicyTest {
    @Test fun aNormalStackLongerThanTheOldLimitRemainsComplete() {
        val stack = "Stack Trace:\n" + (1..60).joinToString("\n") {
            "  android.app.servertransaction.TransactionExecutor.executeLifecycleItem(line: $it)"
        }
        assertTrue(stack.length > 2000)
        assertEquals(stack, LogPayloadPolicy.stack(stack))
        assertNull(LogPayloadPolicy.stack(null))
    }

    @Test fun oversizedStacksEndAtAFrameBoundaryWithAnExplicitMarker() {
        val line = "  android.app.servertransaction.TransactionExecutor.executeLifecycleItem(line: 123)"
        val result = LogPayloadPolicy.stack(List(1000) { line }.joinToString("\n"))!!
        assertTrue(result.length <= LogPayloadPolicy.MAX_STACK_CHARS)
        assertTrue(result.endsWith("\n[truncated]\n"))
        assertTrue(result.lineSequence().filter { it.isNotEmpty() && it != "[truncated]" }.all { it == line })
    }

    @Test fun truncationDoesNotLeaveAHalfSurrogateForASingleLongLine() {
        val result = LogPayloadPolicy.stack("😀".repeat(20_000))!!
        val payload = result.substringBefore("\n[truncated]")
        assertFalse(payload.last().isHighSurrogate())
    }

    @Test fun largeLogBurstsAreSplitWithoutDroppingOrReorderingEntries() {
        val entries = (1..50).map { index ->
            LogEntry(id = index.toString(), timestamp = index.toLong(), tag = "CustomHook",
                message = LogPayloadPolicy.message("m".repeat(50_000)), packageName = "test.app",
                stackTrace = LogPayloadPolicy.stack("frame\n".repeat(10_000)), hookScope = "global")
        }
        val batches = LogPayloadPolicy.batches(entries)
        assertTrue(batches.size > 1)
        assertEquals(entries, batches.flatten())
        assertTrue(batches.all { batch -> batch.sumOf(LogPayloadPolicy::estimatedBytes) <= LogPayloadPolicy.MAX_BATCH_BYTES })
        assertTrue(LogPayloadPolicy.batches(emptyList()).isEmpty())
    }
}
