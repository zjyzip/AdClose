package com.close.hook.ads.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.data.repository.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AppsViewModel(application: Application) : AndroidViewModel(application) {
    var requestList = ArrayList<BlockedRequest>()
    var appInfoList = ArrayList<AppInfo>()
    var filterList = ArrayList<AppInfo>()
    private val appRepository: AppRepository = AppRepository(application.packageManager)
    private val _userAppsLiveData = MutableLiveData<List<AppInfo>>()
    val userAppsLiveData: LiveData<List<AppInfo>> = _userAppsLiveData
    private val _systemAppsLiveData = MutableLiveData<List<AppInfo>>()
    val systemAppsLiveData: LiveData<List<AppInfo>> = _systemAppsLiveData
    private val _errorLiveData = MutableLiveData<String>()
    val errorLiveData: LiveData<String> = _errorLiveData
    lateinit var sortList: ArrayList<String>
    var isFilter = false

    init {
        loadUserApps()
        loadSystemApps()
    }

    fun refreshApps(currentTab: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (currentTab) {
                    "user" -> loadUserApps()
                    "system" -> loadSystemApps()
                    "configured" -> {
                        loadUserApps()
                        loadSystemApps()
                    }
                }
            } catch (e: Exception) {
                _errorLiveData.postValue(e.message)
            }
        }
    }

    private fun loadUserApps() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apps = appRepository.getInstalledApps(false)
                _userAppsLiveData.postValue(apps)
            } catch (e: Exception) {
                _errorLiveData.postValue(e.message)
            }
        }
    }

    private fun loadSystemApps() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apps = appRepository.getInstalledApps(true)
                _systemAppsLiveData.postValue(apps)
            } catch (e: Exception) {
                _errorLiveData.postValue(e.message)
            }
        }
    }
}
