package com.close.hook.ads.data.repository

import android.content.Context
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.preference.HookPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class CustomHookRepository(context: Context) {

    companion object {
        private val _autoDetectResult = MutableStateFlow<List<CustomHookInfo>?>(null)
        val autoDetectResult: StateFlow<List<CustomHookInfo>?> = _autoDetectResult.asStateFlow()

        fun publishAutoDetectResult(hooks: List<CustomHookInfo>) {
            _autoDetectResult.value = hooks
        }

        fun clearAutoDetectResult() {
            _autoDetectResult.value = null
        }
    }

    suspend fun getHookConfigs(packageName: String?): List<CustomHookInfo> = withContext(Dispatchers.IO) {
        HookPrefs.getCustomHookConfigs(packageName)
    }

    suspend fun saveHookConfigs(packageName: String?, configs: List<CustomHookInfo>) = withContext(Dispatchers.IO) {
        HookPrefs.setCustomHookConfigs(packageName, configs)
    }

    suspend fun getHookEnabledStatus(packageName: String?): Boolean = withContext(Dispatchers.IO) {
        HookPrefs.getOverallHookEnabled(packageName)
    }

    suspend fun setHookEnabledStatus(packageName: String?, isEnabled: Boolean) = withContext(Dispatchers.IO) {
        HookPrefs.setOverallHookEnabled(packageName, isEnabled)
    }
}
