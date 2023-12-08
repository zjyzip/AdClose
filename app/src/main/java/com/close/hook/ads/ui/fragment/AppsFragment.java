package com.close.hook.ads.ui.fragment;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.close.hook.ads.R;
import com.close.hook.ads.data.model.AppInfo;
import com.close.hook.ads.hook.preference.PreferencesHelper;
import com.close.hook.ads.ui.activity.MainActivity;
import com.close.hook.ads.ui.adapter.AppsAdapter;
import com.close.hook.ads.ui.viewmodel.AppsViewModel;
import com.close.hook.ads.util.AppUtils;
import com.close.hook.ads.util.LinearItemDecoration;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import java.util.stream.Stream;

public class AppsFragment extends Fragment {

	private static final String PREFERENCES_NAME = "com.close.hook.ads_preferences";
	private static final String ARG_IS_SYSTEM_APP = "IS_SYSTEM_APP";
	private static final int ITEM_DECORATION_SPACE = R.dimen.normal_space;

	private final CompositeDisposable disposables = new CompositeDisposable();
	private AppsViewModel appsViewModel;
	private RecyclerView recyclerView;
	private AppsAdapter appsAdapter;
	private ProgressBar progressBar;
	private List<AppInfo> appInfoList;
	private boolean isSystemApp;

	public static AppsFragment newInstance(boolean isSystemApp) {
		AppsFragment fragment = new AppsFragment();
		Bundle args = new Bundle();
		args.putBoolean(ARG_IS_SYSTEM_APP, isSystemApp);
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (getArguments() != null) {
			isSystemApp = getArguments().getBoolean(ARG_IS_SYSTEM_APP);
		}
		appsAdapter = new AppsAdapter();
		appsViewModel = new ViewModelProvider(requireActivity()).get(AppsViewModel.class);
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_apps, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		setupViews(view);
		setupRecyclerView();
		setupLiveDataObservation();
		setupAdapterItemClick();
	}

	private void setupViews(View view) {
		progressBar = view.findViewById(R.id.progress_bar);
		recyclerView = view.findViewById(R.id.recycler_view_apps);
	}

	private void setupRecyclerView() {
		recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
		recyclerView.setAdapter(appsAdapter);
		if (recyclerView.getItemDecorationCount() == 0) {
			int space = getResources().getDimensionPixelSize(ITEM_DECORATION_SPACE);
			recyclerView.addItemDecoration(new LinearItemDecoration(space));
		}
	}

	private void setupLiveDataObservation() {
		LiveData<List<AppInfo>> appsLiveData = isSystemApp ? appsViewModel.getSystemAppsLiveData()
				: appsViewModel.getUserAppsLiveData();

		appsLiveData.observe(getViewLifecycleOwner(), appInfos -> {
			progressBar.setVisibility(View.GONE);
			appInfoList = new ArrayList<>(appInfos);
			appsAdapter.submitList(appInfos);
		});
	}

	private void setupAdapterItemClick() {
		disposables
				.add(appsAdapter.getOnClickObservable().observeOn(AndroidSchedulers.mainThread()).subscribe(appInfo -> {
					if (MainActivity.isModuleActivated()) {
						showBottomSheetDialog(appInfo);
					} else {
						AppUtils.showToast(requireContext(), "模块尚未被激活");
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

		int[] switchIds = { R.id.switch_one, R.id.switch_two, R.id.switch_three, R.id.switch_four, R.id.switch_five,
				R.id.switch_six, R.id.switch_seven };
		String[] prefKeys = { "switch_one_", "switch_two_", "switch_three_", "switch_four_", "switch_five_",
				"switch_six_", "switch_seven_" };

		for (int i = 0; i < switchIds.length; i++) {
			MaterialSwitch switchView = dialogView.findViewById(switchIds[i]);
			String key = prefKeys[i] + appInfo.getPackageName();
			setupSwitch(switchView, key, prefsHelper);
		}
	}

	private void setupSwitch(MaterialSwitch switchView, String key, PreferencesHelper prefsHelper) {
		switchView.setChecked(prefsHelper.getBoolean(key, false));
		switchView.setOnCheckedChangeListener((buttonView, isChecked) -> {
			prefsHelper.setBoolean(key, isChecked);
			// TO DO : update isEnable value
		});
	}

	public void searchKeyWorld(String keyWord) {
		if (appInfoList == null) {
			appInfoList = new ArrayList<>();
		}

		if (appsAdapter != null) {
			String lowerCaseKeyword = keyWord.toLowerCase();
			List<AppInfo> filteredList = appInfoList.stream()
					.filter(appInfo -> appInfo.getAppName().toLowerCase().contains(lowerCaseKeyword))
					.collect(Collectors.toList());
			appsAdapter.submitList(filteredList);
		}
	}

	public void updateSortList(String title, String keyWord, Boolean isReverse) {
		if (appInfoList == null) {
			appInfoList = new ArrayList<>();
		}

		Comparator<AppInfo> comparator = null;
		switch (title) {
		case "应用名称":
			comparator = Comparator.comparing(AppInfo::getAppName, String.CASE_INSENSITIVE_ORDER);
			break;
		case "已配置":
			comparator = Comparator.comparingInt(AppInfo::getIsEnable);
			break;
		case "应用大小":
			comparator = Comparator.comparingLong(AppInfo::getSize);
			break;
		case "最近更新时间":
			comparator = Comparator.comparing(AppInfo::getLastUpdateTime);
			break;
		case "安装日期":
			comparator = Comparator.comparing(AppInfo::getFirstInstallTime);
			break;
		case "Target 版本":
			comparator = Comparator.comparingInt(AppInfo::getTargetSdk);
			break;
		}

		if (comparator != null) {
			if (isReverse) {
				comparator = comparator.reversed();
			}
			List<AppInfo> sortedList = appInfoList.stream().sorted(comparator).collect(Collectors.toList());
			appsAdapter.submitList(sortedList);
		}
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		disposables.clear();
	}
}
