package com.close.hook.ads.ui.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.data.model.FilterBean
import com.close.hook.ads.data.repository.AppRepository
import com.close.hook.ads.util.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AppsViewModelNew(
    private val type: String,
    private val appRepository: AppRepository
) : ViewModel() {

    private var searchJob: Job? = null
    private var tmpList: List<AppInfo>? = null
    private val _appsLiveData = MutableLiveData<List<AppInfo>>()
    val appsLiveData: LiveData<List<AppInfo>> = _appsLiveData

    init {
        refreshApps()
    }

    fun refreshApps() {
        when (type) {
            "user" -> loadApps(false)
            "system" -> loadApps(true)
            "configured" -> loadApps()
        }
    }

    private fun loadApps(isSystem: Boolean? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            tmpList = appRepository.getInstalledApps(isSystem)
            if (type == "configured") tmpList = tmpList?.filter { it.isEnable == 1 }
            val filterList = ArrayList<String>().apply {
                if (PrefManager.configured) add("已配置")
                if (PrefManager.updated) add("最近更新")
                if (PrefManager.disabled) add("已禁用")
            }
            val filterBean = FilterBean().apply {
                title = PrefManager.order
                filter = filterList
            }
            updateList(filterBean, "", PrefManager.isReverse, 0)
        }
    }

    fun updateList(
        filterBean: FilterBean,
        keyWord: String,
        isReverse: Boolean,
        delayTime: Long = 300L
    ) {
        searchJob?.cancel()
        if (delayTime == 0L)
            updateAppList(filterBean, keyWord, isReverse)
        else {
            searchJob = viewModelScope.launch {
                delay(delayTime)
                updateAppList(filterBean, keyWord, isReverse)
            }
        }
    }

    private fun updateAppList(filterBean: FilterBean, keyWord: String, isReverse: Boolean) {
        if (tmpList == null) {
            tmpList = _appsLiveData.value
        }

        // search
        var updateList = if (keyWord.isBlank()) tmpList
        else tmpList?.filter {
            it.appName.lowercase().contains(keyWord)
                    || it.packageName.lowercase().contains(keyWord)
        } ?: emptyList()

        // filter
        if (filterBean.filter.isNotEmpty()) {
            updateList = filterBean.filter.fold(updateList) { list, title ->
                list?.filter { appInfo ->
                    getAppInfoFilter(title)(appInfo)
                }
            }
        }

        // compare
        val comparator =
            getAppInfoComparator(filterBean.title).let { if (isReverse) it.reversed() else it }

        _appsLiveData.postValue(updateList?.sortedWith(comparator))
    }

    private fun getAppInfoComparator(title: String): Comparator<AppInfo> {
        return when (title) {
            "应用大小" -> compareBy { it.size }
            "最近更新时间" -> compareBy { it.lastUpdateTime }
            "安装日期" -> compareBy { it.firstInstallTime }
            "Target 版本" -> compareBy { it.targetSdk }
            else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.appName }
        }
    }

    private fun getAppInfoFilter(title: String): (AppInfo) -> Boolean {
        val time = 3 * 24 * 3600L
        return { appInfo: AppInfo ->
            when (title) {
                "已配置" -> appInfo.isEnable == 1
                "最近更新" -> System.currentTimeMillis() / 1000 - appInfo.lastUpdateTime / 1000 < time
                "已禁用" -> appInfo.isAppEnable == 0
                else -> throw IllegalArgumentException()
            }
        }
    }

    fun clearSearch() {
        _appsLiveData.postValue(tmpList ?: emptyList())
        tmpList = null
    }

}

class AppsViewModelFactory(
    private val type: String,
    private val appRepository: AppRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppsViewModelNew::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AppsViewModelNew(
                type, appRepository
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}


