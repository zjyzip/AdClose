package com.close.hook.ads.ui.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.data.repository.AppRepository
import com.close.hook.ads.util.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class AppsViewModel(
    val type: String,
    private val appRepository: AppRepository
) : ViewModel() {

    private val updateParams = MutableStateFlow(Triple(Pair("", emptyList<String>()), Pair("", false), 0L))
    val appsLiveData: LiveData<List<AppInfo>>

    init {
        refreshApps()
        appsLiveData = setupAppsLiveData()
    }

    private fun setupAppsLiveData(): LiveData<List<AppInfo>> {
        return updateParams
            .debounce(300L)
            .distinctUntilChanged()
            .flatMapLatest { (filter, params, _) ->
                flow {
                    val apps = when (type) {
                        "user" -> appRepository.getInstalledApps(false)
                        "system" -> appRepository.getInstalledApps(true)
                        else -> emptyList()
                    }.filter { type != "configured" || it.isEnable == 1 }
                    
                    val filteredSortedApps = appRepository.getFilteredAndSortedApps(
                        apps = apps,
                        filter = filter,
                        keyword = params.first,
                        isReverse = params.second
                    )
                    emit(filteredSortedApps)
                }
            }
            .flowOn(Dispatchers.Default)
            .asLiveData(viewModelScope.coroutineContext)
    }

    fun refreshApps() {
        val filterList = listOfNotNull(
            if (PrefManager.configured) "已配置" else null,
            if (PrefManager.updated) "最近更新" else null,
            if (PrefManager.disabled) "已禁用" else null
        )
        updateParams.value = Triple(Pair(PrefManager.order, filterList), Pair("", PrefManager.isReverse), System.currentTimeMillis())
    }

    fun updateList(
        filter: Pair<String, List<String>>,
        keyWord: String,
        isReverse: Boolean
    ) {
        updateParams.value = Triple(filter, Pair(keyWord, isReverse), System.currentTimeMillis())
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
