package com.close.hook.ads.ui.viewmodel;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.LiveDataReactiveStreams;
import android.app.Application;
import android.content.pm.PackageManager;

import com.close.hook.ads.data.model.AppInfo;
import com.close.hook.ads.data.repository.AppRepository;

import java.util.List;

import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class AppsViewModel extends AndroidViewModel {
    private LiveData<List<AppInfo>> userAppsLiveData;
    private LiveData<List<AppInfo>> systemAppsLiveData;
    private final AppRepository appRepository;

    public AppsViewModel(Application application) {
        super(application);
        PackageManager packageManager = application.getPackageManager();
        appRepository = new AppRepository(packageManager);
        loadApps();
    }

    public LiveData<List<AppInfo>> getUserAppsLiveData() {
        return userAppsLiveData;
    }

    public LiveData<List<AppInfo>> getSystemAppsLiveData() {
        return systemAppsLiveData;
    }

    private void loadApps() {
        userAppsLiveData = LiveDataReactiveStreams.fromPublisher(appRepository.getInstalledUserApps().toFlowable(BackpressureStrategy.LATEST) // 指定背压策略
                .subscribeOn(Schedulers.io()));

        systemAppsLiveData = LiveDataReactiveStreams.fromPublisher(appRepository.getInstalledSystemApps().toFlowable(BackpressureStrategy.LATEST) // 指定背压策略
                .subscribeOn(Schedulers.io()));
    }
}
