package com.close.hook.ads.provider

import android.net.Uri
import android.os.IBinder
import android.os.MemoryFile
import android.os.ParcelFileDescriptor
import com.close.hook.ads.IBlockedStatusProvider
import com.close.hook.ads.closeApp
import com.close.hook.ads.data.DataSource
import com.close.hook.ads.BlockedBean
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.ObjectOutputStream
import android.util.Log
import java.lang.reflect.Method

class DataManager private constructor() {
    private var cachedBinder: IBinder? = null

    fun getData(type: String, value: String): ParcelFileDescriptor? {
        val blockedBean = DataSource(closeApp).checkIsBlocked(type, value)
        return writeToMemoryFile(blockedBean).also {
            if (it == null) {
                Log.e("DataManager", "Failed to create ParcelFileDescriptor for MemoryFile.")
            }
        }
    }

    private fun writeToMemoryFile(blockedBean: BlockedBean): ParcelFileDescriptor? {
        var memoryFile: MemoryFile? = null
        try {
            memoryFile = MemoryFile("data_share", 1024 * 1024) // 1MB size
            memoryFile.allowPurging(false)
            ByteArrayOutputStream().use { baos ->
                ObjectOutputStream(baos).use { oos ->
                    oos.writeObject(blockedBean)
                    oos.flush()
                    val data = baos.toByteArray()
                    memoryFile.writeBytes(data, 0, 0, data.size)
                }
            }

            val getFileDescriptorMethod = MemoryFile::class.java.getDeclaredMethod("getFileDescriptor")
            getFileDescriptorMethod.isAccessible = true
            val rawFileDescriptor = getFileDescriptorMethod.invoke(memoryFile) as FileDescriptor
            return ParcelFileDescriptor.dup(rawFileDescriptor)
        } catch (e: Exception) {
            Log.e("DataManager", "Error creating/writing MemoryFile", e)
            return null
        } finally {
            memoryFile?.close()
        }
    }

    private fun getBinder(): IBlockedStatusProvider? {
        cachedBinder?.let { return IBlockedStatusProvider.Stub.asInterface(it) }
        val bundle = closeApp.contentResolver.call(
            Uri.parse("content://com.close.hook.ads"),
            "getBinder",
            null,
            null
        )
        cachedBinder = bundle?.getBinder("binder")
        return cachedBinder?.let { IBlockedStatusProvider.Stub.asInterface(it) }
    }

    companion object {
        @Volatile
        private var instance: DataManager? = null

        fun getInstance(): DataManager = instance ?: synchronized(this) {
            instance ?: DataManager().also { instance = it }
        }

        val serverStub: IBlockedStatusProvider.Stub by lazy {
            object : IBlockedStatusProvider.Stub() {
                override fun getData(type: String, value: String): ParcelFileDescriptor {
                    val blockedBean = DataSource(closeApp).checkIsBlocked(type, value)
                    return DataManager.getInstance().writeToMemoryFile(blockedBean) ?: throw RuntimeException("Failed to write data to MemoryFile")
                }
            }
        }
    }
}
