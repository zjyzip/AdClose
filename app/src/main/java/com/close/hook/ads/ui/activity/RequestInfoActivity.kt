package com.close.hook.ads.ui.activity

import android.os.Bundle
import com.close.hook.ads.R
import com.close.hook.ads.ui.fragment.request.RequestInfoFragment

class RequestInfoActivity : BaseActivity() {

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
    }
}
