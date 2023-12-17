package com.close.hook.ads.ui.fragment;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.close.hook.ads.R;
import com.close.hook.ads.ui.adapter.UniversalPagerAdapter;
import com.close.hook.ads.util.OnBackPressContainer;
import com.close.hook.ads.util.OnBackPressListener;
import com.close.hook.ads.util.OnCLearCLickContainer;
import com.close.hook.ads.util.OnClearClickListener;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class HostsFragment extends Fragment implements OnCLearCLickContainer, OnBackPressListener {

    private ViewPager2 viewPager;
    private TabLayout tabLayout;
    private MaterialToolbar materialToolbar;
    private OnClearClickListener onClearClickListener;
    private EditText searchEditText;
    private ImageView searchIcon;
    private Disposable searchDisposable;
    private ImageButton searchClear;
    private InputMethodManager imm;

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
        searchEditText = view.findViewById(R.id.search_edit_text);
        searchIcon = view.findViewById(R.id.search_icon);
        searchClear = view.findViewById(R.id.search_clear);

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

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                searchEditText.setText("");
                searchEditText.clearFocus();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {

            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {

            }
        });

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                searchEditText.setText("");
                searchEditText.clearFocus();
            }
        });

        initEditText();

    }

    private void initEditText() {
        searchEditText.setOnFocusChangeListener(
                (view, hasFocus) -> setIcon(hasFocus ? R.drawable.ic_back : R.drawable.ic_search));

        searchClear.setOnClickListener(view -> {
            searchEditText.setText("");
        });

        searchIcon.setOnClickListener(view -> {
            if (searchEditText.isFocused()) {
                searchEditText.setText("");
                setIconAndFocus(R.drawable.ic_search, false);
            } else {
                setIconAndFocus(R.drawable.ic_back, true);
            }
        });

        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (searchDisposable != null && !searchDisposable.isDisposed()) {
                    searchDisposable.dispose();
                }
                searchDisposable = Observable.just(s.toString()).debounce(300, TimeUnit.MILLISECONDS)
                        .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                        .subscribe(this::performSearch);
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (s.toString().equals("")) {
                    searchClear.setVisibility(View.GONE);
                } else {
                    searchClear.setVisibility(View.VISIBLE);
                }
            }

            private void performSearch(String query) {
                if (onClearClickListener!=null)
                    onClearClickListener.search(query);
            }
        });
    }

    private void setIconAndFocus(int drawableId, boolean focus) {
        imm = (InputMethodManager) requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
        searchIcon.setImageDrawable(requireContext().getDrawable(drawableId));
        if (focus) {
            searchEditText.requestFocus();
            imm.showSoftInput(searchEditText, 0);
        } else {
            searchEditText.clearFocus();
            imm.hideSoftInputFromWindow(searchEditText.getWindowToken(), 0);
        }
    }

    private void setIcon(int drawableId) {
        searchIcon.setImageDrawable(requireContext().getDrawable(drawableId));
    }

    @Override
    public OnClearClickListener getController() {
        return this.onClearClickListener;
    }

    @Override
    public void setController(OnClearClickListener onClearClickListener) {
        this.onClearClickListener = onClearClickListener;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (searchDisposable != null && !searchDisposable.isDisposed()) {
            searchDisposable.dispose();
        }
    }

    @Override
    public Boolean onBackPressed() {
        if (searchEditText.isFocused()) {
            searchEditText.setText("");
            setIconAndFocus(R.drawable.ic_search, false);
            return true;
        }
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        OnBackPressContainer onBackPressContainer = (OnBackPressContainer) requireContext();
        onBackPressContainer.setController(this);
    }
}
