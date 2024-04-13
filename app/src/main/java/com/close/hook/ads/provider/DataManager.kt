package com.close.hook.ads.provider

import android.net.Uri
import com.close.hook.ads.IMyAidlInterface
import com.close.hook.ads.closeApp
import com.close.hook.ads.data.DataSource
import com.close.hook.ads.BlockedBean

// mod from https://github.com/King-i-Yu/ContentProviderDemo
class DataManager {

    fun getData(type: String, value: String): BlockedBean {
        return try {
            val bundle = closeApp.contentResolver.call(
                Uri.parse("content://com.close.hook.ads"),
                "getBinder",
                null,
                null
            )
            val mBinder: IMyAidlInterface =
                IMyAidlInterface.Stub.asInterface(bundle?.getBinder("binder"))
            mBinder.getData(type, value)
        } catch (e: Exception) {
            e.printStackTrace()
            throw RuntimeException(e)
        }
    }

    companion object {
        var instance: DataManager? = null
            get() {
                if (field == null) {
                    synchronized(DataManager::class.java) {
                        if (field == null) {
                            field = DataManager()
                        }
                    }
                }
                return field
            }
            private set
        private var mBinder: IMyAidlInterface.Stub? = null

        @JvmStatic
        val serverStub: IMyAidlInterface.Stub?
            get() {
                if (mBinder == null) {
                    mBinder = object : IMyAidlInterface.Stub() {

                        override fun getData(type: String, value: String): BlockedBean {
                            return DataSource(closeApp).checkIsBlocked(type, value)
                        }

                    }
                }
                return mBinder
            }
    }
}
