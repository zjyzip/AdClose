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
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var releaseJob: Job? = null
    private val lock = Any()

    private const val RELEASE_DELAY_MS = 3000L

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
            return@lazy false
        }
        
        runCatching {
            val start = System.nanoTime()
            System.loadLibrary("dexkit")
            if (ENABLE_DEBUG_LOG) {
                log("Native loaded in %.2fms", (System.nanoTime() - start) / 1_000_000.0)
            }
            true
        }.onFailure {
            XposedBridge.log("DexKitUtil: Native load failed: ${it.message}")
        }.getOrDefault(false)
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
        return try {
            val start = if (ENABLE_DEBUG_LOG) System.nanoTime() else 0L
            
            methodCache.get(key) {
                findLogic().orEmpty().also {
                    if (ENABLE_DEBUG_LOG) {
                        log("Cache miss for '$key'. Found ${it.size}, took %.2fms", (System.nanoTime() - start) / 1_000_000.0)
                    }
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("DexKitUtil: Error in cache get for $key: ${e.message}")
            emptyList()
        }
    }
    
    private fun acquireBridge(): DexKitBridge? {
        bridge?.let {
            postAcquire()
            return it
        }

        synchronized(lock) {
            bridge?.let {
                postAcquire()
                return it
            }

            val start = if (ENABLE_DEBUG_LOG) System.nanoTime() else 0L
            val newBridge = createBridgeInternal(context) ?: return null
            
            if (ENABLE_DEBUG_LOG) {
                log("Bridge created in %.2fms", (System.nanoTime() - start) / 1_000_000.0)
            }
            
            bridge = newBridge
            postAcquire()
            return newBridge
        }
    }
    
    private fun postAcquire() {
        usageCount.incrementAndGet()
        synchronized(lock) {
            releaseJob?.cancel()
            releaseJob = null
        }
    }
    
    private fun releaseUsage() {
        if (usageCount.decrementAndGet() == 0) {
            scheduleRelease()
        }
    }

    private fun createBridgeInternal(context: Context): DexKitBridge? {
        return runCatching {
            DexKitBridge.create(context.classLoader, true)
        }.onFailure {
            XposedBridge.log("DexKitUtil: Bridge creation failed: ${it.message}")
        }.getOrNull()
    }

    private fun scheduleRelease() {
        synchronized(lock) {
            if (releaseJob?.isActive == true) return
            
            releaseJob = releaseScope.launch {
                delay(RELEASE_DELAY_MS)
                releaseBridge()
            }
        }
    }

    private fun releaseBridge() {
        synchronized(lock) {
            if (usageCount.get() > 0) return
            
            bridge?.let {
                try {
                    if (ENABLE_DEBUG_LOG) {
                        val before = Debug.getNativeHeapAllocatedSize() shr 20
                        it.close()
                        val after = Debug.getNativeHeapAllocatedSize() shr 20
                        log("Bridge released. Freed: ${before - after}MB")
                    } else {
                        it.close()
                    }
                } catch (e: Exception) {
                    XposedBridge.log("DexKitUtil: Error closing bridge: ${e.message}")
                }
                bridge = null
            }
            releaseJob = null
        }
    }

    private inline fun log(format: String, vararg args: Any?) {
        if (ENABLE_DEBUG_LOG) {
            XposedBridge.log("[$LOG_TAG] ${String.format(format, *args)}")
        }
    }
}
