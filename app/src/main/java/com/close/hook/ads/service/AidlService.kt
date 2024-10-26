package com.close.hook.ads.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.close.hook.ads.BlockedBean
import com.close.hook.ads.IBlockedStatusProvider
import com.close.hook.ads.IBlockedStatusCallback
import com.close.hook.ads.data.DataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AidlService : Service() {

    private val dataSource by lazy { DataSource(this) }

    private val mStub = object : IBlockedStatusProvider.Stub() {
        override fun getDataAsync(type: String, value: String, callback: IBlockedStatusCallback) {
            CoroutineScope(Dispatchers.IO).launch {
                val result = dataSource.checkIsBlocked(type, value)
                callback.onResult(result)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = mStub

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}
