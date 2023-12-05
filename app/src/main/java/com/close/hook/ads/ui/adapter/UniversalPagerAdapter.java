package com.close.hook.ads.ui.adapter;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import java.util.List;

public class UniversalPagerAdapter extends FragmentStateAdapter {

    private final List<Fragment> fragments;

    public UniversalPagerAdapter(Fragment fragment, List<Fragment> fragments) {
        super(fragment);
        this.fragments = fragments;
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        return fragments.get(position);
    }

    @Override
    public int getItemCount() {
        return fragments.size();
    }
}
