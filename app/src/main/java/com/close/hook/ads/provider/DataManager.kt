package com.close.hook.ads.provider

import android.os.MemoryFile
import android.os.ParcelFileDescriptor
import com.close.hook.ads.CloseApplication
import com.close.hook.ads.data.DataSource
import com.close.hook.ads.BlockedBean
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.ObjectOutputStream
import android.util.Log

class DataManager private constructor(private val app: CloseApplication) {
    companion object {
        private var instance: DataManager? = null

        @Synchronized
        fun initialize(app: CloseApplication) {
            if (instance == null) {
                instance = DataManager(app)
            }
        }

        fun getInstance(): DataManager {
            return instance ?: throw IllegalStateException("DataManager is not initialized")
        }
    }

    fun getData(type: String, value: String): ParcelFileDescriptor? {
        val blockedBean = DataSource(app).checkIsBlocked(type, value)
        return writeToMemoryFile(blockedBean)
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

            val getFileDescriptorMethod = MemoryFile::class.java.getDeclaredMethod("getFileDescriptor")
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
