package com.close.hook.ads.hook.util

import android.content.Context
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
    private const val LOG_PREFIX = "[DexKitUtil]"
    private val bridgeRef = AtomicReference<DexKitBridge?>()
    private val nativeLoaded = AtomicBoolean(false)
    private val usageCount = AtomicInteger(0)
    private val isReleasing = AtomicBoolean(false)
    private var releaseJob: Job? = null

    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val methodCache = CacheBuilder.newBuilder()
        .maximumSize(300)
        .expireAfterAccess(4, TimeUnit.HOURS)
        .softValues()
        .build<String, List<MethodData>>()

    val context: Context
        get() = ContextUtil.applicationContext
            ?: error("$LOG_PREFIX Context is not initialized")

    fun <T> withBridge(block: (DexKitBridge) -> T): T? {
        if (!AppUtils.isMainProcess(context)) {
            XposedBridge.log("$LOG_PREFIX Skipped in non-main process")
            return null
        }

        if (!ensureBridgeInitialized()) {
            XposedBridge.log("$LOG_PREFIX DexKitBridge init failed")
            return null
        }

        cancelPendingRelease()
        val count = usageCount.incrementAndGet()
        XposedBridge.log("$LOG_PREFIX usageCount++ => $count")

        return try {
            bridgeRef.get()?.let(block)
        } finally {
            val remaining = usageCount.updateAndGet { maxOf(it - 1, 0) }
            XposedBridge.log("$LOG_PREFIX usageCount-- => $remaining")
            if (remaining == 0) scheduleDelayedRelease()
        }
    }

    fun getCachedOrFindMethods(key: String, findLogic: () -> List<MethodData>?): List<MethodData> {
        return runCatching {
            methodCache.get(key) {
                val result = findLogic().orEmpty()
                XposedBridge.log("$LOG_PREFIX Cached $key -> ${result.size} methods")
                result
            }
        }.getOrElse {
            XposedBridge.log("$LOG_PREFIX Cache fail for $key: ${it.message}")
            emptyList()
        }
    }

    private fun ensureBridgeInitialized(): Boolean {
        if (!nativeLoaded.get()) {
            synchronized(nativeLoaded) {
                if (nativeLoaded.compareAndSet(false, true)) {
                    runCatching {
                        System.loadLibrary("dexkit")
                        XposedBridge.log("$LOG_PREFIX Native library loaded")
                    }.onFailure {
                        XposedBridge.log("$LOG_PREFIX Bridge init failed: ${it.message}")
                        nativeLoaded.set(false)
                        return false
                    }
                }
            }
        }

        if (bridgeRef.get() != null) return true

        synchronized(this) {
            if (bridgeRef.get() != null) return true

            val bridge = runCatching {
                createDexKitBridge(context)
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX Bridge init failed: ${it.message}")
            }.getOrNull()

            if (bridge != null) {
                bridgeRef.set(bridge)
                return true
            } else {
                XposedBridge.log("$LOG_PREFIX DexKitBridge init failed")
                return false
            }
        }
    }

    private fun createDexKitBridge(context: Context): DexKitBridge? {
        val loader = context.classLoader
        val loaderName = loader::class.java.name
        XposedBridge.log("$LOG_PREFIX Bridge initialized with classLoader: $loaderName ($loader)")

        return runCatching {
            when {
                loaderName.endsWith("PathClassLoader") -> {
                    DexKitBridge.create(loader, true)
                }
                else -> {
                    XposedBridge.log("$LOG_PREFIX Unknown ClassLoader: $loaderName, fallback to apkPath")
                    DexKitBridge.create(context.applicationInfo.sourceDir)
                }
            }
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX Bridge init failed: ${it.message}")
        }.getOrNull()
    }

    private fun scheduleDelayedRelease() {
        if (releaseJob?.isActive == true) {
            XposedBridge.log("$LOG_PREFIX Release already scheduled")
            return
        }

        releaseJob = releaseScope.launch {
            delay(5000)
            if (usageCount.get() <= 0) {
                releaseBridge()
            } else {
                XposedBridge.log("$LOG_PREFIX Release cancelled, usageCount=${usageCount.get()}")
            }
        }

        XposedBridge.log("$LOG_PREFIX Scheduled release in 5s")
    }

    private fun cancelPendingRelease() {
        releaseJob?.cancel()
        releaseJob = null
    }

    private fun releaseBridge() {
        if (!isReleasing.compareAndSet(false, true)) {
            XposedBridge.log("$LOG_PREFIX Already releasing, skipped")
            return
        }

        try {
            bridgeRef.getAndSet(null)?.let {
                runCatching {
                    it.close()
                    XposedBridge.log("$LOG_PREFIX Bridge released")
                }.onFailure {
                    XposedBridge.log("$LOG_PREFIX Release failed: ${it.message}")
                }
            }
        } finally {
            isReleasing.set(false)
        }
    }
}
