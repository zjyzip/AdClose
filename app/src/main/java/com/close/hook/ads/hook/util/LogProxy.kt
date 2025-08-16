package com.close.hook.ads.hook.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.close.hook.ads.data.model.LogEntry
import com.close.hook.ads.service.ILoggerService
import de.robv.android.xposed.XposedBridge
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

object LogProxy {
    private var loggerService: ILoggerService? = null
    private var isBound = false
    private var currentPackageName: String = "unknown"

    private val logQueue = ConcurrentLinkedQueue<Triple<String, String, String?>>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            loggerService = ILoggerService.Stub.asInterface(service)
            isBound = true
            log("LogProxy", "Logger service connected for $currentPackageName.")
            flushLogQueue()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            log("LogProxy", "Logger service disconnected for $currentPackageName.")
            loggerService = null
            isBound = false
        }
    }

    fun init(context: Context) {
        if (isBound) return
        currentPackageName = context.packageName
        val intent = Intent("com.close.hook.ads.ILoggerService").apply {
            setPackage("com.close.hook.ads")
        }
        try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE or Context.BIND_ABOVE_CLIENT)
        } catch (e: Exception) {
            XposedBridge.log("LogProxy: Failed to bind to LogService: ${e.message}")
        }
    }

    fun log(tag: String, message: String, stackTrace: String? = null) {
        if (isBound && loggerService != null) {
            try {
                sendToService(tag, message, stackTrace)
            } catch (e: Exception) {
                isBound = false
                loggerService = null
                XposedBridge.log("LogProxy: Failed to load, re-queuing log. Error: ${e.message}")
                log(tag, message, stackTrace)
            }
        } else {
            logQueue.offer(Triple(tag, message, stackTrace))
        }
    }

    private fun flushLogQueue() {
        synchronized(logQueue) {
            while (logQueue.isNotEmpty()) {
                logQueue.poll()?.let { (tag, message, stackTrace) ->
                    try {
                        sendToService(tag, message, stackTrace)
                    } catch (e: Exception) {
                        XposedBridge.log("LogProxy: Error flushing queue: ${e.message}")
                    }
                }
            }
        }
    }

    private fun sendToService(tag: String, message: String, stackTrace: String?) {
        loggerService?.log(
            LogEntry(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                tag = tag,
                message = message,
                packageName = currentPackageName,
                stackTrace = stackTrace
            )
        )
    }
}
