package com.close.hook.ads.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.data.model.AppFilterState
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.data.repository.AppRepository
import com.close.hook.ads.preference.PrefManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
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

    private val _refreshTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _refreshTrigger
                .onStart { emit(Unit) }
                .flatMapLatest {
                    flow {
                        _uiState.update { it.copy(isLoading = true) }
                        emit(repo.getAllAppsFlow().first())
                    }
                }
                .combine(_filterState) { apps, filter ->
                    UiState(repo.filterAndSortApps(apps, filter), false)
                }
                .collect(_uiState::value::set)
        }
    }

    fun refreshApps() {
        _refreshTrigger.tryEmit(Unit)
    }

    fun setAppType(type: String) {
        _filterState.update { it.copy(appType = type) }
    }

    fun updateFilterAndSort(order: Int, keyword: String, reverse: Boolean) {
        _filterState.update {
            it.copy(filterOrder = order, keyword = keyword, isReverse = reverse)
        }
    }
}
