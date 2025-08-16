package com.close.hook.ads.ui.activity;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Lifecycle;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.close.hook.ads.R;
import com.close.hook.ads.ui.fragment.block.BlockListFragment;
import com.close.hook.ads.ui.fragment.home.HomeFragment;
import com.close.hook.ads.ui.fragment.app.AppsPagerFragment;
import com.close.hook.ads.ui.fragment.request.RequestFragment;
import com.close.hook.ads.ui.fragment.settings.SettingsFragment;
import com.close.hook.ads.util.INavContainer;
import com.close.hook.ads.util.OnBackPressContainer;
import com.close.hook.ads.util.OnBackPressListener;
import com.close.hook.ads.preference.PrefManager;
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public class MainActivity extends BaseActivity implements OnBackPressContainer, INavContainer {

    private OnBackPressListener currentFragmentController;
    private ViewPager2 viewPager2;
    private BottomNavigationView bottomNavigationView;
    private HideBottomViewOnScrollBehavior<BottomNavigationView> hideBottomViewOnScrollBehavior;

    private final List<Supplier<Fragment>> fragmentSuppliers = Arrays.asList(
            AppsPagerFragment::new,
            RequestFragment::new,
            HomeFragment::new,
            BlockListFragment::new,
            SettingsFragment::new
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setupViewPagerAndBottomNavigation();
    }

    public static boolean isModuleActivated() {
        return false;
    }

    private void setupViewPagerAndBottomNavigation() {
        viewPager2 = findViewById(R.id.view_pager);
        bottomNavigationView = findViewById(R.id.bottom_navigation);

        BottomFragmentStateAdapter adapter = new BottomFragmentStateAdapter(getSupportFragmentManager(), getLifecycle(), fragmentSuppliers);
        viewPager2.setAdapter(adapter);
        viewPager2.setUserInputEnabled(false);
        viewPager2.setOffscreenPageLimit(fragmentSuppliers.size());

        bottomNavigationView.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.bottom_item_1) viewPager2.setCurrentItem(0);
            else if (itemId == R.id.bottom_item_2) viewPager2.setCurrentItem(1);
            else if (itemId == R.id.bottom_item_3) viewPager2.setCurrentItem(2);
            else if (itemId == R.id.bottom_item_4) viewPager2.setCurrentItem(3);
            else if (itemId == R.id.bottom_item_5) viewPager2.setCurrentItem(4);
            return true;
        });

        hideBottomViewOnScrollBehavior = new HideBottomViewOnScrollBehavior<>();
        ((CoordinatorLayout.LayoutParams) bottomNavigationView.getLayoutParams()).setBehavior(hideBottomViewOnScrollBehavior);

        viewPager2.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                bottomNavigationView.setSelectedItemId(bottomNavigationView.getMenu().getItem(position).getItemId());
                updateCurrentFragmentController();
            }
        });
        viewPager2.setCurrentItem(PrefManager.INSTANCE.getDefaultPage(), false);
    }

    private void updateCurrentFragmentController() {
        Fragment fragment = getSupportFragmentManager().findFragmentByTag("f" + viewPager2.getId() + ":" + viewPager2.getCurrentItem());
        currentFragmentController = (fragment instanceof OnBackPressListener) ? (OnBackPressListener) fragment : null;
    }

    @Override
    public void onBackPressed() {
        if (currentFragmentController != null && currentFragmentController.onBackPressed()) return;
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
        if (hideBottomViewOnScrollBehavior != null && hideBottomViewOnScrollBehavior.isScrolledDown()) {
            hideBottomViewOnScrollBehavior.slideUp(bottomNavigationView);
        }
    }

    @Override
    public void hideNavigation() {
        if (hideBottomViewOnScrollBehavior != null && hideBottomViewOnScrollBehavior.isScrolledUp()) {
            hideBottomViewOnScrollBehavior.slideDown(bottomNavigationView);
        }
    }

    public BottomNavigationView getBottomNavigationView() {
        return bottomNavigationView;
    }

    static class BottomFragmentStateAdapter extends FragmentStateAdapter {

        private final List<Supplier<Fragment>> fragmentSuppliers;

        public BottomFragmentStateAdapter(@NonNull FragmentManager fragmentManager, @NonNull Lifecycle lifecycle, List<Supplier<Fragment>> fragmentSuppliers) {
            super(fragmentManager, lifecycle);
            this.fragmentSuppliers = fragmentSuppliers;
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return Objects.requireNonNull(fragmentSuppliers.get(position).get());
        }

        @Override
        public int getItemCount() {
            return fragmentSuppliers.size();
        }
    }
}
