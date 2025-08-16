package com.close.hook.ads.hook.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import com.close.hook.ads.data.model.LogEntry
import com.close.hook.ads.preference.HookPrefs
import com.close.hook.ads.service.ILoggerService
import de.robv.android.xposed.XposedBridge
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

object LogProxy {

    private enum class State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING
    }

    @Volatile
    private var loggerService: ILoggerService? = null
    private val logQueue = ConcurrentLinkedQueue<LogEntry>()
    private val state = AtomicReference(State.DISCONNECTED)
    private lateinit var currentPackageName: String

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (state.get() == State.DISCONNECTING) {
                return
            }
            loggerService = ILoggerService.Stub.asInterface(service)
            state.set(State.CONNECTED)
            log("LogProxy", "Logger service connected for $currentPackageName.")
            flushLogQueue()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            log("LogProxy", "Logger service disconnected for $currentPackageName.")
            loggerService = null
            state.set(State.DISCONNECTED)
        }
    }

    fun init(context: Context) {
        currentPackageName = context.packageName
        if (state.compareAndSet(State.DISCONNECTED, State.CONNECTING)) {
            val intent = Intent("com.close.hook.ads.ILoggerService").apply {
                setPackage("com.close.hook.ads")
            }
            try {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
                XposedBridge.log("LogProxy: Failed to bind to LogService: ${e.message}")
                state.set(State.DISCONNECTED)
            }
        }
    }

    fun log(tag: String, message: String, stackTrace: String? = null) {
        val logEntry = LogEntry(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            tag = tag,
            message = message,
            packageName = currentPackageName,
            stackTrace = stackTrace
        )

        if (state.get() == State.CONNECTED && loggerService != null) {
            try {
                loggerService?.log(logEntry)
            } catch (e: RemoteException) {
                XposedBridge.log("LogProxy: Failed to send log, re-queuing. Error: ${e.message}")
                logQueue.offer(logEntry)
            }
        } else {
            logQueue.offer(logEntry)
            if (state.get() == State.DISCONNECTED) {
                ContextUtil.applicationContext?.let { init(it) }
            }
        }
    }

    private fun flushLogQueue() {
        while (logQueue.isNotEmpty()) {
            val logEntry = logQueue.poll() ?: continue
            try {
                loggerService?.log(logEntry)
            } catch (e: RemoteException) {
                XposedBridge.log("LogProxy: Error flushing queue, re-queuing log. Error: ${e.message}")
                logQueue.offer(logEntry)
                break
            }
        }
    }

    fun disconnect(context: Context) {
        if (state.compareAndSet(State.CONNECTED, State.DISCONNECTING) || state.compareAndSet(State.CONNECTING, State.DISCONNECTING)) {
            try {
                context.unbindService(connection)
            } catch (e: Exception) {
                XposedBridge.log("LogProxy: Error unbinding service: ${e.message}")
            } finally {
                loggerService = null
                state.set(State.DISCONNECTED)
            }
        }
    }
}
