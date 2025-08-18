package com.close.hook.ads.hook.ha

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Handler
import com.close.hook.ads.hook.util.LogProxy
import java.io.File

class SmartFakeContext(realApplicationContext: Context) : ContextWrapper(realApplicationContext) {

    private companion object {
        private const val TAG = "SmartFakeContext"
        private val AD_ACTIVITY_KEYWORDS = setOf("ad", "ads", "advert", "splash", "gdt", "tt", "csj", "ksad")
    }

    private inner class FakeResources(realRes: Resources) :
        Resources(realRes.assets, realRes.displayMetrics, realRes.configuration) {
        private val adResourceKeywords = setOf("ad", "ads", "gdt", "tt_", "csj_", "ksad", "splash")
        override fun getIdentifier(name: String?, defType: String?, defPackage: String?): Int {
            val resourceName = name.orEmpty().lowercase()
            if (adResourceKeywords.any { resourceName.contains(it) }) {
                LogProxy.log(TAG, "Blocked resource lookup: name=$name, type=$defType, package=$defPackage")
                return 0
            }
            return super.getIdentifier(name, defType, defPackage)
        }
    }

    private val allFakePrefs = mutableMapOf<String, MutableMap<String, Any?>>()
    private val fakeResources by lazy { FakeResources(super.getResources()) }
    private val devNullFile by lazy {
        LogProxy.log(TAG, "Redirecting all file operations to a '/dev/null' black hole.")
        File("/dev/null")
    }

    override fun getSystemService(name: String): Any? {
        return when (name) {
            Context.CONNECTIVITY_SERVICE,
            Context.WIFI_SERVICE,
            Context.TELEPHONY_SERVICE,
            Context.LOCATION_SERVICE,
            Context.CLIPBOARD_SERVICE,
            Context.ACCOUNT_SERVICE,
            Context.WINDOW_SERVICE,
            Context.DOWNLOAD_SERVICE,
            Context.STORAGE_SERVICE -> {
                LogProxy.log(TAG, "Blocked getSystemService for critical service: $name")
                null
            }
            else -> super.getSystemService(name)
        }
    }

    override fun checkPermission(permission: String, pid: Int, uid: Int) = PackageManager.PERMISSION_DENIED
    override fun checkCallingOrSelfPermission(permission: String) = PackageManager.PERMISSION_DENIED

    override fun getFilesDir(): File = devNullFile
    override fun getCacheDir(): File = devNullFile
    override fun getDir(name: String?, mode: Int): File = devNullFile
    override fun openFileInput(name: String?) = null
    override fun openFileOutput(name: String?, mode: Int) = null

    override fun getContentResolver(): ContentResolver? = null
    override fun getResources(): Resources = fakeResources

    override fun startActivity(intent: Intent) {
        val targetComponent = intent.component?.className.orEmpty().lowercase()
        if (AD_ACTIVITY_KEYWORDS.any { targetComponent.contains(it) }) {
            LogProxy.log(TAG, "Blocked startActivity to a potential ad activity: $targetComponent")
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            super.startActivity(intent)
        }
    }

    private fun blockReceiverRegistration(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {
        LogProxy.log(TAG, "Blocked registerReceiver for receiver: $receiver with filter: $filter")
        return null
    }

    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? =
        blockReceiverRegistration(receiver, filter)

    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?, flags: Int): Intent? =
        blockReceiverRegistration(receiver, filter)

    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?, broadcastPermission: String?, scheduler: Handler?): Intent? =
        blockReceiverRegistration(receiver, filter)

    override fun getClassLoader(): ClassLoader = object : ClassLoader() {}

    override fun getPackageName(): String = "com.android.vending"

    override fun getApplicationInfo(): ApplicationInfo {
        return super.getApplicationInfo().apply {
            dataDir = devNullFile.absolutePath
            nativeLibraryDir = "/dev/null"
        }
    }

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        LogProxy.log(TAG, "Intercepted getSharedPreferences for '$name'. Providing an in-memory, non-persistent instance.")
        return FakeSharedPreferences(name, allFakePrefs)
    }

    private class FakeSharedPreferences(
        private val name: String,
        private val allPrefs: MutableMap<String, MutableMap<String, Any?>>
    ) : SharedPreferences {

        private val storage: MutableMap<String, Any?>
            get() = allPrefs.getOrPut(name) { mutableMapOf() }

        private inner class FakeEditor : SharedPreferences.Editor {
            private val edits = mutableMapOf<String, Any?>()
            private var shouldClear = false
            private val REMOVE_SENTINEL = Any()

            override fun putString(key: String, value: String?) = apply { edits[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { edits[key] = values }
            override fun putInt(key: String, value: Int) = apply { edits[key] = value }
            override fun putLong(key: String, value: Long) = apply { edits[key] = value }
            override fun putFloat(key: String, value: Float) = apply { edits[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { edits[key] = value }
            override fun remove(key: String) = apply { edits[key] = REMOVE_SENTINEL }
            override fun clear() = apply { shouldClear = true }

            override fun commit(): Boolean {
                applyChanges()
                return true
            }

            override fun apply() {
                applyChanges()
            }

            private fun applyChanges() {
                synchronized(this@FakeSharedPreferences) {
                    if (shouldClear) {
                        storage.clear()
                        shouldClear = false
                    }
                    edits.forEach { (key, value) ->
                        if (value == REMOVE_SENTINEL) {
                            storage.remove(key)
                        } else {
                            storage[key] = value
                        }
                    }
                    edits.clear()
                }
            }
        }

        override fun edit(): SharedPreferences.Editor = FakeEditor()
        override fun getAll(): MutableMap<String, *> = synchronized(this) { storage.toMutableMap() }
        override fun getString(key: String, defValue: String?): String? = storage[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = storage[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String, defValue: Int): Int = storage[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = storage[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = storage[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = storage[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = storage.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { /* no-op */ }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { /* no-op */ }
    }
}
