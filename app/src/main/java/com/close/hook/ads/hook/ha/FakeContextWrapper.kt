package com.close.hook.ads.hook.ha

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

class FakeContextWrapper(base: Context) : ContextWrapper(base) {

    private val TAG = "FakeContextWrapper"

    override fun startService(service: Intent): ComponentName? {
        XposedBridge.log("$TAG | startService() called, returning null to block: ${service.component?.className}")
        return null
    }

    override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
        XposedBridge.log("$TAG | bindService() called, returning false to block: ${service.component?.className}")
        return false
    }

    override fun sendBroadcast(intent: Intent) {
        if (intent.action?.startsWith("com.google.android.gms") == true || intent.action?.contains("ad") == true) {
            XposedBridge.log("$TAG | sendBroadcast() called, dropping ad-related broadcast: ${intent.action}")
            return
        }
        super.sendBroadcast(intent)
    }

    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {
        if (filter?.countActions() == 1) {
            val action = filter.getAction(0)
            if (action?.contains("ad") == true) {
                XposedBridge.log("$TAG | registerReceiver() called, preventing registration for ad-related action: $action")
                return null
            }
        }
        return super.registerReceiver(receiver, filter)
    }

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        if (name.contains("ad", ignoreCase = true) || name.contains("sdk", ignoreCase = true)) {
            XposedBridge.log("$TAG | getSharedPreferences() called for ad-related name, returning a dummy SharedPreferences: $name")
            return DummySharedPreferences()
        }
        return super.getSharedPreferences(name, mode)
    }

    override fun getSystemService(name: String): Any? {
        if (name == Context.WINDOW_SERVICE || name == Context.CONNECTIVITY_SERVICE) {
            XposedBridge.log("$TAG | getSystemService() called for ${name}, returning real service.")
            return super.getSystemService(name)
        }
        if (name.contains("ad", ignoreCase = true)) {
            XposedBridge.log("$TAG | getSystemService() called for ad-related service, returning null: $name")
            return null
        }
        return super.getSystemService(name)
    }

    override fun getAssets(): AssetManager {
        return super.getAssets()
    }

    override fun getResources(): Resources {
        return super.getResources()
    }

    override fun getPackageManager(): PackageManager {
        return super.getPackageManager()
    }

    override fun getApplicationInfo(): ApplicationInfo {
        return super.getApplicationInfo()
    }

    override fun getContentResolver(): ContentResolver {
        return super.getContentResolver()
    }

    override fun openFileInput(name: String): FileInputStream {
        return super.openFileInput(name)
    }

    override fun openFileOutput(name: String, mode: Int): FileOutputStream {
        return super.openFileOutput(name, mode)
    }

    override fun getDir(name: String, mode: Int): File {
        return super.getDir(name, mode)
    }

    override fun getCacheDir(): File {
        return super.getCacheDir()
    }

    override fun getFilesDir(): File {
        return super.getFilesDir()
    }

    override fun getPackageName(): String {
        return super.getPackageName()
    }

    override fun getApplicationContext(): Context {
        return this
    }

    override fun getFileStreamPath(name: String): File {
        return super.getFileStreamPath(name)
    }

    override fun getExternalFilesDir(type: String?): File? {
        return super.getExternalFilesDir(type)
    }

    override fun getExternalCacheDir(): File? {
        return super.getExternalCacheDir()
    }
}

class DummySharedPreferences : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()
    override fun getString(key: String?, defValue: String?): String? = defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = defValue
    override fun getLong(key: String?, defValue: Long): Long = defValue
    override fun getFloat(key: String?, defValue: Float): Float = defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
    override fun contains(key: String?): Boolean = false
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = DummyEditor()

    class DummyEditor : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
        override fun remove(key: String?): SharedPreferences.Editor = this
        override fun clear(): SharedPreferences.Editor = this
        override fun commit(): Boolean = true
        override fun apply() {}
    }
}
