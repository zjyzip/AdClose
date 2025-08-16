package com.close.hook.ads.ui.activity

import android.os.Bundle
import androidx.activity.addCallback
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
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

    companion object {
        private const val MANAGER_FRAGMENT_INDEX = 0
        private const val LOG_FRAGMENT_INDEX = 1
    }

    private val binding by lazy { ActivityCustomHookBinding.inflate(layoutInflater) }
    
    private val targetPackageName by lazy { intent.getStringExtra("packageName") }

    private val viewPager by lazy { binding.viewPager }
    val bottomNavigationView by lazy { binding.bottomNavigationHook }
    private val hideBottomViewOnScrollBehavior by lazy { HideBottomViewOnScrollBehavior<BottomNavigationView>() }

    override var backController: OnBackPressListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupViewPager()
        setupBottomNavigation()
        setupBackButton()
    }

    private fun setupViewPager() {
        viewPager.apply {
            adapter = ViewPagerAdapter(this@CustomHookActivity, targetPackageName)
            isUserInputEnabled = false
            offscreenPageLimit = 2
            
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    bottomNavigationView.menu.getItem(position).isChecked = true
                }
            })
        }
    }

    private fun setupBottomNavigation() {
        (bottomNavigationView.layoutParams as? CoordinatorLayout.LayoutParams)?.behavior = hideBottomViewOnScrollBehavior
        bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_hook_manager -> viewPager.currentItem = MANAGER_FRAGMENT_INDEX
                R.id.nav_hook_log -> viewPager.currentItem = LOG_FRAGMENT_INDEX
            }
            true
        }
    }

    private fun setupBackButton() {
        onBackPressedDispatcher.addCallback(this) {
            if (backController?.onBackPressed() == true) {
                return@addCallback
            }

            if (viewPager.currentItem == LOG_FRAGMENT_INDEX) {
                viewPager.currentItem = MANAGER_FRAGMENT_INDEX
            } else {
                finish()
            }
        }
    }

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

    private class ViewPagerAdapter(
        activity: FragmentActivity,
        private val packageName: String?
    ) : FragmentStateAdapter(activity) {

        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment = when (position) {
            MANAGER_FRAGMENT_INDEX -> CustomHookManagerFragment.newInstance(packageName)
            LOG_FRAGMENT_INDEX -> CustomHookLogFragment.newInstance(packageName)
            else -> throw IllegalStateException("Invalid position $position")
        }
    }
}
