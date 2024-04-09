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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class AppsViewModel(
    val type: String,
    private val appRepository: AppRepository
) : ViewModel() {

    private var appList: List<AppInfo> = emptyList()
    private val _appsLiveData = MutableLiveData<List<AppInfo>>()
    val appsLiveData: LiveData<List<AppInfo>> = _appsLiveData

    private val updateParams = MutableStateFlow(Triple(Pair("", listOf("")), Pair("", false), 0L))

    init {
        refreshApps()
        handleUpdateList()
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

            val filterList = getFilterList()
            updateList(Pair(PrefManager.order, filterList), "", PrefManager.isReverse)
        }
    }

    private fun getFilterList(): List<String> {
        return listOfNotNull(
            if (PrefManager.configured) "已配置" else null,
            if (PrefManager.updated) "最近更新" else null,
            if (PrefManager.disabled) "已禁用" else null
        )
    }

    private fun handleUpdateList() {
        viewModelScope.launch {
            updateParams
                .debounce(300L)
                .distinctUntilChanged()
                .flatMapLatest { (filter, params, _) ->
                    flow {
                        emit(
                            appRepository.getFilteredAndSortedApps(
                                apps = appList,
                                filter = filter,
                                keyword = params.first,
                                isReverse = params.second
                            )
                        )
                    }
                }
                .flowOn(Dispatchers.Default)
                .collect { updatedList ->
                    _appsLiveData.postValue(updatedList)
                }
        }
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
