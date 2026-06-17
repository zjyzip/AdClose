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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RequestViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val MAX_REQUEST_LIST_SIZE = 1000
    }

    val dataSource: DataSource = DataSource.getDataSource(application)

    private val _requestList = MutableStateFlow<List<RequestInfo>>(emptyList())
    val requestList: StateFlow<List<RequestInfo>> = _requestList.asStateFlow()

    private val _requestSearchQuery = MutableStateFlow("")
    val requestSearchQuery: StateFlow<String> = _requestSearchQuery.asStateFlow()

    private val debouncedQuery = _requestSearchQuery.debounce(300L)

    // Query matching computed once; block/pass filter the same result.
    private val queryFilteredFlow = combine(_requestList, debouncedQuery) { requests, query ->
        if (query.isBlank()) requests
        else requests.filter { r ->
            r.request.contains(query, ignoreCase = true) ||
            r.packageName.contains(query, ignoreCase = true) ||
            r.appName.contains(query, ignoreCase = true)
        }
    }.flowOn(Dispatchers.Default)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    val filteredAll: StateFlow<List<RequestInfo>> =
        queryFilteredFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredBlock: StateFlow<List<RequestInfo>> =
        queryFilteredFlow.map { it.filter { r -> r.isBlocked == true } }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredPass: StateFlow<List<RequestInfo>> =
        queryFilteredFlow.map { it.filter { r -> r.isBlocked == false } }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun getFilteredFlow(type: String): StateFlow<List<RequestInfo>> = when (type) {
        "block" -> filteredBlock
        "pass" -> filteredPass
        else -> filteredAll
    }

    fun setRequestSearchQuery(query: String) {
        _requestSearchQuery.value = query
    }

    fun updateRequestList(item: RequestInfo) {
        _requestList.update { currentList ->
            if (item.responseCode != -1 && item.requestId.isNotEmpty()) {
                val idx = currentList.indexOfFirst {
                    it.requestId == item.requestId && it.responseCode == -1
                }
                if (idx >= 0) {
                    return@update ArrayList(currentList).also { it[idx] = item }
                }
            }
            buildList {
                add(item)
                addAll(currentList)
            }.take(MAX_REQUEST_LIST_SIZE)
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
                val matches = if (request.requestId.isNotEmpty()) it.requestId == request.requestId
                              else it.timestamp == request.timestamp
                if (matches) it.copy(isBlocked = newIsBlocked) else it
            }
        }
    }
}
