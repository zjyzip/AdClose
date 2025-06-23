package com.close.hook.ads.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.R
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.data.repository.AppRepository
import com.close.hook.ads.util.PrefManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AppsViewModel(
    application: Application
) : AndroidViewModel(application) {

    var type: String = "user"
    private val updateParams = MutableStateFlow(Triple(Pair(0, emptyList<Int>()), Pair("", false), 0L))
    private val appRepository: AppRepository = AppRepository(
        packageManager = application.packageManager
    )

    private val _appsLiveData = MutableLiveData<List<AppInfo>>()
    val appsLiveData: LiveData<List<AppInfo>> get() = _appsLiveData

    init {
        observeUpdateParams()
        refreshApps()
    }

    fun refreshApps() {
        val filter = Pair(PrefManager.order, getFilterList())
        val params = Pair("", PrefManager.isReverse)
        val timestamp = System.currentTimeMillis()

        updateParams.value = updateParams.value.copy(first = filter, second = params, third = timestamp)
    }

    private fun observeUpdateParams() {
        viewModelScope.launch {
            updateParams
                .debounce(300L)
                .distinctUntilChanged()
                .flatMapLatest { (filter, params, _) ->
                    flow {
                        val isSystem = when (type) {
                            "user" -> false
                            "system" -> true
                            else -> null
                        }
                        val apps = appRepository.getInstalledApps(isSystem)

                        val filteredByTypeApps = if (type == "configured") {
                            apps.filter { it.isEnable == 1 }
                        } else {
                            apps
                        }
                        emit(filteredByTypeApps)
                    }.map { apps ->
                        appRepository.getFilteredAndSortedApps(
                            apps = apps,
                            filter = filter,
                            keyword = params.first,
                            isReverse = params.second
                        )
                    }
                }
                .collect { apps ->
                    _appsLiveData.postValue(apps)
                }
        }
    }

    private fun getFilterList(): List<Int> {
        return listOfNotNull(
            if (PrefManager.configured) R.string.filter_configured else null,
            if (PrefManager.updated) R.string.filter_recent_update else null,
            if (PrefManager.disabled) R.string.filter_disabled else null
        )
    }

    fun updateList(
        filter: Pair<Int, List<Int>>,
        keyword: String,
        isReverse: Boolean
    ) {
        updateParams.value = updateParams.value.copy(
            first = filter,
            second = Pair(keyword, isReverse),
            third = System.currentTimeMillis()
        )
    }
}
