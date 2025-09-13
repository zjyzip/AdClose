package com.close.hook.ads.ui.activity

import android.os.Bundle
import androidx.activity.addCallback
import com.close.hook.ads.R
import com.close.hook.ads.ui.fragment.request.RequestInfoFragment
import com.close.hook.ads.util.OnBackPressContainer
import com.close.hook.ads.util.OnBackPressListener

class RequestInfoActivity : BaseActivity(), OnBackPressContainer {

    override var backController: OnBackPressListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_request_info)

        if (savedInstanceState == null) {
            val fragment = RequestInfoFragment().apply {
                arguments = intent.extras
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit()
        }

        onBackPressedDispatcher.addCallback(this) {
            if (backController?.onBackPressed() == true) {
                return@addCallback
            }
            finish()
        }
    }
}
