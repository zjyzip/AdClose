package com.close.hook.ads.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.data.DataSource
import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.data.model.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class BlockListViewModel(application: Application) : AndroidViewModel(application) {

    val dataSource: DataSource = DataSource.getDataSource(application)

    val searchQuery: MutableStateFlow<String> = MutableStateFlow("")

    val blackListLiveData: LiveData<List<Url>> = combine(
        dataSource.getUrlList(),
        searchQuery.debounce(300L).distinctUntilChanged()
    ) { urlList: List<Url>, query: String ->
        if (query.isBlank()) {
            urlList
        } else {
            urlList.filter {
                it.url.contains(query, ignoreCase = true) ||
                it.type.contains(query, ignoreCase = true)
            }
        }
    }
    .flowOn(Dispatchers.Default)
    .asLiveData(viewModelScope.coroutineContext + Dispatchers.Main)


    private val _requestList = MutableLiveData<List<BlockedRequest>>(emptyList())
    val requestList: LiveData<List<BlockedRequest>> = _requestList

    private val _requestSearchQuery = MutableStateFlow("")
    val requestSearchQuery: StateFlow<String> = _requestSearchQuery.asStateFlow()

    init {
        setupRequestSearchQueryObservation()
    }

    private fun setupRequestSearchQueryObservation() {
        viewModelScope.launch {
            _requestSearchQuery
                .debounce(300L)
                .distinctUntilChanged()
                .collect {
                }
        }
    }

    fun getFilteredRequestList(type: String): LiveData<List<BlockedRequest>> {
        return combine(
            _requestList.asFlow(),
            _requestSearchQuery
        ) { requests: List<BlockedRequest>, query: String ->
            val filteredByType = when (type) {
                "all" -> requests
                "block" -> requests.filter { request -> request.isBlocked == true }
                "pass" -> requests.filter { request -> request.isBlocked == false }
                else -> emptyList()
            }
            if (query.isBlank()) {
                filteredByType
            } else {
                filteredByType.filter { blockedRequest ->
                    blockedRequest.request.contains(query, ignoreCase = true) ||
                    blockedRequest.packageName.contains(query, ignoreCase = true) ||
                    blockedRequest.appName.contains(query, ignoreCase = true)
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .asLiveData(viewModelScope.coroutineContext + Dispatchers.Main)
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
        _requestList.value = _requestList.value.orEmpty().toMutableList().apply {
            add(0, item)
        }
    }

    fun onClearAllRequests() {
        if (_requestList.value?.isNotEmpty() == true) {
            _requestList.value = emptyList()
        }
    }
}
