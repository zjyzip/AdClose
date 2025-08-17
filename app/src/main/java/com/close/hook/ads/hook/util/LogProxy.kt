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
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object LogProxy {

    private enum class State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING
    }

    private const val BATCH_SIZE = 20
    private const val FLUSH_INTERVAL_SECONDS = 1L

    @Volatile
    private var loggerService: ILoggerService? = null
    private val logQueue = ConcurrentLinkedQueue<LogEntry>()
    private val state = AtomicReference(State.DISCONNECTED)
    private val flushScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    @Volatile private var currentPackageName: String? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (state.get() == State.DISCONNECTING) return
            
            loggerService = ILoggerService.Stub.asInterface(service)
            state.set(State.CONNECTED)
            XposedBridge.log("LogProxy: Logger service connected for ${currentPackageName ?: "unknown"}.")
            flushQueueAsync()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            XposedBridge.log("LogProxy: Logger service disconnected for ${currentPackageName ?: "unknown"}.")
            loggerService = null
            state.set(State.DISCONNECTED)
        }
    }

    fun init(context: Context) {
        synchronized(this) {
            if (currentPackageName != null) return
            currentPackageName = context.packageName

            flushScheduler.scheduleAtFixedRate(
                { flushQueueAsync() },
                FLUSH_INTERVAL_SECONDS,
                FLUSH_INTERVAL_SECONDS,
                TimeUnit.SECONDS
            )
        }

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
        val pkgName = currentPackageName ?: return

        logQueue.offer(LogEntry(
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
            tag, message, pkgName, stackTrace
        ))

        if (logQueue.size >= BATCH_SIZE) {
            flushQueueAsync()
        }
    }

    private fun flushQueueAsync() {
        if (logQueue.isEmpty() || state.get() != State.CONNECTED) {
            return
        }

        flushScheduler.execute {
            val batch = generateSequence { logQueue.poll() }.toList()

            if (batch.isNotEmpty()) {
                try {
                    loggerService?.logBatch(batch)
                } catch (e: RemoteException) {
                    XposedBridge.log("LogProxy: Failed to send batch, re-queuing. Error: ${e.message}")
                    logQueue.addAll(batch)
                }
            }
        }
    }

    fun disconnect(context: Context) {
        if (state.compareAndSet(State.CONNECTED, State.DISCONNECTING) || state.compareAndSet(State.CONNECTING, State.DISCONNECTING)) {
            flushQueueAsync()
            flushScheduler.shutdown()
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
