package com.close.hook.ads.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.MemoryFile
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import com.close.hook.ads.BlockedBean
import com.close.hook.ads.IBlockedStatusProvider
import com.close.hook.ads.data.DataSource
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.ObjectOutputStream

class AidlService : Service() {

    private val dataSource by lazy { DataSource(this) }

    private val mStub: IBlockedStatusProvider.Stub = object : IBlockedStatusProvider.Stub() {
        @Throws(RemoteException::class)
        override fun getData(type: String, value: String): ParcelFileDescriptor? {
            val blockedBean = dataSource.checkIsBlocked(type, value)
            return writeToMemoryFile(blockedBean)
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return mStub
    }

    private fun writeToMemoryFile(blockedBean: BlockedBean): ParcelFileDescriptor? {
        var memoryFile: MemoryFile? = null
        try {
            memoryFile = MemoryFile("data_share", 1024 * 1024)  // 1MB size
            memoryFile.allowPurging(false)
            ByteArrayOutputStream().use { baos ->
                ObjectOutputStream(baos).use { oos ->
                    oos.writeObject(blockedBean)
                    oos.flush()
                    val data = baos.toByteArray()
                    memoryFile.writeBytes(data, 0, 0, data.size)
                }
            }

            val getFileDescriptorMethod =
                MemoryFile::class.java.getDeclaredMethod("getFileDescriptor")
            getFileDescriptorMethod.isAccessible = true
            val rawFileDescriptor = getFileDescriptorMethod.invoke(memoryFile) as FileDescriptor
            return ParcelFileDescriptor.dup(rawFileDescriptor)
        } catch (e: Exception) {
            Log.e("DataManager", "Error writing to MemoryFile", e)
            return null
        } finally {
            memoryFile?.close()
        }
    }

}