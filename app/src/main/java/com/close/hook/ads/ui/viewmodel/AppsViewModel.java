package com.close.hook.ads.ui.viewmodel;

import android.app.Application;
import android.content.pm.PackageManager;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.LiveDataReactiveStreams;
import androidx.lifecycle.MutableLiveData;

import com.close.hook.ads.data.model.AppInfo;
import com.close.hook.ads.data.model.BlockedRequest;
import com.close.hook.ads.data.model.FilterBean;
import com.close.hook.ads.data.repository.AppRepository;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class AppsViewModel extends AndroidViewModel {
    public List<BlockedRequest> requestList = new ArrayList<>();
    public List<AppInfo> appInfoList = new ArrayList<>();
    public List<AppInfo> filterList = new ArrayList<>();
    private final AppRepository appRepository;
    private final LiveData<List<AppInfo>> userAppsLiveData;
    private final LiveData<List<AppInfo>> systemAppsLiveData;
    private final MutableLiveData<LoadState> loadState = new MutableLiveData<>();
    private final MutableLiveData<String> errorLiveData = new MutableLiveData<>();
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private String currentSearchKeyword = "";
    public List<String> sortList;
    public FilterBean filterBean;
    public Boolean isFilter = false;

    public AppsViewModel(Application application) {
        super(application);
        PackageManager packageManager = application.getPackageManager();
        appRepository = new AppRepository(packageManager);

        userAppsLiveData = loadAppsLiveData(() -> appRepository.getInstalledUserApps());

        systemAppsLiveData = loadAppsLiveData(() -> appRepository.getInstalledSystemApps());
    }

    private LiveData<List<AppInfo>> loadAppsLiveData(Callable<Observable<List<AppInfo>>> appsCallable) {
        return LiveDataReactiveStreams.fromPublisher(
                Observable.defer(() -> {
                    try {
                        return appsCallable.call();
                    } catch (Exception e) {
                        return Observable.error(e);
                    }
                })
                .toFlowable(BackpressureStrategy.BUFFER)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSubscribe(disposable -> loadState.postValue(LoadState.LOADING))
                .doOnError(error -> {
                    loadState.postValue(LoadState.ERROR);
                    errorLiveData.postValue(error.getMessage());
                })
                .onErrorReturnItem(Collections.emptyList())
                .doOnNext(list -> loadState.postValue(LoadState.COMPLETE))
        );
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

    public LiveData<String> getErrorLiveData() {
        return errorLiveData;
    }

    public String getCurrentSearchKeyword() {
        return currentSearchKeyword;
    }

    public void setCurrentSearchKeyword(String searchKeyword) {
        this.currentSearchKeyword = searchKeyword;
    }

    public void clearRequests() {
        requestList.clear();
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        compositeDisposable.clear();
    }

    public enum LoadState {
        LOADING, COMPLETE, ERROR
    }
}
