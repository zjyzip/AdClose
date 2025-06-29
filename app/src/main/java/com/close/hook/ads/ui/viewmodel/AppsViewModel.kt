package com.close.hook.ads.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.data.model.AppFilterState
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.data.repository.AppRepository
import com.close.hook.ads.util.PrefManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppsViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val apps: List<AppInfo> = emptyList(),
        val isLoading: Boolean = false
    )

    private val repo = AppRepository(application.packageManager)

    private val _filterState = MutableStateFlow(
        AppFilterState(
            appType = "user",
            filterOrder = PrefManager.order,
            isReverse = PrefManager.isReverse,
            keyword = "",
            showConfigured = PrefManager.configured,
            showUpdated = PrefManager.updated,
            showDisabled = PrefManager.disabled
        )
    )

    private val appsFlow = _filterState
        .debounce(100L)
        .distinctUntilChanged()
        .combine(repo.getAllAppsFlow()) { filter, all ->
            repo.filterAndSortApps(all, filter)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val uiState: StateFlow<UiState> = combine(appsFlow, repo.isLoading) { list, loading ->
        UiState(list, loading)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState())

    fun setAppType(type: String) {
        _filterState.update { it.copy(appType = type) }
    }

    fun updateFilterAndSort(order: Int, keyword: String, reverse: Boolean) {
        _filterState.update {
            it.copy(filterOrder = order, keyword = keyword, isReverse = reverse)
        }
    }

    fun refreshApps() {
        viewModelScope.launch {
            repo.triggerRefresh()
            _filterState.update {
                it.copy(
                    filterOrder = PrefManager.order,
                    isReverse = PrefManager.isReverse,
                    showConfigured = PrefManager.configured,
                    showUpdated = PrefManager.updated,
                    showDisabled = PrefManager.disabled
                )
            }
        }
    }

    init {
        refreshApps()
    }
}
