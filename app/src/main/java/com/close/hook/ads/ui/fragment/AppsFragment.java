package com.close.hook.ads.ui.fragment;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.close.hook.ads.R;
import com.close.hook.ads.data.model.AppInfo;
import com.close.hook.ads.data.module.FilterBean;
import com.close.hook.ads.hook.preference.PreferencesHelper;
import com.close.hook.ads.ui.activity.MainActivity;
import com.close.hook.ads.ui.adapter.AppsAdapter;
import com.close.hook.ads.ui.viewmodel.AppsViewModel;
import com.close.hook.ads.util.AppUtils;
import com.close.hook.ads.util.LinearItemDecoration;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.button.MaterialButton;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import me.zhanghai.android.fastscroll.FastScrollerBuilder;

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
		if (appsViewModel == null) {
			appsViewModel = new ViewModelProvider(requireActivity()).get(AppsViewModel.class);
		}
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

		String currentSearchKeyword = appsViewModel.getCurrentSearchKeyword();
		if (!currentSearchKeyword.isEmpty()) {
			searchKeyWorld(currentSearchKeyword);
		}
	}

	private void setupViews(View view) {
		progressBar = view.findViewById(R.id.progress_bar);
		recyclerView = view.findViewById(R.id.recycler_view_apps);
	}

	private void setupRecyclerView() {
		new FastScrollerBuilder(recyclerView).useMd2Style().build();
		recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
		recyclerView.setAdapter(appsAdapter);
		int space = getResources().getDimensionPixelSize(ITEM_DECORATION_SPACE);
		recyclerView.addItemDecoration(new LinearItemDecoration(space));
	}

	private void setupLiveDataObservation() {
		LiveData<List<AppInfo>> appsLiveData = isSystemApp ? appsViewModel.getSystemAppsLiveData()
				: appsViewModel.getUserAppsLiveData();

		appsLiveData.observe(getViewLifecycleOwner(), appInfos -> {
			progressBar.setVisibility(View.GONE);
			appInfoList = appInfos;
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

		disposables.add(
				appsAdapter.getOnLongClickObservable().observeOn(AndroidSchedulers.mainThread()).subscribe(appInfo -> {
					Intent intent = new Intent();
					intent.setAction("android.settings.APPLICATION_DETAILS_SETTINGS");
					intent.setData(Uri.fromParts("package", appInfo.getPackageName(), null));
					try {
						requireContext().startActivity(intent);
					} catch (ActivityNotFoundException e) {
						Toast.makeText(requireContext(), "打开失败", Toast.LENGTH_SHORT).show();
						e.printStackTrace();
					}
				}));
	}

	private void showBottomSheetDialog(AppInfo appInfo) {
		BottomSheetDialog bottomSheetDialog = new BottomSheetDialog(requireContext());
		View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.bottom_dialog_switches, null);
		bottomSheetDialog.setContentView(dialogView);
        ImageButton closeButton = dialogView.findViewById(R.id.button_close);
        closeButton.setOnClickListener(view -> bottomSheetDialog.dismiss());
		setupListeners(dialogView, appInfo);
		bottomSheetDialog.show();
	}

	@SuppressLint("CommitPrefEdits")
	private void setupListeners(View dialogView, AppInfo appInfo) {
		@SuppressLint("WorldReadableFiles")
		PreferencesHelper prefsHelper = new PreferencesHelper(dialogView.getContext(), PREFERENCES_NAME);

		int[] checkBoxIds = { R.id.switch_one, R.id.switch_two, R.id.switch_three, R.id.switch_four, R.id.switch_five,
				R.id.switch_six, R.id.switch_seven };
		String[] prefKeys = { "switch_one_", "switch_two_", "switch_three_", "switch_four_", "switch_five_",
				"switch_six_", "switch_seven_" };

		// 初始化状态
		for (int i = 0; i < checkBoxIds.length; i++) {
			MaterialCheckBox checkBoxView = dialogView.findViewById(checkBoxIds[i]);
			String key = prefKeys[i] + appInfo.getPackageName();
			checkBoxView.setChecked(prefsHelper.getBoolean(key, false));
		}

		// 设置点击监听器
		MaterialButton buttonUpdate = dialogView.findViewById(R.id.button_update);
		buttonUpdate.setOnClickListener(view -> {
			// 保存状态
			for (int i = 0; i < checkBoxIds.length; i++) {
				MaterialCheckBox checkBoxView = dialogView.findViewById(checkBoxIds[i]);
				String key = prefKeys[i] + appInfo.getPackageName();
				prefsHelper.setBoolean(key, checkBoxView.isChecked());
			}

			Toast.makeText(dialogView.getContext(), "保存成功", Toast.LENGTH_SHORT).show();
		});
	}

	public void searchKeyWorld(String keyWord) {
		if (appsViewModel != null) {
			appsViewModel.setCurrentSearchKeyword(keyWord);
			List<AppInfo> safeAppInfoList = Optional.ofNullable(appInfoList).orElseGet(Collections::emptyList);

			disposables.add(Observable.fromIterable(safeAppInfoList)
					.filter(appInfo -> appInfo.getAppName().contains(keyWord)
							|| appInfo.getAppName().toLowerCase().contains(keyWord.toLowerCase())
							|| appInfo.getPackageName().toLowerCase().contains(keyWord.toLowerCase()))
					.toList().observeOn(AndroidSchedulers.mainThread())
					.subscribe(filteredList -> appsAdapter.submitList(filteredList),
							throwable -> Log.e("AppsFragment", "Error in searchKeyWorld", throwable)));
		} else {
			Log.e("AppsFragment", "AppsViewModel is null");
		}
	}

	public void updateSortList(FilterBean filterBean, String keyWord, Boolean isReverse) {
		Comparator<AppInfo> comparator = getAppInfoComparator(filterBean.getTitle());
		if (isReverse) {
			comparator = comparator.reversed();
		}

		List<AppInfo> safeAppInfoList = Optional.ofNullable(appInfoList).orElseGet(Collections::emptyList);

		if (!filterBean.getFilter().isEmpty()) {
			for (String title : filterBean.getFilter()) {
				safeAppInfoList = safeAppInfoList.stream().filter(getAppInfoFilter(title, keyWord))
						.collect(Collectors.toList());
			}
			disposables.add(Observable.fromIterable(safeAppInfoList).sorted(comparator).toList()
					.observeOn(AndroidSchedulers.mainThread())
					.subscribe(sortedList -> appsAdapter.submitList(sortedList),
							throwable -> Log.e("AppsFragment", "Error in updateSortList", throwable)));
		} else {
			disposables.add(Observable.fromIterable(safeAppInfoList).sorted(comparator).toList()
					.observeOn(AndroidSchedulers.mainThread())
					.subscribe(sortedList -> appsAdapter.submitList(sortedList),
							throwable -> Log.e("AppsFragment", "Error in updateSortList", throwable)));
		}

	}

	private Comparator<AppInfo> getAppInfoComparator(String title) {
		switch (title) {
		case "应用大小":
			return Comparator.comparingLong(AppInfo::getSize);
		case "最近更新时间":
			return Comparator.comparing(AppInfo::getLastUpdateTime);
		case "安装日期":
			return Comparator.comparing(AppInfo::getFirstInstallTime);
		case "Target 版本":
			return Comparator.comparingInt(AppInfo::getTargetSdk);
		default:
			return Comparator.comparing(AppInfo::getAppName, String.CASE_INSENSITIVE_ORDER);
		}
	}

	private Predicate<AppInfo> getAppInfoFilter(String title, String keyWord) {
		long time = 7 * 24 * 3600L;
		switch (title) {
			case "已配置":
				return appInfo -> appInfo.getAppName().toLowerCase().contains(keyWord.toLowerCase())
						&& appInfo.getIsEnable() == 1;
			case "最近更新":
				return appInfo -> appInfo.getAppName().toLowerCase().contains(keyWord.toLowerCase())
						&& (System.currentTimeMillis() / 1000 - appInfo.getLastUpdateTime() / 1000) < time;
			case "已禁用":
				return appInfo -> appInfo.getAppName().toLowerCase().contains(keyWord.toLowerCase())
						&& appInfo.getIsAppEnable() == 0;
			default:
				return appInfo -> appInfo.getAppName().toLowerCase().contains(keyWord.toLowerCase());
		}
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		disposables.clear();
	}
}
