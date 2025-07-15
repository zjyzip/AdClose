package com.close.hook.ads.data.repository

import android.content.Context
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.preference.HookPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CustomHookRepository(context: Context) {

    private val hookPrefs = HookPrefs.getInstance(context)

    suspend fun getHookConfigs(packageName: String?): List<CustomHookInfo> = withContext(Dispatchers.IO) {
        hookPrefs.getCustomHookConfigs(packageName)
    }

    suspend fun saveHookConfigs(packageName: String?, configs: List<CustomHookInfo>) = withContext(Dispatchers.IO) {
        hookPrefs.setCustomHookConfigs(packageName, configs)
    }

    suspend fun getHookEnabledStatus(packageName: String?): Boolean = withContext(Dispatchers.IO) {
        hookPrefs.getOverallHookEnabled(packageName)
    }

    suspend fun setHookEnabledStatus(packageName: String?, isEnabled: Boolean) = withContext(Dispatchers.IO) {
        hookPrefs.setOverallHookEnabled(packageName, isEnabled)
    }
}
