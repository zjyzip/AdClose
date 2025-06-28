package com.close.hook.ads.ui.activity;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.close.hook.ads.R;
import com.close.hook.ads.ui.fragment.BlockListFragment;
import com.close.hook.ads.ui.fragment.HomeFragment;
import com.close.hook.ads.ui.fragment.app.AppsPagerFragment;
import com.close.hook.ads.ui.fragment.request.RequestFragment;
import com.close.hook.ads.ui.fragment.settings.SettingsFragment;
import com.close.hook.ads.util.INavContainer;
import com.close.hook.ads.util.OnBackPressContainer;
import com.close.hook.ads.util.OnBackPressListener;
import com.close.hook.ads.util.PrefManager;
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BaseActivity implements OnBackPressContainer, INavContainer {

    private OnBackPressListener currentFragmentController;
    private ViewPager2 viewPager2;
    private BottomNavigationView bottomNavigationView;
    private HideBottomViewOnScrollBehavior<BottomNavigationView> hideBottomViewOnScrollBehavior;

    private final List<Class<? extends Fragment>> fragmentClasses = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        fragmentClasses.add(AppsPagerFragment.class);
        fragmentClasses.add(RequestFragment.class);
        fragmentClasses.add(HomeFragment.class);
        fragmentClasses.add(BlockListFragment.class);
        fragmentClasses.add(SettingsFragment.class);

        setupViewPagerAndBottomNavigation();
    }

    public static boolean isModuleActivated() {
        return false;
    }

    private void setupViewPagerAndBottomNavigation() {
        viewPager2 = findViewById(R.id.view_pager);
        bottomNavigationView = findViewById(R.id.bottom_navigation);
        hideBottomViewOnScrollBehavior = new HideBottomViewOnScrollBehavior<BottomNavigationView>();

        BottomFragmentStateAdapter adapter = new BottomFragmentStateAdapter(this, fragmentClasses);
        viewPager2.setAdapter(adapter);
        viewPager2.setUserInputEnabled(false);

        bottomNavigationView.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.bottom_item_1) {
                viewPager2.setCurrentItem(0);
            } else if (itemId == R.id.bottom_item_2) {
                viewPager2.setCurrentItem(1);
            } else if (itemId == R.id.bottom_item_3) {
                viewPager2.setCurrentItem(2);
            } else if (itemId == R.id.bottom_item_4) {
                viewPager2.setCurrentItem(3);
            } else if (itemId == R.id.bottom_item_5) {
                viewPager2.setCurrentItem(4);
            }
            return true;
        });
        CoordinatorLayout.LayoutParams layoutParams = (CoordinatorLayout.LayoutParams) bottomNavigationView.getLayoutParams();
        layoutParams.setBehavior(hideBottomViewOnScrollBehavior);

        viewPager2.setOffscreenPageLimit(fragmentClasses.size()); 

        viewPager2.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                bottomNavigationView.getMenu().getItem(position).setChecked(true);
                updateCurrentFragmentController(position);
            }
        });
        viewPager2.setCurrentItem(PrefManager.INSTANCE.getDefaultPage(), false);
    }

    private void updateCurrentFragmentController(int position) {
        Fragment fragment = getSupportFragmentManager().findFragmentByTag("f" + viewPager2.getId() + ":" + viewPager2.getCurrentItem());
        
        if (fragment instanceof OnBackPressListener) {
            currentFragmentController = (OnBackPressListener) fragment;
        } else {
            currentFragmentController = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (currentFragmentController != null && currentFragmentController.onBackPressed()) {
            return;
        }

        if (viewPager2.getCurrentItem() != 0) {
            viewPager2.setCurrentItem(0, true);
        } else {
            super.onBackPressed();
        }
    }


    @Override
    public OnBackPressListener getBackController() {
        return currentFragmentController;
    }

    @Override
    public void setBackController(OnBackPressListener onBackPressListener) {
        this.currentFragmentController = onBackPressListener;
    }

    @Override
    public void showNavigation() {
        if (hideBottomViewOnScrollBehavior.isScrolledDown())
            hideBottomViewOnScrollBehavior.slideUp(bottomNavigationView);
    }

    @Override
    public void hideNavigation() {
        if (hideBottomViewOnScrollBehavior.isScrolledUp())
            hideBottomViewOnScrollBehavior.slideDown(bottomNavigationView);
    }

    static class BottomFragmentStateAdapter extends FragmentStateAdapter {

        private final List<Class<? extends Fragment>> fragmentClasses;

        public BottomFragmentStateAdapter(@NonNull FragmentActivity fragmentActivity, List<Class<? extends Fragment>> fragmentClasses) {
            super(fragmentActivity);
            this.fragmentClasses = fragmentClasses;
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            Class<? extends Fragment> fragmentClass = fragmentClasses.get(position);
            if (fragmentClass == AppsPagerFragment.class) {
                return AppsPagerFragment.newInstance();
            } else {
                try {
                    return fragmentClass.newInstance();
                } catch (InstantiationException | IllegalAccessException e) {
                    throw new RuntimeException("Failed to create fragment instance", e);
                }
            }
        }

        @Override
        public int getItemCount() {
            return fragmentClasses.size();
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean containsItem(long itemId) {
            return itemId >= 0 && itemId < fragmentClasses.size();
        }
    }
}
