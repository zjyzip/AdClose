package com.close.hook.ads.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.data.DataSource
import com.close.hook.ads.data.model.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BlockListViewModel(application: Application) : AndroidViewModel(application) {

    val dataSource: DataSource = DataSource.getDataSource(application)

    private val _blackListSearchQuery = MutableStateFlow("")
    val blackListSearchQuery: StateFlow<String> = _blackListSearchQuery.asStateFlow()

    val blackList: StateFlow<List<Url>> = blackListSearchQuery
        .debounce(300L)
        .flatMapLatest { dataSource.searchUrls(it) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setBlackListSearchQuery(query: String) {
        _blackListSearchQuery.value = query
    }

    fun addUrl(url: Url) = viewModelScope.launch(Dispatchers.IO) {
        dataSource.addUrl(url)
    }

    fun removeList(list: List<Url>) = viewModelScope.launch(Dispatchers.IO) {
        dataSource.removeList(list)
    }

    fun removeUrl(url: Url) = viewModelScope.launch(Dispatchers.IO) {
        dataSource.removeUrl(url)
    }

    fun removeAll() = viewModelScope.launch(Dispatchers.IO) {
        dataSource.removeAll()
    }

    fun addListUrl(list: List<Url>) = viewModelScope.launch(Dispatchers.IO) {
        dataSource.addListUrl(list)
    }

    fun updateUrl(url: Url) = viewModelScope.launch(Dispatchers.IO) {
        dataSource.updateUrl(url)
    }

    fun removeUrlString(type: String, url: String) = viewModelScope.launch(Dispatchers.IO) {
        dataSource.removeUrlString(type, url)
    }
}
