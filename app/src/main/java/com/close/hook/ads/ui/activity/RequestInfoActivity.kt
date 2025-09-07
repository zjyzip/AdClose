package com.close.hook.ads.ui.activity

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.close.hook.ads.R
import com.close.hook.ads.ui.fragment.request.RequestInfoFragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class RequestInfoActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_request_info)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        val method = intent.getStringExtra("method") ?: ""
        val urlString = intent.getStringExtra("urlString") ?: ""
        val requestHeaders = intent.getStringExtra("requestHeaders") ?: ""
        val requestBodyUriString = intent.getStringExtra("requestBodyUriString")
        val responseCode = intent.getStringExtra("responseCode") ?: ""
        val responseMessage = intent.getStringExtra("responseMessage") ?: ""
        val responseHeaders = intent.getStringExtra("responseHeaders") ?: ""
        val responseBodyUriString = intent.getStringExtra("responseBodyUriString")
        val stack = intent.getStringExtra("stack") ?: ""
        val dnsHost = intent.getStringExtra("dnsHost")
        val fullAddress = intent.getStringExtra("fullAddress")

        val sectionsPagerAdapter = SectionsPagerAdapter(
            this,
            method, urlString, requestHeaders, requestBodyUriString,
            responseCode, responseMessage, responseHeaders, responseBodyUriString,
            stack, dnsHost, fullAddress
        )
        val viewPager: ViewPager2 = findViewById(R.id.view_pager)
        viewPager.adapter = sectionsPagerAdapter
        viewPager.offscreenPageLimit = sectionsPagerAdapter.itemCount

        val tabs: TabLayout = findViewById(R.id.tabs)
        TabLayoutMediator(tabs, viewPager) { tab, position ->
            tab.text = sectionsPagerAdapter.getPageTitle(position)
        }.attach()
    }

    class SectionsPagerAdapter(
        activity: FragmentActivity,
        private val method: String,
        private val urlString: String,
        private val requestHeaders: String,
        private val requestBodyUriString: String?,
        private val responseCode: String,
        private val responseMessage: String,
        private val responseHeaders: String,
        private val responseBodyUriString: String?,
        private val stack: String,
        private val dnsHost: String?,
        private val fullAddress: String?
    ) : FragmentStateAdapter(activity) {

        private val fragmentTitles = mutableListOf<String>()

        init {
            if (!dnsHost.isNullOrEmpty()) {
                fragmentTitles.add("DNS Info")
            }

            if (method.isNotEmpty() || urlString.isNotEmpty() || requestHeaders.isNotEmpty()) {
                fragmentTitles.add("Request")
            }

            requestBodyUriString?.let {
                if (it.isNotEmpty()) {
                    fragmentTitles.add("RequestBody")
                }
            }

            if (responseMessage.isNotEmpty() || responseHeaders.isNotEmpty()) {
                fragmentTitles.add("Response")
                if (!responseBodyUriString.isNullOrEmpty()) {
                    fragmentTitles.add("ResponseBody")
                }
            }

            if (stack.isNotEmpty()) {
                fragmentTitles.add("Stack")
            }
        }

        override fun getItemCount(): Int = fragmentTitles.size

        override fun createFragment(position: Int): Fragment = RequestInfoFragment.newInstance(
            getPageTitle(position),
            method,
            urlString,
            requestHeaders,
            requestBodyUriString,
            responseCode,
            responseMessage,
            responseHeaders,
            responseBodyUriString,
            stack,
            dnsHost,
            fullAddress
        )

        fun getPageTitle(position: Int): String = fragmentTitles[position]
    }
}
