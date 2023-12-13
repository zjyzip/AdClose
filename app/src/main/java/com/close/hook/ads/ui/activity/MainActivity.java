package com.close.hook.ads.ui.activity;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.close.hook.ads.R;
import com.close.hook.ads.ui.fragment.AboutFragment;
import com.close.hook.ads.ui.fragment.HostsFragment;
import com.close.hook.ads.ui.fragment.InstalledAppsFragment;
import com.close.hook.ads.util.AppUtils;
import com.close.hook.ads.util.OnBackPressContainer;
import com.close.hook.ads.util.OnBackPressListener;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements OnBackPressContainer {

    private OnBackPressListener currentFragmentController;
    private ViewPager2 viewPager2;
    private BottomNavigationView bottomNavigationView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        AppUtils.setSystemBarsColor(findViewById(android.R.id.content));
        setupViewPagerAndBottomNavigation();
    }

    public static boolean isModuleActivated() {
        return false;
    }

    private void setupViewPagerAndBottomNavigation() {
        viewPager2 = findViewById(R.id.view_pager);
        bottomNavigationView = findViewById(R.id.bottom_navigation);

        List<Fragment> fragments = new ArrayList<>();
        fragments.add(new InstalledAppsFragment());
        fragments.add(new HostsFragment());
        fragments.add(new AboutFragment());

        BottomFragmentStateAdapter adapter = new BottomFragmentStateAdapter(this, fragments);
        viewPager2.setAdapter(adapter);
        viewPager2.setUserInputEnabled(false);

        bottomNavigationView.setOnItemSelectedListener(item -> {
            if (item.getItemId() == R.id.bottom_item_1) {
                viewPager2.setCurrentItem(0);
            } else if (item.getItemId() == R.id.bottom_item_2) {
                viewPager2.setCurrentItem(1);
            } else if (item.getItemId() == R.id.bottom_item_3) {
                viewPager2.setCurrentItem(2);
            }
            return true;
        });

        viewPager2.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                bottomNavigationView.getMenu().getItem(position).setChecked(true);
                updateCurrentFragmentController(position);
            }
        });
    }

    private void updateCurrentFragmentController(int position) {
        Fragment fragment = getSupportFragmentManager().findFragmentByTag("f" + position);
        if (fragment instanceof OnBackPressListener) {
            currentFragmentController = (OnBackPressListener) fragment;
        } else {
            currentFragmentController = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (currentFragmentController == null || !currentFragmentController.onBackPressed()) {
            super.onBackPressed();
        }
    }

    @Override
    public OnBackPressListener getController() {
        return currentFragmentController;
    }

    @Override
    public void setController(OnBackPressListener onBackPressListener) {
        this.currentFragmentController = onBackPressListener;
    }

    static class BottomFragmentStateAdapter extends FragmentStateAdapter {

        private final List<Fragment> fragmentList;

        public BottomFragmentStateAdapter(@NonNull FragmentActivity fragmentActivity, List<Fragment> fragmentList) {
            super(fragmentActivity);
            this.fragmentList = fragmentList;
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return fragmentList.get(position);
        }

        @Override
        public int getItemCount() {
            return fragmentList.size();
        }
    }
}
