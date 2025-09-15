package com.close.hook.ads.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.data.DataSource
import com.close.hook.ads.data.model.RequestInfo
import com.close.hook.ads.data.model.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RequestViewModel(application: Application) : AndroidViewModel(application) {

    val dataSource: DataSource = DataSource.getDataSource(application)

    private val _requestList = MutableStateFlow<List<RequestInfo>>(emptyList())
    val requestList: StateFlow<List<RequestInfo>> = _requestList.asStateFlow()

    private val _requestSearchQuery = MutableStateFlow("")
    val requestSearchQuery: StateFlow<String> = _requestSearchQuery.asStateFlow()

    fun getFilteredRequestList(filterType: String): StateFlow<List<RequestInfo>> {
        return combine(
            _requestList,
            _requestSearchQuery.debounce(300L)
        ) { requests, query ->
            requests.filter { request ->
                val matchesType = when (filterType) {
                    "all" -> true
                    "block" -> request.isBlocked == true
                    "pass" -> request.isBlocked == false
                    else -> false
                }
                val matchesQuery = query.isBlank() ||
                        request.request.contains(query, ignoreCase = true) ||
                        request.packageName.contains(query, ignoreCase = true) ||
                        request.appName.contains(query, ignoreCase = true)

                matchesType && matchesQuery
            }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    fun setRequestSearchQuery(query: String) {
        _requestSearchQuery.value = query
    }

    fun updateRequestList(item: RequestInfo) {
        _requestList.update { list ->
            listOf(item) + list
        }
    }

    fun onClearAllRequests() {
        _requestList.value = emptyList()
    }

    fun toggleBlockStatus(request: RequestInfo) = viewModelScope.launch(Dispatchers.IO) {
        val requestType = request.blockType.takeUnless { it.isNullOrEmpty() } ?: run {
            if (request.appName.trim().endsWith("DNS", ignoreCase = true)) "Domain" else "URL"
        }
        val urlToToggle = request.url ?: request.request

        val newIsBlocked = if (request.isBlocked == true) {
            dataSource.removeUrlString(requestType, urlToToggle)
            false
        } else {
            dataSource.addUrl(Url(requestType, urlToToggle))
            true
        }

        _requestList.update { currentList ->
            currentList.map {
                if (it.timestamp == request.timestamp) it.copy(isBlocked = newIsBlocked) else it
            }
        }
    }
}
