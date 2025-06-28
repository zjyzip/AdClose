package com.close.hook.ads.debug

import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.close.hook.ads.databinding.ActivityPerformanceBinding
import com.close.hook.ads.ui.activity.BaseActivity
import com.close.hook.ads.debug.fragment.AdapterPerformanceFragment
import com.close.hook.ads.debug.fragment.AppRepoPerformanceFragment
import com.google.android.material.tabs.TabLayoutMediator

@RequiresApi(Build.VERSION_CODES.N)
class PerformanceActivity : BaseActivity() {

    private lateinit var binding: ActivityPerformanceBinding

    private val fragments = listOf(
        AppRepoPerformanceFragment(),
        AdapterPerformanceFragment()
    )

    private val tabTitles = listOf(
        "应用Repository性能",
        "列表Adapter性能"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPerformanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViewPagerAndTabs()
    }

    private fun setupViewPagerAndTabs() {
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = fragments.size
            override fun createFragment(position: Int): Fragment = fragments[position]
        }

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()
    }
}
