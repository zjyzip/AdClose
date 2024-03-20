package com.close.hook.ads.ui.activity

import android.content.Context
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.close.hook.ads.R
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.close.hook.ads.ui.fragment.RequestInfoFragment
import com.close.hook.ads.ui.fragment.ResponseInfoFragment

class RequestInfoActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_request_info)

        val method = intent.getStringExtra("method") ?: ""
        val urlString = intent.getStringExtra("urlString") ?: ""
        val requestHeaders = intent.getStringExtra("requestHeaders") ?: ""
        val responseCode = intent.getStringExtra("responseCode") ?: ""
        val responseMessage = intent.getStringExtra("responseMessage") ?: ""
        val responseHeaders = intent.getStringExtra("responseHeaders") ?: ""

        val sectionsPagerAdapter = SectionsPagerAdapter(
            this, supportFragmentManager, lifecycle,
            method, urlString, requestHeaders,
            responseCode, responseMessage, responseHeaders
        )

        val viewPager: ViewPager2 = findViewById(R.id.view_pager)
        viewPager.adapter = sectionsPagerAdapter

        val tabs: TabLayout = findViewById(R.id.tabs)
        TabLayoutMediator(tabs, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Request"
                else -> "Response"
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
        private val responseHeaders: String
    ) : FragmentStateAdapter(fm, lifecycle) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> RequestInfoFragment.newInstance(method, urlString, requestHeaders)
                else -> ResponseInfoFragment.newInstance(responseCode, responseMessage, responseHeaders)
            }
        }
    }
}
