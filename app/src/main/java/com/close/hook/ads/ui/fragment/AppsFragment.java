package com.close.hook.ads.ui.fragment;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;

import com.close.hook.ads.ui.activity.MainActivity;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;
import android.annotation.SuppressLint;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.close.hook.ads.R;
import com.close.hook.ads.util.LinearItemDecoration;

import com.close.hook.ads.util.AppUtils;
import com.close.hook.ads.data.model.AppInfo;
import com.close.hook.ads.ui.adapter.AppsAdapter;
import com.close.hook.ads.ui.viewmodel.AppsViewModel;
import com.close.hook.ads.hook.preference.PreferencesHelper;

import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;

public class AppsFragment extends Fragment {

    private static final String PREFERENCES_NAME = "com.close.hook.ads_preferences";
    private final CompositeDisposable disposables = new CompositeDisposable();
    private RecyclerView recyclerView;
    private AppsAdapter appsAdapter;
    private AppsViewModel appsViewModel;
    private ProgressBar progressBar;
    private List<AppInfo> appInfoList;
    private boolean isSystemApp;

    public static AppsFragment newInstance(boolean isSystemApp) {
        AppsFragment fragment = new AppsFragment();
        Bundle args = new Bundle();
        args.putBoolean("IS_SYSTEM_APP", isSystemApp);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            isSystemApp = getArguments().getBoolean("IS_SYSTEM_APP");
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_apps, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        progressBar = view.findViewById(R.id.progress_bar);
        recyclerView = view.findViewById(R.id.recycler_view_apps);
        recyclerView.setLayoutManager(new LinearLayoutManager(view.getContext()));
        appsAdapter = new AppsAdapter();
        recyclerView.setAdapter(appsAdapter);

        if (recyclerView.getItemDecorationCount() == 0) {
            int space = this.getResources().getDimensionPixelSize(R.dimen.normal_space);
            recyclerView.addItemDecoration(new LinearItemDecoration(space));
        }

        progressBar.setVisibility(View.VISIBLE);
        appsViewModel = new ViewModelProvider(requireActivity()).get(AppsViewModel.class);
        LiveData<List<AppInfo>> appsLiveData =
            isSystemApp ? appsViewModel.getSystemAppsLiveData() : appsViewModel.getUserAppsLiveData();

        appsLiveData.observe(getViewLifecycleOwner(), appInfos -> {
            progressBar.setVisibility(View.GONE);
            appInfoList = appInfos;
            appsAdapter.submitList(appInfos);
        });

        setupAdapterItemClick();
    }

    private void setupAdapterItemClick() {
        disposables.add(
            appsAdapter.getOnClickObservable()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(appInfo -> {
                    if (MainActivity.isModuleActivated()) {
                        showBottomSheetDialog(appInfo);
                    } else {
                        AppUtils.showToast(requireContext(), "模块未激活");
                    }
                }));
    }

    private void showBottomSheetDialog(AppInfo appInfo) {
        BottomSheetDialog bottomSheetDialog = new BottomSheetDialog(requireContext());
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.bottom_dialog_switches, null);
        bottomSheetDialog.setContentView(dialogView);

        setupSwitchListeners(dialogView, appInfo);
        bottomSheetDialog.show();
    }

    @SuppressLint("CommitPrefEdits")
    private void setupSwitchListeners(View dialogView, AppInfo appInfo) {
        @SuppressLint("WorldReadableFiles")
        PreferencesHelper prefsHelper = new PreferencesHelper(dialogView.getContext(), PREFERENCES_NAME);
        setupSwitch(dialogView.findViewById(R.id.switch_one), "switch_one_" + appInfo.getPackageName(), prefsHelper);
        setupSwitch(dialogView.findViewById(R.id.switch_two), "switch_two_" + appInfo.getPackageName(), prefsHelper);
        setupSwitch(dialogView.findViewById(R.id.switch_three), "switch_three_" + appInfo.getPackageName(), prefsHelper);
        setupSwitch(dialogView.findViewById(R.id.switch_four), "switch_four_" + appInfo.getPackageName(), prefsHelper);
        setupSwitch(dialogView.findViewById(R.id.switch_five), "switch_five_" + appInfo.getPackageName(), prefsHelper);
        setupSwitch(dialogView.findViewById(R.id.switch_six), "switch_six_" + appInfo.getPackageName(), prefsHelper);
        setupSwitch(dialogView.findViewById(R.id.switch_seven), "switch_seven_" + appInfo.getPackageName(), prefsHelper);
    }

    private void setupSwitch(MaterialSwitch switchView, String key, PreferencesHelper prefsHelper) {
        switchView.setChecked(prefsHelper.getBoolean(key, false));
        switchView.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefsHelper.setBoolean(key, isChecked);
        });
    }

    public void searchKeyWorld(String keyWord) {
        if (appInfoList == null || appsAdapter == null) {
            appInfoList = new ArrayList<>();
            return;
        }

        List<AppInfo> filteredList =
            appInfoList.stream()
                .filter(appInfo -> appInfo.getAppName().toLowerCase().contains(keyWord.toLowerCase()))
                .collect(Collectors.toList());
        appsAdapter.submitList(filteredList);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        disposables.clear();
    }
}
