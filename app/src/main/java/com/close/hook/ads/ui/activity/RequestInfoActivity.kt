package com.close.hook.ads.ui.activity

import android.content.Context
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.close.hook.ads.R
import com.close.hook.ads.ui.fragment.request.DnsInfoFragment
import com.close.hook.ads.ui.fragment.request.RequestInfoFragment
import com.close.hook.ads.ui.fragment.request.RequestStackFragment
import com.close.hook.ads.ui.fragment.request.ResponseInfoFragment
import com.close.hook.ads.ui.fragment.request.ResponseBodyInfoFragment
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
        val responseBodyUriString = intent.getStringExtra("responseBody")
        val stack = intent.getStringExtra("stack") ?: ""
        val dnsHost = intent.getStringExtra("dnsHost")
        val fullAddress = intent.getStringExtra("fullAddress")

        val sectionsPagerAdapter = SectionsPagerAdapter(
            this, supportFragmentManager, lifecycle,
            method, urlString, requestHeaders,
            responseCode, responseMessage, responseHeaders, responseBodyUriString,
            stack, dnsHost, fullAddress
        )
        val viewPager: ViewPager2 = findViewById(R.id.view_pager)
        viewPager.adapter = sectionsPagerAdapter

        val tabs: TabLayout = findViewById(R.id.tabs)
        TabLayoutMediator(tabs, viewPager) { tab, position ->
            tab.text = sectionsPagerAdapter.getPageTitle(position)
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
        private val responseBodyUriString: String?,
        private val stack: String,
        private val dnsHost: String?,
        private val fullAddress: String?
    ) : FragmentStateAdapter(fm, lifecycle) {

        private val fragments = mutableListOf<Fragment>()
        private val fragmentTitles = mutableListOf<String>()

        init {
            if (!dnsHost.isNullOrEmpty()) {
                fragments.add(DnsInfoFragment.newInstance(dnsHost, fullAddress))
                fragmentTitles.add("DNS Info")
            }
            if (method.isNotEmpty() || urlString.isNotEmpty() || requestHeaders.isNotEmpty()) {
                fragments.add(RequestInfoFragment.newInstance(method, urlString, requestHeaders))
                fragmentTitles.add("Request")
            }
            if (responseCode.isNotEmpty() || responseMessage.isNotEmpty() || responseHeaders.isNotEmpty()) {
                fragments.add(ResponseInfoFragment.newInstance(responseCode, responseMessage, responseHeaders))
                fragmentTitles.add("Response")
            }
            if (!responseBodyUriString.isNullOrEmpty()) {
                fragments.add(ResponseBodyInfoFragment.newInstance(responseBodyUriString))
                fragmentTitles.add("Body")
            }
            if (stack.isNotEmpty()) {
                fragments.add(RequestStackFragment.newInstance(stack))
                fragmentTitles.add("Stack")
            }
        }

        override fun getItemCount(): Int = fragments.size

        override fun createFragment(position: Int): Fragment = fragments[position]

        fun getPageTitle(position: Int): String = fragmentTitles[position]
    }
}
