package com.close.hook.ads.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.close.hook.ads.data.repository.LogRepository
import com.close.hook.ads.service.ILoggerService
import com.close.hook.ads.data.model.LogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LogService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val binder = object : ILoggerService.Stub() {
        override fun log(entry: LogEntry?) {
            entry?.let {
                serviceScope.launch {
                    LogRepository.addLog(it)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
