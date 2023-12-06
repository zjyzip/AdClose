package com.close.hook.ads.ui.fragment;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.close.hook.ads.util.OnBackPressListener;
import com.close.hook.ads.util.OnBackPressContainer;
import com.close.hook.ads.R;
import com.close.hook.ads.ui.adapter.UniversalPagerAdapter;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class InstalledAppsFragment extends Fragment implements OnBackPressListener {

    private ViewPager2 viewPager;
    private TabLayout tabLayout;
    private EditText searchEditText;
    private ImageView searchIcon;
    private Disposable searchDisposable;
    private AppsFragment appFragment1;
    private AppsFragment appFragment2;
    private InputMethodManager imm;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        imm = (InputMethodManager) requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.universal_with_tabs, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initializeViews(view);
        setupViewPagerAndTabs();
    }

    private void initializeViews(View view) {
        viewPager = view.findViewById(R.id.view_pager);
        tabLayout = view.findViewById(R.id.tab_layout);
        searchEditText = view.findViewById(R.id.search_edit_text);
        searchIcon = view.findViewById(R.id.search_icon);

        appFragment1 = AppsFragment.newInstance(false);
        appFragment2 = AppsFragment.newInstance(true);

        setupSearchIcon();
        setupSearchEditText();
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private void setupSearchIcon() {
        searchIcon.setOnClickListener(view -> {
            if (searchEditText.isFocused()) {
                setIconAndFocus(R.drawable.ic_search, false);
            } else {
                setIconAndFocus(R.drawable.ic_back, true);
            }
        });
    }

    private void setIconAndFocus(int drawableId, boolean focus) {
        searchIcon.setImageDrawable(requireContext().getDrawable(drawableId));
        if (focus) {
            searchEditText.requestFocus();
            imm.showSoftInput(searchEditText, 0);
        } else {
            searchEditText.clearFocus();
            imm.hideSoftInputFromWindow(searchEditText.getWindowToken(), 0);
        }
    }

    private void setupViewPagerAndTabs() {
        UniversalPagerAdapter adapter = new UniversalPagerAdapter(this,
                List.of(appFragment1, appFragment2));
        viewPager.setAdapter(adapter);

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            tab.setText(position == 0 ? R.string.tab_user_apps : R.string.tab_system_apps);
        }).attach();
    }

    private void setupSearchEditText() {
        searchEditText.setOnFocusChangeListener((view, hasFocus) -> setIcon(hasFocus ? R.drawable.ic_back : R.drawable.ic_search));

        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (searchDisposable != null && !searchDisposable.isDisposed()) {
                    searchDisposable.dispose();
                }
                searchDisposable = Observable.just(s.toString())
                        .debounce(300, TimeUnit.MILLISECONDS)
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(this::performSearch);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }

            private void performSearch(String query) {
                if (viewPager.getCurrentItem() == 0) {
                    appFragment1.searchKeyWorld(query);
                } else if (viewPager.getCurrentItem() == 1) {
                    appFragment2.searchKeyWorld(query);
                }
            }
        });
    }

    private void setIcon(int drawableId) {
        searchIcon.setImageDrawable(requireContext().getDrawable(drawableId));
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
