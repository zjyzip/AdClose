package com.close.hook.ads.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.close.hook.ads.BlockedBean
import com.close.hook.ads.IBlockedStatusProvider
import com.close.hook.ads.data.DataSource

class AidlService : Service() {

    private val dataSource by lazy { DataSource(this) }

    private val mStub = object : IBlockedStatusProvider.Stub() {
        override fun getData(type: String, value: String): BlockedBean {
            return dataSource.checkIsBlocked(type, value)
        }
    }

    override fun onBind(intent: Intent?): IBinder = mStub
}
