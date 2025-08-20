package com.close.hook.ads.service

import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

object ServiceManager {

    private const val TAG = "ServiceManager"

    @Volatile
    @JvmStatic
    var service: XposedService? = null
        private set

    @Volatile
    private var isInitialized = false
    private val lock = Any()

    /**
     * 在 Application 启动时调用，以异步方式绑定到 LSPosed 管理服务。
     *
     * 对应 XposedServiceHelper.registerListener()，
     * onServiceBind 成功后即可通过 `service` 实例与框架服务交互。
     */
    fun init() {
        synchronized(lock) {
            if (isInitialized) {
                return
            }

            val listener = object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(boundService: XposedService) {
                    Log.i(TAG, "LSPosed service connected: ${boundService.frameworkName} v${boundService.frameworkVersion}")
                    service = boundService
                }

                override fun onServiceDied(deadService: XposedService) {
                    if (service == deadService) {
                        Log.w(TAG, "LSPosed service died.")
                        service = null
                    }
                }
            }
            
            XposedServiceHelper.registerListener(listener)
            isInitialized = true
        }
    }
}
