package com.close.hook.ads.ui.activity

import android.os.Bundle
import androidx.activity.addCallback
import com.close.hook.ads.R
import com.close.hook.ads.ui.adapter.RequestListAdapter
import com.close.hook.ads.ui.fragment.request.RequestInfoFragment
import com.close.hook.ads.util.OnBackPressContainer
import com.close.hook.ads.util.OnBackPressListener
import com.google.android.material.transition.platform.MaterialContainerTransform
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback

class RequestInfoActivity : BaseActivity(), OnBackPressContainer {

    override var backController: OnBackPressListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Activity-level Material Container Transform: the list card morphs into
        // the detail surface. Must be wired before super.onCreate so the window
        // captures the entering transition.
        setEnterSharedElementCallback(MaterialContainerTransformSharedElementCallback())
        window.sharedElementEnterTransition = MaterialContainerTransform().apply {
            addTarget(android.R.id.content)
            duration = ENTER_DURATION_MS
            fadeMode = MaterialContainerTransform.FADE_MODE_THROUGH
        }
        window.sharedElementReturnTransition = MaterialContainerTransform().apply {
            addTarget(android.R.id.content)
            duration = RETURN_DURATION_MS
            fadeMode = MaterialContainerTransform.FADE_MODE_THROUGH
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_request_info)

        findViewById<android.view.View>(android.R.id.content).transitionName =
            RequestListAdapter.SHARED_CARD_NAME

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
            finishAfterTransition()
        }
    }

    companion object {
        private const val ENTER_DURATION_MS = 350L
        private const val RETURN_DURATION_MS = 300L
    }
}
