package com.close.hook.ads.hook.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import com.close.hook.ads.data.model.LogEntry
import com.close.hook.ads.service.ILoggerService
import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object LogProxy {

    private enum class State { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

    private const val BATCH_SIZE = 500
    private const val FLUSH_INTERVAL_MS = 1000L
    private const val CHANNEL_CAPACITY = 1000

    @Volatile
    private var loggerService: ILoggerService? = null
    
    private val logChannel = Channel<LogEntry>(CHANNEL_CAPACITY, BufferOverflow.DROP_OLDEST)
    
    private val state = AtomicReference(State.DISCONNECTED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isInitialized = AtomicBoolean(false)

    @Volatile
    private var currentPackageName: String? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (state.get() == State.DISCONNECTING) return

            loggerService = ILoggerService.Stub.asInterface(service)
            state.set(State.CONNECTED)
            XposedBridge.log("LogProxy: Logger service connected for ${currentPackageName ?: "unknown"}.")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            XposedBridge.log("LogProxy: Logger service disconnected for ${currentPackageName ?: "unknown"}.")
            loggerService = null
            state.set(State.DISCONNECTED)
        }
    }

    fun init(context: Context) {
        if (!isInitialized.compareAndSet(false, true)) {
            return
        }
        currentPackageName = context.packageName

        scope.launch {
            while (isActive) {
                flushQueue()
                delay(FLUSH_INTERVAL_MS)
            }
        }

        bindToService(context)
    }

    private fun bindToService(context: Context) {
        if (state.compareAndSet(State.DISCONNECTED, State.CONNECTING)) {
            val intent = Intent("com.close.hook.ads.ILoggerService").apply {
                setPackage("com.close.hook.ads")
            }
            try {
                val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                if (!bound) {
                    XposedBridge.log("LogProxy: bindService returned false.")
                    state.set(State.DISCONNECTED)
                }
            } catch (e: Exception) {
                XposedBridge.log("LogProxy: Failed to bind to LogService: ${e.message}")
                state.set(State.DISCONNECTED)
            }
        }
    }

    fun log(tag: String, message: String, stackTrace: String? = null) {
        if (!isInitialized.get()) return
        val pkgName = currentPackageName ?: return

        val entry = LogEntry(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            tag = tag,
            message = message,
            packageName = pkgName,
            stackTrace = stackTrace
        )
        logChannel.trySend(entry)
    }

    private suspend fun flushQueue() {
        if (state.get() != State.CONNECTED || logChannel.isEmpty) {
            return
        }

        val batch = mutableListOf<LogEntry>()
        while (batch.size < BATCH_SIZE && !logChannel.isEmpty) {
            logChannel.tryReceive().getOrNull()?.let { batch.add(it) }
        }

        if (batch.isNotEmpty()) {
            try {
                loggerService?.logBatch(batch)
            } catch (e: RemoteException) {
                XposedBridge.log("LogProxy: Failed to send log batch. Error: ${e.message}")
            }
        }
    }

    fun disconnect(context: Context) {
        if (state.get() == State.DISCONNECTED || state.get() == State.DISCONNECTING) {
            return
        }
        state.set(State.DISCONNECTING)

        scope.launch {
            flushQueue()

            try {
                context.unbindService(connection)
            } catch (e: Exception) {
                XposedBridge.log("LogProxy: Error unbinding service: ${e.message}")
            }
            
            shutdown()
        }
    }

    private fun shutdown() {
        scope.cancel()

        while (!logChannel.isEmpty) {
            logChannel.tryReceive()
        }
        logChannel.close()

        loggerService = null
        currentPackageName = null
        state.set(State.DISCONNECTED)
        isInitialized.set(false)
        XposedBridge.log("LogProxy: Shutdown complete.")
    }
}
