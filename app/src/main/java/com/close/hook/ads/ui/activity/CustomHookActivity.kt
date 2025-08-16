package com.close.hook.ads.ui.activity

import android.os.Bundle
import androidx.annotation.NonNull
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.close.hook.ads.R
import com.close.hook.ads.databinding.ActivityCustomHookBinding
import com.close.hook.ads.ui.fragment.hook.CustomHookLogFragment
import com.close.hook.ads.ui.fragment.hook.CustomHookManagerFragment
import com.close.hook.ads.util.INavContainer
import com.close.hook.ads.util.OnBackPressContainer
import com.close.hook.ads.util.OnBackPressListener
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.bottomnavigation.BottomNavigationView

class CustomHookActivity : BaseActivity(), OnBackPressContainer, INavContainer {

    private lateinit var binding: ActivityCustomHookBinding
    private lateinit var bottomNavigationView: BottomNavigationView
    private lateinit var hideBottomViewOnScrollBehavior: HideBottomViewOnScrollBehavior<BottomNavigationView>
    private lateinit var viewPager: ViewPager2

    override var backController: OnBackPressListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCustomHookBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViewPagerAndBottomNavigation()
    }

    private fun setupViewPagerAndBottomNavigation() {
        val packageName = intent.getStringExtra("packageName")

        viewPager = binding.viewPager
        bottomNavigationView = binding.bottomNavigationHook

        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 2

            override fun createFragment(position: Int): Fragment {
                return when (position) {
                    0 -> CustomHookManagerFragment.newInstance(packageName)
                    1 -> CustomHookLogFragment.newInstance(packageName)
                    else -> throw IllegalArgumentException("Invalid position: $position")
                }
            }
        }
        viewPager.isUserInputEnabled = false
        viewPager.offscreenPageLimit = 2

        bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_hook_manager -> viewPager.currentItem = 0
                R.id.nav_hook_log -> viewPager.currentItem = 1
            }
            true
        }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                bottomNavigationView.selectedItemId = bottomNavigationView.menu.getItem(position).itemId
            }
        })

        hideBottomViewOnScrollBehavior = HideBottomViewOnScrollBehavior()
        (bottomNavigationView.layoutParams as? CoordinatorLayout.LayoutParams)?.apply {
            behavior = hideBottomViewOnScrollBehavior
        }
    }

    override fun onBackPressed() {
        if (backController?.onBackPressed() == true) {
            return
        }
        super.onBackPressed()
    }

    fun getBottomNavigationView(): BottomNavigationView = bottomNavigationView

    override fun showNavigation() {
        if (hideBottomViewOnScrollBehavior.isScrolledDown) {
            hideBottomViewOnScrollBehavior.slideUp(bottomNavigationView)
        }
    }

    override fun hideNavigation() {
        if (hideBottomViewOnScrollBehavior.isScrolledUp) {
            hideBottomViewOnScrollBehavior.slideDown(bottomNavigationView)
        }
    }
}
