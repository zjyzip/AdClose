package com.close.hook.ads.ui.viewmodel;

import android.app.Application;
import android.content.pm.PackageManager;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.LiveDataReactiveStreams;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;

import com.close.hook.ads.data.model.AppInfo;
import com.close.hook.ads.data.repository.AppRepository;

import java.util.Collections;
import java.util.List;

import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class AppsViewModel extends AndroidViewModel {
	private final AppRepository appRepository;
	private final MediatorLiveData<List<AppInfo>> userAppsLiveData = new MediatorLiveData<>();
	private final MediatorLiveData<List<AppInfo>> systemAppsLiveData = new MediatorLiveData<>();
	private final MutableLiveData<LoadState> loadState = new MutableLiveData<>();
	private String currentSearchKeyword = "";

	public AppsViewModel(Application application) {
		super(application);
		PackageManager packageManager = application.getPackageManager();
		appRepository = new AppRepository(packageManager);
		loadUserApps();
		loadSystemApps();
	}

	private void loadUserApps() {
		LiveData<List<AppInfo>> source = LiveDataReactiveStreams
				.fromPublisher(appRepository.getInstalledUserApps().toFlowable(BackpressureStrategy.LATEST)
						.subscribeOn(Schedulers.io()).doOnNext(appInfos -> loadState.postValue(LoadState.COMPLETE))
						.doOnError(throwable -> loadState.postValue(LoadState.ERROR))
						.onErrorReturnItem(Collections.emptyList()));
		userAppsLiveData.addSource(source, userAppsLiveData::setValue);
	}

	private void loadSystemApps() {
		LiveData<List<AppInfo>> source = LiveDataReactiveStreams
				.fromPublisher(appRepository.getInstalledSystemApps().toFlowable(BackpressureStrategy.LATEST)
						.doOnSubscribe(disposable -> loadState.postValue(LoadState.LOADING))
						.subscribeOn(Schedulers.io()).doOnError(throwable -> loadState.postValue(LoadState.ERROR))
						.onErrorReturnItem(Collections.emptyList()));
		systemAppsLiveData.addSource(source, systemAppsLiveData::setValue);
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
