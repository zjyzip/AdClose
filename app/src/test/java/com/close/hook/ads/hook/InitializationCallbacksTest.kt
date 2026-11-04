package com.close.hook.ads.hook

import com.close.hook.ads.hook.util.InitializationCallbacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class InitializationCallbacksTest {
    @Test
    fun publishesOnceAndIsolatesFailedCallbacks() {
        val calls = mutableListOf<Int>()
        val errors = AtomicInteger()
        val callbacks = InitializationCallbacks { errors.incrementAndGet() }
        callbacks.add { calls += 1 }
        callbacks.add { throw IllegalStateException("one faulty initializer") }
        callbacks.add { calls += 2 }
        assertTrue(callbacks.complete())
        assertFalse(callbacks.complete())
        callbacks.add { calls += 3 }
        assertEquals(listOf(1, 2, 3), calls)
        assertEquals(1, errors.get())
    }

    @Test
    fun concurrentRegistrationAndPublicationNeverLoseOrDuplicateACallback() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(500) {
                val calls = AtomicInteger()
                val callbacks = InitializationCallbacks { throw AssertionError(it) }
                val barrier = CyclicBarrier(2)
                val registration = executor.submit {
                    barrier.await()
                    callbacks.add { calls.incrementAndGet() }
                }
                val publication = executor.submit {
                    barrier.await()
                    callbacks.complete()
                }
                registration.get(5, TimeUnit.SECONDS)
                publication.get(5, TimeUnit.SECONDS)
                assertEquals(1, calls.get())
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
