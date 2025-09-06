package com.close.hook.ads.ui.activity

import android.os.Bundle
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.close.hook.ads.R
import com.close.hook.ads.preference.PrefManager
import com.close.hook.ads.ui.fragment.app.AppsPagerFragment
import com.close.hook.ads.ui.fragment.block.BlockListFragment
import com.close.hook.ads.ui.fragment.home.HomeFragment
import com.close.hook.ads.ui.fragment.request.RequestFragment
import com.close.hook.ads.ui.fragment.settings.SettingsFragment
import com.close.hook.ads.util.INavContainer
import com.close.hook.ads.util.OnBackPressContainer
import com.close.hook.ads.util.OnBackPressListener
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : BaseActivity(), OnBackPressContainer, INavContainer {

    override var backController: OnBackPressListener? = null
    
    private lateinit var viewPager2: ViewPager2
    private lateinit var bottomNavigationView: BottomNavigationView
    private lateinit var hideBottomViewOnScrollBehavior: HideBottomViewOnScrollBehavior<BottomNavigationView>

    private val fragmentSuppliers: List<() -> Fragment> = listOf(
        ::AppsPagerFragment,
        ::RequestFragment,
        ::HomeFragment,
        ::BlockListFragment,
        ::SettingsFragment
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupViewPagerAndBottomNavigation()
    }

    private fun setupViewPagerAndBottomNavigation() {
        viewPager2 = findViewById(R.id.view_pager)
        bottomNavigationView = findViewById(R.id.bottom_navigation)

        val adapter = BottomFragmentStateAdapter(supportFragmentManager, lifecycle, fragmentSuppliers)
        viewPager2.adapter = adapter
        viewPager2.isUserInputEnabled = false
        viewPager2.offscreenPageLimit = fragmentSuppliers.size

        bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.bottom_item_1 -> viewPager2.setCurrentItem(0, false)
                R.id.bottom_item_2 -> viewPager2.setCurrentItem(1, false)
                R.id.bottom_item_3 -> viewPager2.setCurrentItem(2, false)
                R.id.bottom_item_4 -> viewPager2.setCurrentItem(3, false)
                R.id.bottom_item_5 -> viewPager2.setCurrentItem(4, false)
            }
            true
        }

        hideBottomViewOnScrollBehavior = HideBottomViewOnScrollBehavior()
        (bottomNavigationView.layoutParams as CoordinatorLayout.LayoutParams).behavior = hideBottomViewOnScrollBehavior

        viewPager2.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                bottomNavigationView.selectedItemId = bottomNavigationView.menu.getItem(position).itemId
            }
        })
        viewPager2.setCurrentItem(PrefManager.defaultPage, false)
    }

    override fun onBackPressed() {
        if (backController?.onBackPressed() == true) {
            return
        }

        if (viewPager2.currentItem != 0) {
            viewPager2.setCurrentItem(0, true)
        } else {
            super.onBackPressed()
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

    fun getBottomNavigationView(): BottomNavigationView {
        return bottomNavigationView
    }

    private class BottomFragmentStateAdapter(
        fragmentManager: FragmentManager,
        lifecycle: Lifecycle,
        private val fragmentSuppliers: List<() -> Fragment>
    ) : FragmentStateAdapter(fragmentManager, lifecycle) {

        override fun createFragment(position: Int): Fragment {
            return fragmentSuppliers[position]()
        }

        override fun getItemCount(): Int {
            return fragmentSuppliers.size
        }
    }
}
