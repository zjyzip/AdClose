package com.close.hook.ads.ui.activity

import android.content.Context
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.close.hook.ads.R
import com.close.hook.ads.ui.fragment.request.RequestInfoFragment
import com.close.hook.ads.ui.fragment.request.RequestStackFragment
import com.close.hook.ads.ui.fragment.request.ResponseInfoFragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class RequestInfoActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_request_info)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            onBackPressed()
        }

        val method = intent.getStringExtra("method") ?: ""
        val urlString = intent.getStringExtra("urlString") ?: ""
        val requestHeaders = intent.getStringExtra("requestHeaders") ?: ""
        val responseCode = intent.getStringExtra("responseCode") ?: ""
        val responseMessage = intent.getStringExtra("responseMessage") ?: ""
        val responseHeaders = intent.getStringExtra("responseHeaders") ?: ""
        val stack = intent.getStringExtra("stack") ?: ""

        val sectionsPagerAdapter = SectionsPagerAdapter(
            this, supportFragmentManager, lifecycle,
            method, urlString, requestHeaders,
            responseCode, responseMessage, responseHeaders, stack
        )

        val viewPager: ViewPager2 = findViewById(R.id.view_pager)
        viewPager.adapter = sectionsPagerAdapter

        val tabs: TabLayout = findViewById(R.id.tabs)
        TabLayoutMediator(tabs, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Request"
                1 -> "Response"
                else -> "Stack"
            }
        }.attach()
    }

    class SectionsPagerAdapter(
        private val context: Context,
        fm: FragmentManager,
        lifecycle: Lifecycle,
        private val method: String,
        private val urlString: String,
        private val requestHeaders: String,
        private val responseCode: String,
        private val responseMessage: String,
        private val responseHeaders: String,
        private val stack: String
    ) : FragmentStateAdapter(fm, lifecycle) {
        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> RequestInfoFragment.newInstance(method, urlString, requestHeaders)
                1 -> ResponseInfoFragment.newInstance(responseCode, responseMessage, responseHeaders)
                else -> RequestStackFragment.newInstance(stack)
            }
        }
    }
}
