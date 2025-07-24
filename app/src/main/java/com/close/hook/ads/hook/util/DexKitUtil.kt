package com.close.hook.ads.hook.util

import android.content.Context
import android.os.Debug
import android.os.Process
import com.close.hook.ads.util.AppUtils
import com.google.common.cache.CacheBuilder
import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object DexKitUtil {
    private const val LOG_TAG = "DexKitUtil"
    private const val ENABLE_DEBUG_LOG = false

    private val bridgeRef = AtomicReference<DexKitBridge?>()
    private val usageCount = AtomicInteger(0)
    private val isReleasing = AtomicBoolean(false)
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var releaseJob: Job? = null

    private const val BASE_DELAY_MS = 2000L
    private const val MAX_DELAY_MS = 30000L
    private var releaseDelay = BASE_DELAY_MS
    private var lastScheduleTime = 0L

    private val methodCache = CacheBuilder.newBuilder()
        .maximumSize(300)
        .expireAfterAccess(4, TimeUnit.HOURS)
        .softValues()
        .build<String, List<MethodData>>()

    val context: Context by lazy {
        ContextUtil.applicationContext
            ?: throw IllegalStateException("ContextUtil.applicationContext is null")
    }

    private val nativeLoaded: Boolean by lazy {
        if (!AppUtils.isMainProcess(context)) {
            log("Skipped: non-main process PID=${Process.myPid()}")
            return@lazy false
        }
        
        runCatching {
            val start = System.nanoTime()
            System.loadLibrary("dexkit")
            log("Native loaded in %.2fms", (System.nanoTime() - start) / 1_000_000.0)
            true
        }.getOrElse {
            log("Native load failed: ${it.message}")
            false
        }
    }

    fun <T> withBridge(block: (DexKitBridge) -> T): T? {
        if (!nativeLoaded) {
            return null
        }

        val start = System.nanoTime()

        val bridge = bridgeRef.get() ?: synchronized(this) {
            bridgeRef.get() ?: createBridge(context)?.also {
                bridgeRef.set(it)
                releaseDelay = BASE_DELAY_MS
                lastScheduleTime = System.currentTimeMillis()
                log("Bridge created in %.2fms", (System.nanoTime() - start) / 1_000_000.0)
            }
        } ?: return null

        cancelPendingRelease()
        usageCount.incrementAndGet()

        return try {
            block(bridge)
        } finally {
            if (usageCount.decrementAndGet() == 0) {
                scheduleRelease()
            }
            val elapsed = (System.nanoTime() - start) / 1_000_000.0
            log("Bridge ready. Elapsed: %.2fms, usageCount=%d", elapsed, usageCount.get())
        }
    }

    fun getCachedOrFindMethods(key: String, findLogic: () -> List<MethodData>?): List<MethodData> {
        val start = System.nanoTime()
        return methodCache.get(key) {
            findLogic().orEmpty().also {
                log("Cache miss '%s'. Found %d, took %.2fms", key, it.size, (System.nanoTime() - start) / 1_000_000.0)
            }
        }
    }

    private fun createBridge(context: Context): DexKitBridge? {
        val loader = context.classLoader
        return try {
            if (loader::class.java.name.endsWith("PathClassLoader")) {
                DexKitBridge.create(loader, true)
            } else {
                log("Unknown ClassLoader: ${loader::class.java.name}, fallback to apkPath")
                DexKitBridge.create(context.applicationInfo.sourceDir)
            }
        } catch (e: Exception) {
            log("Bridge creation failed: ${e.message}")
            null
        }
    }

    private fun scheduleRelease() {
        if (releaseJob?.isActive == true) return

        val now = System.currentTimeMillis()
        releaseDelay = if (now - lastScheduleTime > MAX_DELAY_MS) {
            BASE_DELAY_MS
        } else {
            (releaseDelay * 1.5).toLong().coerceAtMost(MAX_DELAY_MS)
        }
        lastScheduleTime = now

        releaseJob = releaseScope.launch {
            log("Scheduled release in ${releaseDelay / 1000}s")
            delay(releaseDelay)
            if (usageCount.get() <= 0) {
                releaseBridge()
            } else {
                log("Release skipped: usageCount=${usageCount.get()}")
            }
        }
    }

    private fun cancelPendingRelease() {
        releaseJob?.cancel()
        releaseJob = null
        releaseDelay = BASE_DELAY_MS
        lastScheduleTime = 0L
    }

    private fun releaseBridge() {
        if (!isReleasing.compareAndSet(false, true)) {
            log("Already releasing, skipped")
            return
        }

        val before = Debug.getNativeHeapAllocatedSize() shr 20
        log("Memory before close: ${before}MB")

        bridgeRef.getAndSet(null)?.run {
            close()
            log("Bridge released")
        } ?: log("Release skipped: Bridge already null")

        val after = Debug.getNativeHeapAllocatedSize() shr 20
        log("Memory after close: ${after}MB")

        isReleasing.set(false)
        releaseDelay = BASE_DELAY_MS
        lastScheduleTime = 0L
    }

    private fun log(msg: String, vararg args: Any?) {
        if (ENABLE_DEBUG_LOG) {
            val time = System.currentTimeMillis()
            val mem = Debug.getNativeHeapAllocatedSize() shr 20
            XposedBridge.log("[$LOG_TAG] T:$time M:${mem}MB ${msg.format(*args)}")
        }
    }
}
