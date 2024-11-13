package com.close.hook.ads.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.R
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.data.repository.AppRepository
import com.close.hook.ads.util.PrefManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

class AppsViewModel(
    application: Application
) : AndroidViewModel(application) {

    var type: String = "user"
    private val updateParams =
        MutableStateFlow(Triple(Pair("", emptyList<String>()), Pair("", false), 0L))
    val appsLiveData: LiveData<List<AppInfo>> by lazy { setupAppsLiveData() }
    private val appRepository: AppRepository = AppRepository(
        packageManager = application.packageManager,
        context = application
    )

    init {
        refreshApps()
    }

    private fun setupAppsLiveData(): LiveData<List<AppInfo>> {
        return updateParams
            .debounce(500L)
            .distinctUntilChanged()
            .flatMapLatest { (filter, params, _) ->
                flow {
                    val isSystem = when (type) {
                        "user" -> false
                        "system" -> true
                        else -> null
                    }
                    val apps = appRepository.getInstalledApps(isSystem)
                        .filter { type != "configured" || it.isEnable == 1 }

                    val filteredSortedApps = appRepository.getFilteredAndSortedApps(
                        apps = apps,
                        filter = filter,
                        keyword = params.first,
                        isReverse = params.second
                    )
                    emit(filteredSortedApps)
                }.buffer()
            }
            .asLiveData(viewModelScope.coroutineContext)
    }

    fun refreshApps() {
        updateParams.value = Triple(
            Pair(PrefManager.order, getFilterList()),
            Pair("", PrefManager.isReverse),
            System.currentTimeMillis()
        )
    }

    private fun getFilterList(): List<String> {
        val context = getApplication<Application>()
        return listOfNotNull(
            if (PrefManager.configured) context.getString(R.string.filter_configured) else null,
            if (PrefManager.updated) context.getString(R.string.filter_recent_update) else null,
            if (PrefManager.disabled) context.getString(R.string.filter_disabled) else null
        )
    }

    fun updateList(
        filter: Pair<String, List<String>>,
        keyWord: String,
        isReverse: Boolean
    ) {
        val newParams = Triple(filter, Pair(keyWord, isReverse), System.currentTimeMillis())
        if (newParams != updateParams.value) {
            updateParams.value = newParams
        }
    }
}
