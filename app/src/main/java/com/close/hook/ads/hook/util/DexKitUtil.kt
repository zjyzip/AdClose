package com.close.hook.ads.hook.util

import android.app.ActivityManager
import android.content.Context
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
    private const val LOG_PREFIX = "[DexKitUtil]"
    private val bridgeRef = AtomicReference<DexKitBridge?>()
    private val nativeLoaded = AtomicBoolean(false)
    private val usageCount = AtomicInteger(0)
    private val isReleasing = AtomicBoolean(false)
    private var releaseJob: Job? = null

    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val methodCache = CacheBuilder.newBuilder()
        .maximumSize(300)
        .expireAfterAccess(4, TimeUnit.HOURS)
        .softValues()
        .build<String, List<MethodData>>()

    val context: Context
        get() = ContextUtil.applicationContext
            ?: error("DexKitUtil.context is not initialized")

    private fun isMainProcess(): Boolean {
        val pid = android.os.Process.myPid()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
        return am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName == context.packageName
            ?: true
    }

    fun <T> withBridge(block: (DexKitBridge) -> T): T? {
        if (!isMainProcess()) {
            XposedBridge.log("$LOG_PREFIX Skipped in non-main process")
            return null
        }

        cancelPendingRelease()
        initBridge()
        usageCount.incrementAndGet().also {
            XposedBridge.log("$LOG_PREFIX usageCount++ => $it")
        }

        return try {
            block(bridgeRef.get()!!)
        } finally {
            val remaining = usageCount.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
            XposedBridge.log("$LOG_PREFIX usageCount-- => $remaining")
            if (remaining == 0) {
                scheduleDelayedRelease()
            }
        }
    }

    fun getCachedOrFindMethods(key: String, findLogic: () -> List<MethodData>?): List<MethodData> {
        return runCatching {
            methodCache.get(key) {
                val result = findLogic()
                if (result.isNullOrEmpty()) {
                    XposedBridge.log("$LOG_PREFIX No methods found for key: $key")
                    emptyList()
                } else {
                    XposedBridge.log("$LOG_PREFIX Cached $key -> ${result.size} methods")
                    result
                }
            }
        }.getOrElse {
            XposedBridge.log("$LOG_PREFIX Failed loading methods for $key: ${it.message}")
            emptyList()
        }
    }

    private fun initBridge() {
        if (!nativeLoaded.get()) {
            synchronized(nativeLoaded) {
                if (nativeLoaded.compareAndSet(false, true)) {
                    System.loadLibrary("dexkit")
                    XposedBridge.log("$LOG_PREFIX Native library loaded.")
                }
            }
        }

        if (bridgeRef.get() != null) return

        synchronized(this) {
            if (bridgeRef.get() != null) return
            val loader = context.classLoader
            val result = runCatching {
                DexKitBridge.create(loader, true)
            }.recoverCatching {
                Thread.sleep(100)
                DexKitBridge.create(loader, true)
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX Bridge init failed: ${it.message}")
            }.getOrNull()

            if (result != null) {
                bridgeRef.set(result)
                XposedBridge.log("$LOG_PREFIX Bridge initialized with classLoader: $loader")
            } else {
                error("DexKitBridge init failed.")
            }
        }
    }

    private fun scheduleDelayedRelease() {
        if (releaseJob?.isActive == true) {
            XposedBridge.log("$LOG_PREFIX Delayed release already scheduled.")
            return
        }

        releaseJob = releaseScope.launch {
            delay(5000)
            if (usageCount.get() <= 0) {
                releaseBridge()
            } else {
                XposedBridge.log("$LOG_PREFIX Release cancelled: usageCount=${usageCount.get()}")
            }
        }

        XposedBridge.log("$LOG_PREFIX Scheduled delayed release in 5s")
    }

    private fun cancelPendingRelease() {
        releaseJob?.cancel()
        releaseJob = null
    }

    private fun releaseBridge() {
        if (!isReleasing.compareAndSet(false, true)) {
            XposedBridge.log("$LOG_PREFIX Already releasing, skip.")
            return
        }

        bridgeRef.getAndSet(null)?.let {
            runCatching {
                it.close()
                XposedBridge.log("$LOG_PREFIX Bridge released.")
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX Bridge release failed: ${it.message}")
            }
        }

        isReleasing.set(false)
    }
}
