package com.close.hook.ads.ui.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.data.repository.AppRepository
import com.close.hook.ads.util.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppsViewModel(
    private val type: String,
    private val appRepository: AppRepository
) : ViewModel() {

    private var searchJob: Job? = null
    private var appList: List<AppInfo> = emptyList()
    private val _appsLiveData = MutableLiveData<List<AppInfo>>()
    val appsLiveData: LiveData<List<AppInfo>> = _appsLiveData

    init {
        refreshApps()
    }

    fun refreshApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val isSystem = when (type) {
                "user" -> false
                "system" -> true
                else -> null
            }
            appList = appRepository.getInstalledApps(isSystem)
            if (type == "configured") appList = appList.filter { it.isEnable == 1 }
            withContext(Dispatchers.Main) {
                updateLiveData(appList)
            }
        }
    }

    private fun updateLiveData(list: List<AppInfo>) {
        val filterList = ArrayList<String>().apply {
            if (PrefManager.configured) add("已配置")
            if (PrefManager.updated) add("最近更新")
            if (PrefManager.disabled) add("已禁用")
        }
        updateList(Pair(PrefManager.order, filterList), "", PrefManager.isReverse)
    }

    fun updateList(
        filter: Pair<String, List<String>>,
        keyWord: String,
        isReverse: Boolean,
        delayTime: Long = 300L
    ) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.Default) {
            if (delayTime > 0) {
                delay(delayTime)
            }
            val updatedList = appRepository.getFilteredAndSortedApps(
                apps = appList,
                filter = filter,
                keyword = keyWord,
                isReverse = isReverse
            )
            withContext(Dispatchers.Main) {
                _appsLiveData.value = updatedList
            }
        }
    }
}

class AppsViewModelFactory(
    private val type: String,
    private val appRepository: AppRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AppsViewModel(type, appRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
