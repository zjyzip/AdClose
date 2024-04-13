package com.close.hook.ads.provider

import android.net.Uri
import com.close.hook.ads.IBlockedStatusProvider
import com.close.hook.ads.closeApp
import com.close.hook.ads.data.DataSource
import com.close.hook.ads.BlockedBean

// mod from https://github.com/King-i-Yu/ContentProviderDemo
class DataManager private constructor() {

    fun getData(type: String, value: String): BlockedBean {
        return try {
            val bundle = closeApp.contentResolver.call(
                Uri.parse("content://com.close.hook.ads"),
                "getBinder",
                null,
                null
            )
            val mBinder = IBlockedStatusProvider.Stub.asInterface(bundle?.getBinder("binder"))
            mBinder.getData(type, value)
        } catch (e: Exception) {
            e.printStackTrace()
            BlockedBean(false, null, null)
        }
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
