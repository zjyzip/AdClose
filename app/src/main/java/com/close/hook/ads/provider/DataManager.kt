package com.close.hook.ads.provider

import android.net.Uri
import android.os.IBinder
import com.close.hook.ads.IBlockedStatusProvider
import com.close.hook.ads.closeApp
import com.close.hook.ads.data.DataSource
import com.close.hook.ads.BlockedBean

class DataManager private constructor() {
    private var cachedBinder: IBinder? = null

    private fun getBinder(): IBlockedStatusProvider? {
        if (cachedBinder != null) {
            return IBlockedStatusProvider.Stub.asInterface(cachedBinder)
        }
        return try {
            val bundle = closeApp.contentResolver.call(
                Uri.parse("content://com.close.hook.ads"),
                "getBinder",
                null,
                null
            )
            cachedBinder = bundle?.getBinder("binder")
            IBlockedStatusProvider.Stub.asInterface(cachedBinder)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getData(type: String, value: String): BlockedBean {
        val mBinder = getBinder()
        return mBinder?.getData(type, value) ?: BlockedBean(false, null, null)
    }

    companion object {
        @Volatile
        private var instance: DataManager? = null
        
        fun getInstance(): DataManager {
            return instance ?: synchronized(this) {
                instance ?: DataManager().also { instance = it }
            }
        }
        
        private var mBinder: IBlockedStatusProvider.Stub? = null

        @JvmStatic
        val serverStub: IBlockedStatusProvider.Stub?
            get() = mBinder ?: synchronized(this) {
                mBinder ?: object : IBlockedStatusProvider.Stub() {
                    override fun getData(type: String, value: String): BlockedBean {
                        return DataSource(closeApp).checkIsBlocked(type, value)
                    }
                }.also { mBinder = it }
            }
    }
}
