package com.close.hook.ads.ui.activity

import android.content.Context
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

object ScopeManager {

    private const val TAG = "ScopeManager"

    @Volatile
    private var service: XposedService? = null
    private val serviceLock = Any()

    interface Callback {
        fun onApproved(packageName: String)
        fun onDenied(packageName: String)
        fun onFailed(packageName: String, message: String)
    }

    private suspend fun getService(): XposedService? {
        service?.let { return it }
        return suspendCancellableCoroutine { continuation ->
            synchronized(serviceLock) {
                service?.let {
                    continuation.resume(it)
                    return@synchronized
                }

                val listener = object : XposedServiceHelper.OnServiceListener {
                    override fun onServiceBind(service: XposedService) {
                        Log.d(TAG, "LSPosed service connected.")
                        ScopeManager.service = service
                        if (continuation.isActive) {
                            continuation.resume(service)
                        }
                    }

                    override fun onServiceDied(service: XposedService) {
                        Log.w(TAG, "LSPosed service died.")
                        ScopeManager.service = null
                    }
                }
                
                XposedServiceHelper.registerListener(listener)
            }
        }
    }

    /**
     * 异步获取作用域列表。
     */
    suspend fun getScope(): List<String>? = withContext(Dispatchers.IO) {
        return@withContext try {
            getService()?.scope
        } catch (e: Exception) {
            Log.e(TAG, "getScope failed", e)
            null
        }
    }

    /**
     * 请求作用域。
     */
    suspend fun requestScope(packageName: String, uiCallback: Callback) {
        withContext(Dispatchers.Main) {
            val service = getService()
            if (service == null) {
                uiCallback.onFailed(packageName, "LSPosed service not available.")
                return@withContext
            }

            val serviceCallback = object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(packageName: String) {
                    uiCallback.onApproved(packageName)
                }
                override fun onScopeRequestDenied(packageName: String) {
                    uiCallback.onDenied(packageName)
                }
                override fun onScopeRequestFailed(packageName: String, message: String) {
                    uiCallback.onFailed(packageName, message)
                }
            }

            try {
                service.requestScope(packageName, serviceCallback)
            } catch (e: Exception) {
                Log.e(TAG, "requestScope failed", e)
                uiCallback.onFailed(packageName, e.message ?: "Unknown error")
            }
        }
    }

    /**
     * 移除作用域。
     */
    suspend fun removeScope(packageName: String): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            getService()?.removeScope(packageName)
        } catch (e: Exception) {
            Log.e(TAG, "removeScope failed", e)
            e.message
        }
    }
}
