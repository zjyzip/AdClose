package com.close.hook.ads.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.data.DataSource
import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.data.model.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BlockListViewModel(application: Application) : AndroidViewModel(application) {

    val dataSource: DataSource = DataSource.getDataSource(application)

    private val _blackListSearchQuery = MutableStateFlow("")
    val blackListSearchQuery: StateFlow<String> = _blackListSearchQuery.asStateFlow()

    val blackList: StateFlow<List<Url>> = blackListSearchQuery
        .debounce(300L)
        .distinctUntilChanged()
        .flatMapLatest { query ->
            dataSource.searchUrls(query)
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )

    private val _requestList = MutableStateFlow<List<BlockedRequest>>(emptyList())
    val requestList: StateFlow<List<BlockedRequest>> = _requestList.asStateFlow()

    private val _requestSearchQuery = MutableStateFlow("")
    val requestSearchQuery: StateFlow<String> = _requestSearchQuery.asStateFlow()

    fun getFilteredRequestList(type: String): StateFlow<List<BlockedRequest>> {
        return combine(
            _requestList,
            _requestSearchQuery.debounce(300L).distinctUntilChanged()
        ) { requests, query ->
            val filteredByType = when (type) {
                "all" -> requests
                "block" -> requests.filter { it.isBlocked == true }
                "pass" -> requests.filter { it.isBlocked == false }
                else -> emptyList()
            }

            if (query.isBlank()) {
                filteredByType
            } else {
                filteredByType.filter {
                    it.request.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true) ||
                    it.appName.contains(query, ignoreCase = true)
                }
            }
        }.flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                emptyList()
            )
    }

    fun setBlackListSearchQuery(query: String) {
        _blackListSearchQuery.value = query
    }

    fun setRequestSearchQuery(query: String) {
        _requestSearchQuery.value = query
    }

    fun addUrl(url: Url) = viewModelScope.launch(Dispatchers.IO) {
        if (!dataSource.isExist(url.type, url.url)) {
            dataSource.addUrl(url)
        }
    }

    fun removeList(list: List<Url>) = viewModelScope.launch(Dispatchers.IO) {
        if (list.isNotEmpty()) {
            dataSource.removeList(list)
        }
    }

    fun removeUrl(url: Url) = viewModelScope.launch(Dispatchers.IO) {
        dataSource.removeUrl(url)
    }

    fun removeAll() = viewModelScope.launch(Dispatchers.IO) {
        dataSource.removeAll()
    }

    fun addListUrl(list: List<Url>) = viewModelScope.launch(Dispatchers.IO) {
        if (list.isNotEmpty()) {
            dataSource.addListUrl(list)
        }
    }

    fun updateUrl(url: Url) = viewModelScope.launch(Dispatchers.IO) {
        dataSource.updateUrl(url)
    }

    fun removeUrlString(type: String, url: String) = viewModelScope.launch(Dispatchers.IO) {
        dataSource.removeUrlString(type, url)
    }

    fun updateRequestList(item: BlockedRequest) {
        _requestList.update { list ->
            list.toMutableList().apply { add(0, item) }
        }
    }

    fun onClearAllRequests() {
        _requestList.value = emptyList()
    }
}
