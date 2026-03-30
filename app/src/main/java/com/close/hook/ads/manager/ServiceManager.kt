package com.close.hook.ads.manager

import android.util.Log
import com.close.hook.ads.preference.HookPrefs
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

object ServiceManager {

    private const val TAG = "ServiceManager"

    private const val CONNECT_TIMEOUT_MS = 1500L

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
    val connectionState = _connectionState.asStateFlow()

    private val isInitialized = AtomicBoolean(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
                var alreadyConnectedFramework: String? = null
                _connectionState.update { currentState ->
                    if (currentState is ConnectionState.Connected) {
                        isFirstConnection = false
                        alreadyConnectedFramework = currentState.service.frameworkName
                        currentState
                    } else {
                        isFirstConnection = true
                        ConnectionState.Connected(boundService)
                    }
                }
                if (isFirstConnection) {
                    HookPrefs.invalidateCaches()
                    Log.i(TAG, "LSPosed service connected: ${boundService.frameworkName} v${boundService.frameworkVersion}")
                } else {
                    Log.w(TAG, "Already connected to $alreadyConnectedFramework. Ignoring ${boundService.frameworkName}.")
                }
            }

            override fun onServiceDied(deadService: XposedService) {
                var isDisconnected = false
                _connectionState.update { currentState ->
                    if (currentState is ConnectionState.Connected && currentState.service === deadService) {
                        isDisconnected = true
                        ConnectionState.Disconnected
                    } else {
                        isDisconnected = false
                        currentState
                    }
                }
                if (isDisconnected) {
                    Log.w(TAG, "LSPosed service (${deadService.frameworkName}) died.")
                }
            }
        }

        XposedServiceHelper.registerListener(listener)

        scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            _connectionState.update { currentState ->
                if (currentState is ConnectionState.Connecting) {
                    Log.i(TAG, "Service binding timed out, module is likely not activated.")
                    ConnectionState.Disconnected
                } else {
                    currentState
                }
            }
        }

        Log.i(TAG, "ServiceManager initialized and listener registered.")
    }
}

sealed interface ConnectionState {
    data object Connecting : ConnectionState
    data class Connected(val service: XposedService) : ConnectionState
    data object Disconnected : ConnectionState
}
