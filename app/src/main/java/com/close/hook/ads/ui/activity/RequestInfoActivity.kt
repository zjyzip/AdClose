package com.close.hook.ads.ui.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.addCallback
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.os.BundleCompat
import com.close.hook.ads.R
import com.close.hook.ads.data.RequestDetailUpdates
import com.close.hook.ads.data.model.RequestInfo
import com.close.hook.ads.data.model.detailArguments
import com.close.hook.ads.hook.network.RequestHook
import com.close.hook.ads.ui.fragment.request.RequestInfoFragment
import com.close.hook.ads.util.OnBackPressContainer
import com.close.hook.ads.util.OnBackPressListener

class RequestInfoActivity : BaseActivity(), OnBackPressContainer {
    override var backController: OnBackPressListener? = null
    private var request: RequestInfo? = null
    private var receiverRegistered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val batch = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(RequestHook.REQUEST_BATCH, RequestInfo::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<RequestInfo>(RequestHook.REQUEST_BATCH)
            }
            val item = batch?.firstOrNull { value ->
                val current = request
                current != null && value.packageName == current.packageName && value.requestId == current.requestId
            } ?: IntentCompat.getParcelableExtra(intent, "request", RequestInfo::class.java) ?: return
            val current = request ?: return
            if (item.packageName == current.packageName && item.requestId == current.requestId) {
                showUpdate(RequestDetailUpdates.update(item))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_request_info)
        request = savedInstanceState?.let { BundleCompat.getParcelable(it, "requestInfo", RequestInfo::class.java) }
            ?: IntentCompat.getParcelableExtra(intent, "requestInfo", RequestInfo::class.java)
        request = request?.let(RequestDetailUpdates::current)
        request?.let {
            intent.putExtras(it.detailArguments())
            ContextCompat.registerReceiver(this, receiver, IntentFilter(RequestHook.REQUEST_ACTION), ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
        }
        if (savedInstanceState == null) {
            val fragment = RequestInfoFragment().apply { arguments = intent.extras }
            supportFragmentManager.beginTransaction().replace(R.id.fragment_container, fragment).commit()
        }
        onBackPressedDispatcher.addCallback(this) {
            if (backController?.onBackPressed() != true) finish()
        }
    }

    override fun onResume() {
        super.onResume()
        request?.let { showUpdate(RequestDetailUpdates.current(it)) }
    }

    private fun showUpdate(item: RequestInfo) {
        request = item
        intent.putExtras(item.detailArguments())
        (supportFragmentManager.findFragmentById(R.id.fragment_container) as? RequestInfoFragment)?.updateRequest(item)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        request?.let { outState.putParcelable("requestInfo", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(receiver)
        super.onDestroy()
    }
}
