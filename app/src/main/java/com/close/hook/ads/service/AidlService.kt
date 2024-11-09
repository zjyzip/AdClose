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
        val serviceIntent = Intent(this, AidlService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startServiceForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        stopForeground(true)
        super.onDestroy()
    }

    private fun startServiceForeground() {
        val channelID = "com.close.hook.ads.service"
        createNotificationChannel(channelID)

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }

    private fun createNotificationChannel(channelID: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                channelID,
                "AIDL Service Channel",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }
    }
}
