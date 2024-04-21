package com.close.hook.ads.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.Q
import android.os.IBinder
import android.os.MemoryFile
import android.os.ParcelFileDescriptor
import android.util.Log
import com.close.hook.ads.R
import com.close.hook.ads.BlockedBean
import com.close.hook.ads.IBlockedStatusProvider
import com.close.hook.ads.data.DataSource
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.io.FileDescriptor

class AidlService : Service() {

    private val dataSource by lazy { DataSource(this) }

    private val mStub: IBlockedStatusProvider.Stub = object : IBlockedStatusProvider.Stub() {
        override fun getData(type: String, value: String): ParcelFileDescriptor? {
            val blockedBean = dataSource.checkIsBlocked(type, value)
            return writeToMemoryFile(blockedBean)
        }
    }

    override fun onBind(intent: Intent): IBinder = mStub

    private fun writeToMemoryFile(blockedBean: BlockedBean): ParcelFileDescriptor? {
        var memoryFile: MemoryFile? = null
        try {
            val baos = ByteArrayOutputStream()
            ObjectOutputStream(baos).use { oos ->
                oos.writeObject(blockedBean)
                oos.flush()
            }
            val data = baos.toByteArray()

            Log.d("AidlService", "Size of the data to be written: ${data.size} bytes")

            memoryFile = MemoryFile("data_share", data.size).apply {
                writeBytes(data, 0, 0, data.size)
            }

            memoryFile.allowPurging(false)

            val getFileDescriptorMethod = MemoryFile::class.java.getDeclaredMethod("getFileDescriptor")
            getFileDescriptorMethod.isAccessible = true
            val rawFileDescriptor = getFileDescriptorMethod.invoke(memoryFile) as FileDescriptor

            return ParcelFileDescriptor.dup(rawFileDescriptor).also {
                memoryFile.close()
            }
        } catch (e: Exception) {
            Log.e("DataManager", "Error writing to MemoryFile", e)
            return null
        } finally {
            memoryFile?.close()
        }
    }

    override fun onCreate() {
        super.onCreate()
        startServiceForeground()
    }

    private fun startServiceForeground() {
        val channelID = "com.close.hook.ads.service"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelID, "AIDL Service Channel", NotificationManager.IMPORTANCE_HIGH)
        notificationManager.createNotificationChannel(channel)

        val notification = Notification.Builder(this, channelID)
            .setContentTitle("Foreground Service Running")
            .setContentText("Blocking ad requests")
            .setSmallIcon(R.drawable.notice_smallicon)
            .build()

        if (SDK_INT >= Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }
}
