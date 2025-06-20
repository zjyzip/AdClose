package com.close.hook.ads.util

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.widget.Toast

object AppUtils {

    fun showToast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun getAppName(context: Context, packageName: String): CharSequence {
        val pm = context.packageManager
        return try {
            val appInfo: ApplicationInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo)
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    fun isMainProcess(context: Context): Boolean {
        val pid = Process.myPid()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName == context.packageName
            ?: false
    }

    fun showHookTip(context: Context, packageName: String) {
        showToast(context, "AdClose Hooking into ${getAppName(context, packageName)}")
    }
}
