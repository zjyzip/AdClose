package com.close.hook.ads.ui.viewmodel;

import android.app.Application;
import android.content.pm.PackageManager;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.LiveDataReactiveStreams;

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
    }

    public LiveData<List<AppInfo>> getUserAppsLiveData() {
        if (userAppsLiveData == null) {
            userAppsLiveData = loadUserApps();
        }
        return userAppsLiveData;
    }

    public LiveData<List<AppInfo>> getSystemAppsLiveData() {
        if (systemAppsLiveData == null) {
            systemAppsLiveData = loadSystemApps();
        }
        return systemAppsLiveData;
    }

    private LiveData<List<AppInfo>> loadUserApps() {
        return LiveDataReactiveStreams.fromPublisher(
                appRepository.getInstalledUserApps()
                        .toFlowable(BackpressureStrategy.LATEST)
                        .subscribeOn(Schedulers.io())
        );
    }

    private LiveData<List<AppInfo>> loadSystemApps() {
        return LiveDataReactiveStreams.fromPublisher(
                appRepository.getInstalledSystemApps()
                        .toFlowable(BackpressureStrategy.LATEST)
                        .subscribeOn(Schedulers.io())
        );
    }
}
