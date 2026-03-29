package com.close.hook.ads.manager

import android.util.Log
import com.close.hook.ads.preference.HookPrefs
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

object ServiceManager {

    private const val TAG = "ServiceManager"

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
    val connectionState = _connectionState.asStateFlow()

    private val isInitialized = AtomicBoolean(false)

    @JvmStatic
    val service: XposedService?
        get() = (connectionState.value as? ConnectionState.Connected)?.service

    @JvmStatic
    val isModuleActivated: Boolean
        get() = connectionState.value is ConnectionState.Connected

    fun init() {
        if (!isInitialized.compareAndSet(false, true)) {
            return
        }

        val listener = object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(boundService: XposedService) {
                var isFirstConnection = false
                _connectionState.update { currentState ->
                    if (currentState is ConnectionState.Connected) {
                        Log.w(TAG, "Already connected to ${currentState.service.frameworkName}. Ignoring ${boundService.frameworkName}.")
                        currentState
                    } else {
                        isFirstConnection = true
                        ConnectionState.Connected(boundService)
                    }
                }

                if (isFirstConnection) {
                    HookPrefs.invalidateCaches()
                    Log.i(TAG, "LSPosed service connected: ${boundService.frameworkName} v${boundService.frameworkVersion}")
                }
            }

            override fun onServiceDied(deadService: XposedService) {
                _connectionState.update { currentState ->
                    if (currentState is ConnectionState.Connected && currentState.service === deadService) {
                        Log.w(TAG, "LSPosed service (${deadService.frameworkName}) died.")
                        ConnectionState.Disconnected
                    } else {
                        currentState
                    }
                }
            }
        }
        
        XposedServiceHelper.registerListener(listener)
        Log.i(TAG, "ServiceManager initialized and listener registered.")
    }
}

sealed interface ConnectionState {
    data object Connecting : ConnectionState
    data class Connected(val service: XposedService) : ConnectionState
    data object Disconnected : ConnectionState
}
