package com.close.hook.ads.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.close.hook.ads.R
import com.close.hook.ads.BlockedBean
import com.close.hook.ads.IBlockedStatusProvider
import com.close.hook.ads.data.DataSource
import com.close.hook.ads.ui.activity.MainActivity

class AidlService : Service() {

    private val dataSource by lazy { DataSource(this) }

    private val mStub = object : IBlockedStatusProvider.Stub() {
        override fun getData(type: String, value: String): BlockedBean {
            return dataSource.checkIsBlocked(type, value)
        }
    }

    override fun onBind(intent: Intent?): IBinder = mStub

    override fun onCreate() {
        super.onCreate()
        startServiceForeground()
    }

    override fun onDestroy() {
        stopForeground(true)
        super.onDestroy()
    }

    private fun startServiceForeground() {
        val channelID = "com.close.hook.ads.service"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelID,
                "AIDL Service Channel",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelID)
            .setContentTitle("AIDL Service Running")
            .setContentText("Blocking ad requests")
            .setSmallIcon(R.drawable.ad_block_notification_icon)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .build()

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                ContextCompat.startForegroundService(this, Intent(this, AidlService::class.java))
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
            else -> {
                startForeground(1, notification)
            }
        }
    }
}

