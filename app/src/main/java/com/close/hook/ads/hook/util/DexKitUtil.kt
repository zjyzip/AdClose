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
import java.util.concurrent.atomic.AtomicInteger

object DexKitUtil {
    private const val LOG_TAG = "DexKitUtil"
    private const val ENABLE_DEBUG_LOG = false

    @Volatile
    private var bridge: DexKitBridge? = null
    private val usageCount = AtomicInteger(0)
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var releaseJob: Job? = null
    private val lock = Any()

    private const val BASE_DELAY_MS = 2000L
    private const val MAX_DELAY_MS = 30000L
    private var releaseDelay = BASE_DELAY_MS
    private var lastScheduleTime = 0L

    private val methodCache = CacheBuilder.newBuilder()
        .maximumSize(300)
        .expireAfterAccess(4, TimeUnit.HOURS)
        .softValues()
        .build<String, List<MethodData>>()

    internal val context: Context by lazy {
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
        if (!nativeLoaded) return null
        val bridgeInstance = acquireBridge() ?: return null
        return try {
            block(bridgeInstance)
        } finally {
            releaseUsage()
        }
    }

    fun getCachedOrFindMethods(key: String, findLogic: () -> List<MethodData>?): List<MethodData> {
        val start = System.nanoTime()
        return methodCache.get(key) {
            findLogic().orEmpty().also {
                log("Cache miss for key '%s'. Found %d methods, took %.2fms", key, it.size, (System.nanoTime() - start) / 1_000_000.0)
            }
        }
    }
    
    private fun acquireBridge(): DexKitBridge? {
        bridge?.let { return it.also { postAcquire() } }

        return synchronized(lock) {
            bridge ?: createBridgeInternal(context)?.also {
                val start = System.nanoTime()
                bridge = it
                log("Bridge created in %.2fms", (System.nanoTime() - start) / 1_000_000.0)
            }
        }?.also { postAcquire() }
    }
    
    private fun postAcquire() {
        cancelPendingRelease()
        usageCount.incrementAndGet()
        log("Bridge acquired on TID:${Thread.currentThread().id}. Usage count: ${usageCount.get()}")
    }
    
    private fun releaseUsage() {
        if (usageCount.decrementAndGet() == 0) {
            log("Last usage released on TID:${Thread.currentThread().id}. Scheduling bridge release.")
            scheduleRelease()
        }
    }

    private fun createBridgeInternal(context: Context): DexKitBridge? {
        log("Attempting to create bridge with ClassLoader: ${context.classLoader}")
        return runCatching {
            DexKitBridge.create(context.classLoader, true)
        }.getOrElse {
            log("Bridge creation failed: ${it.message}")
            null
        }
    }

    private fun scheduleRelease() {
        if (releaseJob?.isActive == true) return

        val now = System.currentTimeMillis()
        releaseDelay = if (now - lastScheduleTime > MAX_DELAY_MS) BASE_DELAY_MS 
                       else (releaseDelay * 1.5).toLong().coerceAtMost(MAX_DELAY_MS)
        lastScheduleTime = now

        releaseJob = releaseScope.launch {
            log("Scheduled release in ${releaseDelay / 1000}s")
            delay(releaseDelay)
            if (usageCount.get() == 0) {
                releaseBridge()
            } else {
                log("Release skipped: bridge is in use again. Usage count: ${usageCount.get()}")
            }
        }
    }

    private fun cancelPendingRelease() {
        releaseJob?.cancel()
        releaseJob = null
    }

    private fun releaseBridge() {
        synchronized(lock) {
            if (bridge == null || usageCount.get() != 0) {
                log("Release skipped: Bridge already null or in use.")
                return
            }

            val before = Debug.getNativeHeapAllocatedSize() shr 20
            log("Memory before close: ${before}MB")

            bridge?.close()
            bridge = null
            log("Bridge released")

            val after = Debug.getNativeHeapAllocatedSize() shr 20
            log("Memory after close: ${after}MB, Freed: ${before - after}MB")
        }
        releaseDelay = BASE_DELAY_MS
        lastScheduleTime = 0L
    }

    private fun log(msg: String, vararg args: Any?) {
        if (ENABLE_DEBUG_LOG) {
            XposedBridge.log("[$LOG_TAG] M:${Debug.getNativeHeapAllocatedSize() shr 20}MB | ${String.format(msg, *args)}")
        }
    }
}
