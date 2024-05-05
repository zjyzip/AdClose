package com.close.hook.ads.service

import android.app.Service
import android.content.Intent
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

    private val mStub = object : IBlockedStatusProvider.Stub() {
        override fun getData(type: String, value: String): ParcelFileDescriptor? {
            val blockedBean = dataSource.checkIsBlocked(type, value)
            return writeToMemoryFile(blockedBean)
        }
    }

    override fun onBind(intent: Intent?): IBinder = mStub

    private fun writeToMemoryFile(blockedBean: BlockedBean): ParcelFileDescriptor? {
        var memoryFile: MemoryFile? = null
        return try {
            val baos = ByteArrayOutputStream()
            ObjectOutputStream(baos).use { it.writeObject(blockedBean) }
            val data = baos.toByteArray()

            Log.d("AidlService", "Size of the data to be written: ${data.size} bytes")

            memoryFile = MemoryFile("data_share", data.size).apply {
                writeBytes(data, 0, 0, data.size)
                allowPurging(false)
            }

            val getFileDescriptorMethod = MemoryFile::class.java.getDeclaredMethod("getFileDescriptor")
            getFileDescriptorMethod.isAccessible = true
            val rawFileDescriptor = getFileDescriptorMethod.invoke(memoryFile) as FileDescriptor

            ParcelFileDescriptor.dup(rawFileDescriptor).also { memoryFile.close() }
        } catch (e: Exception) {
            Log.e("DataManager", "Error writing to MemoryFile", e)
            null
        } finally {
            memoryFile?.close()
        }
    }

}
