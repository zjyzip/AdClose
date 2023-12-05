package com.close.hook.ads.ui.activity;

import android.annotation.SuppressLint;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.close.hook.ads.OnBackPressListener;
import com.close.hook.ads.OnBackPressContainer;
import com.close.hook.ads.R;
import com.close.hook.ads.ui.fragment.HostsFragment;
import com.close.hook.ads.ui.fragment.InstalledAppsFragment;
import com.close.hook.ads.util.AppUtils;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements OnBackPressContainer {

	private OnBackPressListener controller;

	@Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        AppUtils.setSystemBarsColor(findViewById(android.R.id.content));

        setupBottomNavigation();

    }

    public static boolean isModuleActivated() {
        return false;
    }

    @SuppressLint("NonConstantResourceId")
    private void setupBottomNavigation() {

		BottomNavigationView bottomNavigationView = findViewById(R.id.bottom_navigation);
		ViewPager2 viewPager2 = findViewById(R.id.view_pager);
		List<Fragment> fragmentList = new ArrayList<>();
		fragmentList.add(new InstalledAppsFragment());
		fragmentList.add(new HostsFragment());
		fragmentList.add(new Fragment()); //待定

		BottomFragmentStateAdapter bottomFragmentStateAdapter =
				new BottomFragmentStateAdapter(this, fragmentList);
		viewPager2.setAdapter(bottomFragmentStateAdapter);
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
				switch (position){
					case 0:
						bottomNavigationView.setSelectedItemId(R.id.bottom_item_1);
						break;
					case 1:
						bottomNavigationView.setSelectedItemId(R.id.bottom_item_2);
						break;
					case 2:
						bottomNavigationView.setSelectedItemId(R.id.bottom_item_3);
						break;
				}
			}
		});

	}

	static class BottomFragmentStateAdapter extends FragmentStateAdapter {

		List<Fragment> fragmentList;
		public BottomFragmentStateAdapter(@NonNull FragmentActivity fragmentActivity, List<Fragment> list) {
			super(fragmentActivity);
			this.fragmentList = list;
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

	@Override
	public void onBackPressed() {
		if (!controller.onBackPressed()){
			super.onBackPressed();
		}
	}

	@Override
	public OnBackPressListener getController() {
		return this.controller;
	}

	@Override
	public void setController(OnBackPressListener onBackPressListener) {
		this.controller = onBackPressListener;
	}

}
