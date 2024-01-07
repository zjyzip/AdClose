package com.close.hook.ads.ui.viewmodel;

import android.app.Application;
import android.content.pm.PackageManager;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.LiveDataReactiveStreams;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;

import com.close.hook.ads.data.model.AppInfo;
import com.close.hook.ads.data.model.BlockedRequest;
import com.close.hook.ads.data.repository.AppRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class AppsViewModel extends AndroidViewModel {

	public HashMap<String, Integer> checkHashMap = new HashMap<>();
	public List<BlockedRequest> requestList = new ArrayList<>();
	public List<AppInfo> appInfoList = new ArrayList<>();

	private final AppRepository appRepository;
    private final LiveData<List<AppInfo>> userAppsLiveData;
    private final LiveData<List<AppInfo>> systemAppsLiveData;
	private final MutableLiveData<LoadState> loadState = new MutableLiveData<>();
	private String currentSearchKeyword = "";

    public AppsViewModel(Application application) {
        super(application);
        PackageManager packageManager = application.getPackageManager();
        appRepository = new AppRepository(packageManager);

        userAppsLiveData = LiveDataReactiveStreams
            .fromPublisher(appRepository.getInstalledUserApps()
            .toFlowable(BackpressureStrategy.LATEST)
            .subscribeOn(Schedulers.io())
            .onErrorReturnItem(Collections.emptyList()));

        systemAppsLiveData = LiveDataReactiveStreams
            .fromPublisher(appRepository.getInstalledSystemApps()
            .toFlowable(BackpressureStrategy.LATEST)
            .subscribeOn(Schedulers.io())
            .onErrorReturnItem(Collections.emptyList()));
    }

    public LiveData<List<AppInfo>> getUserAppsLiveData() {
        return userAppsLiveData;
    }

    public LiveData<List<AppInfo>> getSystemAppsLiveData() {
        return systemAppsLiveData;
    }

	public LiveData<LoadState> getLoadState() {
		return loadState;
	}

	public String getCurrentSearchKeyword() {
		return currentSearchKeyword;
	}

	public void setCurrentSearchKeyword(String searchKeyword) {
		this.currentSearchKeyword = searchKeyword;
	}

	public enum LoadState {
		LOADING, COMPLETE, ERROR
	}
}
