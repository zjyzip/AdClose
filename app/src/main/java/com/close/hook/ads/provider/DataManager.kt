package com.close.hook.ads.provider

import android.net.Uri
import android.os.IBinder
import android.os.MemoryFile
import android.os.ParcelFileDescriptor
import com.close.hook.ads.IBlockedStatusProvider
import com.close.hook.ads.closeApp
import com.close.hook.ads.data.DataSource
import com.close.hook.ads.BlockedBean
import java.io.FileDescriptor
import java.lang.reflect.Method
import android.util.Log

class DataManager private constructor() {
    private var cachedBinder: IBinder? = null
    private var memoryFile: MemoryFile? = null
    private var parcelFileDescriptor: ParcelFileDescriptor? = null

    init {
        try {
            memoryFile = MemoryFile("data_share", 1024).apply { allowPurging(false) }
            val getFileDescriptorMethod = MemoryFile::class.java.getDeclaredMethod("getFileDescriptor")
            val rawFileDescriptor = getFileDescriptorMethod.invoke(memoryFile) as FileDescriptor
            parcelFileDescriptor = ParcelFileDescriptor.dup(rawFileDescriptor)
        } catch (e: Exception) {
            Log.e("DataManager", "Failed to create memory file or descriptor", e)
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

    fun getData(type: String, value: String): BlockedBean {
        return getBinder()?.getData(type, value) ?: BlockedBean(false, null, null)
    }

    fun getMemoryFileDescriptor(): ParcelFileDescriptor? = parcelFileDescriptor

    companion object {
        @Volatile
        private var instance: DataManager? = null

        fun getInstance(): DataManager = instance ?: synchronized(this) {
            instance ?: DataManager().also { instance = it }
        }

        val serverStub: IBlockedStatusProvider.Stub by lazy {
            object : IBlockedStatusProvider.Stub() {
                override fun getData(type: String, value: String): BlockedBean =
                    DataSource(closeApp).checkIsBlocked(type, value)

                override fun getMemoryFileDescriptor(): ParcelFileDescriptor? =
                    getInstance().getMemoryFileDescriptor()
            }
        }
    }
}
