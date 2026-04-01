package com.close.hook.ads.hook.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.close.hook.ads.preference.HookPrefs
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method

object PackageVisibilityHandler {

    private const val TAG = "PkgVisHandler"
    private const val PERM_QUERY_ALL = "android.permission.QUERY_ALL_PACKAGES"
    private const val ACTION_UPDATE = "com.close.hook.ads.ACTION_UPDATE_PKG_VISIBILITY"

    private val ENABLE_PREFIXES = arrayOf(
        "switch_one_", "switch_two_", "switch_three_", "switch_four_",
        "switch_five_", "switch_six_", "switch_seven_", "switch_eight_",
        "switch_nine_",
        "overall_hook_enabled_"
    )

    private val threadWakeLock = Object()

    fun init(xposed: XposedInterface) {
        xposed.log(Log.INFO, TAG, "Initializing Package Visibility Handler...")

        Thread {
            var appsFilterInstance: Any? = null
            var disabledPackagesInstance: Any? = null
            var cacheEnabledField: Field? = null
            var addMethod: Method? = null
            var clearMethod: Method? = null

            var lastTargetPackages: Set<String> = emptySet()
            var lastFeatureEnabled: Boolean? = null
            var isReceiverRegistered = false

            val updateReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == ACTION_UPDATE) {
                        xposed.log(Log.DEBUG, TAG, "Update signal received, waking up...")
                        synchronized(threadWakeLock) {
                            threadWakeLock.notifyAll()
                        }
                    }
                }
            }

            while (true) {
                try {
                    if (!isReceiverRegistered) {
                        getSystemContext()?.let { sysContext ->
                            try {
                                val filter = IntentFilter(ACTION_UPDATE)
                                if (Build.VERSION.SDK_INT >= 33) {
                                    sysContext.javaClass.getMethod("registerReceiver", 
                                        BroadcastReceiver::class.java, IntentFilter::class.java, Int::class.javaPrimitiveType)
                                        .invoke(sysContext, updateReceiver, filter, 2)
                                } else {
                                    sysContext.registerReceiver(updateReceiver, filter)
                                }
                                isReceiverRegistered = true
                                xposed.log(Log.INFO, TAG, "BroadcastReceiver registered successfully. System is ready.")
                            } catch (ignored: Throwable) {}
                        }
                    }

                    if (appsFilterInstance == null) {
                        val binder = getService("package")
                        val pms = if (binder != null) extractPMS(binder) else null
                        
                        if (pms != null) {
                            val appsFilter = getFieldValue(pms, "mAppsFilter")
                            if (appsFilter != null) {
                                val featureConfig = getFieldValue(appsFilter, "mFeatureConfig")
                                if (featureConfig != null) {
                                    xposed.log(Log.INFO, TAG, "--- Package Visibility Bypass Initialized ---")
                                    xposed.log(Log.INFO, TAG, "[Binder] ServiceManager 'package': ${binder?.javaClass?.name}")
                                    xposed.log(Log.INFO, TAG, "[PMS] Extracted instance: ${pms.javaClass.name}")
                                    xposed.log(Log.INFO, TAG, "[Filter] Located mAppsFilter instance: ${appsFilter.javaClass.name}")
                                    xposed.log(Log.INFO, TAG, "[Config] Located mFeatureConfig instance: ${featureConfig.javaClass.name}")

                                    appsFilterInstance = appsFilter
                                    cacheEnabledField = findField(appsFilter.javaClass, "mCacheEnabled")
                                    disabledPackagesInstance = getFieldValue(featureConfig, "mDisabledPackages")
                                    
                                    if (disabledPackagesInstance != null) {
                                        val arraySetClass = Class.forName("android.util.ArraySet")
                                        addMethod = arraySetClass.getMethod("add", Object::class.java)
                                        clearMethod = arraySetClass.getMethod("clear")
                                    }
                                }
                            }
                        }

                        if (appsFilterInstance == null) {
                            Thread.sleep(10000)
                            continue
                        }
                    }

                    HookPrefs.invalidateCaches()
                    val isFeatureEnabled = HookPrefs.getBoolean(HookPrefs.KEY_ENABLE_PACKAGE_VISIBILITY_BYPASS, false)

                    if (isFeatureEnabled) {
                        cacheEnabledField?.let { field ->
                            if (field.get(appsFilterInstance) as? Boolean != false) {
                                field.set(appsFilterInstance, false)
                            }
                        }

                        val targetPackages = buildTargetPackages()

                        if (lastFeatureEnabled != true || targetPackages != lastTargetPackages) {
                            disabledPackagesInstance?.let { dp ->
                                synchronized(dp) {
                                    clearMethod?.invoke(dp)
                                    targetPackages.forEach { pkg ->
                                        addMethod?.invoke(dp, pkg)
                                    }
                                }
                                lastTargetPackages = targetPackages
                                xposed.log(Log.INFO, TAG, "Whitelist updated: [${targetPackages.joinToString()}], total=${targetPackages.size}")
                            }
                        }
                    } else {
                        if (lastFeatureEnabled != false) {
                            cacheEnabledField?.let { field ->
                                if (field.get(appsFilterInstance) as? Boolean == false) {
                                    field.set(appsFilterInstance, true)
                                    disabledPackagesInstance?.let { dp ->
                                        synchronized(dp) { clearMethod?.invoke(dp) }
                                    }
                                    lastTargetPackages = emptySet()
                                    xposed.log(Log.INFO, TAG, "Bypass feature disabled. System settings restored.")
                                }
                            }
                        }
                    }
                    lastFeatureEnabled = isFeatureEnabled

                    if (isReceiverRegistered) {
                        synchronized(threadWakeLock) {
                            threadWakeLock.wait(60000L) 
                        }
                    } else {
                        Thread.sleep(5000)
                    }

                } catch (e: Throwable) {
                    xposed.log(Log.ERROR, TAG, "Daemon error: ${Log.getStackTraceString(e)}")
                    if (e is ReflectiveOperationException) appsFilterInstance = null
                    Thread.sleep(10000)
                }
            }
        }.apply {
            isDaemon = true
            name = "AdClose-PkgVisDaemon"
            start()
        }
    }

    private fun buildTargetPackages(): Set<String> {
        val allPrefs = HookPrefs.getAll()
        val systemPm = getSystemContext()?.packageManager
        val rawPackages = mutableSetOf<String>()

        allPrefs.forEach { (key, value) ->
            if (value == true) {
                for (prefix in ENABLE_PREFIXES) {
                    if (key.startsWith(prefix)) {
                        rawPackages.add(key.substring(prefix.length))
                        break
                    }
                }
            }
        }

        return if (systemPm != null) {
            rawPackages.filterTo(mutableSetOf()) { pkg ->
                systemPm.checkPermission(PERM_QUERY_ALL, pkg) != PackageManager.PERMISSION_GRANTED
            }
        } else {
            rawPackages
        }
    }

    private fun getSystemContext(): Context? {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = atClass.getMethod("currentActivityThread").invoke(null)
            atClass.getMethod("getSystemContext").invoke(at) as Context
        } catch (e: Exception) { null }
    }

    private fun getService(name: String): IBinder? {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val method = smClass.getDeclaredMethod("getService", String::class.java)
            method.invoke(null, name) as? IBinder
        } catch (e: Exception) { null }
    }

    private fun extractPMS(binder: IBinder): Any? {
        val className = binder.javaClass.name
        if (className.contains("PackageManagerService") && !className.contains("IPackageManagerImpl")) {
            return binder
        }
        getFieldValue(binder, "mService")?.let { return it }
        getFieldValue(binder, "this\$0")?.let { return it }
        return null
    }

    private fun getFieldValue(obj: Any, fieldName: String): Any? {
        val field = findField(obj.javaClass, fieldName) ?: return null
        return try { field.get(obj) } catch (e: Exception) { null }
    }

    private fun findField(clazz: Class<*>, fieldName: String): Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            try {
                return current.getDeclaredField(fieldName).also { it.isAccessible = true }
            } catch (e: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }
}
