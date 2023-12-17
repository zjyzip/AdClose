package com.close.hook.ads.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.close.hook.ads.R;
import com.close.hook.ads.ui.adapter.UniversalPagerAdapter;
import com.close.hook.ads.util.OnCLearCLickContainer;
import com.close.hook.ads.util.OnClearClickListener;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.ArrayList;
import java.util.List;

public class HostsFragment extends Fragment implements OnCLearCLickContainer {

    private ViewPager2 viewPager;
    private TabLayout tabLayout;
    private MaterialToolbar materialToolbar;
    private OnClearClickListener onClearClickListener;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_hosts, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewPager = view.findViewById(R.id.view_pager);
        tabLayout = view.findViewById(R.id.tab_layout);
        materialToolbar = view.findViewById(R.id.toolbar);

        materialToolbar.setTitle(R.string.bottom_item_2);
        materialToolbar.inflateMenu(R.menu.menu_hosts);
        materialToolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.clear){
                onClearClickListener.onClearAll();
            }
            return true;
        });

        List<Fragment> fragments = new ArrayList<>();

        fragments.add(HostsListFragment.newInstance("all"));
        fragments.add(HostsListFragment.newInstance("block"));
        fragments.add(HostsListFragment.newInstance("pass"));

        UniversalPagerAdapter adapter = new UniversalPagerAdapter(this, fragments);
        viewPager.setAdapter(adapter);
        viewPager.setOffscreenPageLimit(fragments.size());

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            switch (position) {
                case 0:
                    tab.setText(R.string.tab_domain_list);
                    break;
                case 1:
                    tab.setText(R.string.tab_host_list);
                    break;
                case 2:
                    tab.setText(R.string.tab_host_whitelist);
                    break;
            }
        }).attach();
    }

    @Override
    public OnClearClickListener getController() {
        return this.onClearClickListener;
    }

    @Override
    public void setController(OnClearClickListener onClearClickListener) {
        this.onClearClickListener = onClearClickListener;
    }
}
