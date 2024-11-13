package com.close.hook.ads.ui.viewmodel

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import com.close.hook.ads.data.DataSource
import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.data.model.Url
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class BlockListViewModel(val dataSource: DataSource) : ViewModel() {

    private val _requestList = MutableLiveData<List<BlockedRequest>>(emptyList())
    val requestList: LiveData<List<BlockedRequest>> = _requestList

    val blackListLiveData = dataSource.getUrlList().asLiveData()

    val searchQuery = MutableStateFlow("")

    fun addUrl(url: Url) {
        dataSource.addUrl(url)
    }

    fun removeList(list: List<Url>) {
        if (list.isNotEmpty()) {
            dataSource.removeList(list)
        }
    }

    fun removeUrl(url: Url) {
        dataSource.removeUrl(url)
    }

    fun removeAll() {
        dataSource.removeAll()
    }

    fun addListUrl(list: List<Url>) {
        if (list.isNotEmpty()) {
            dataSource.addListUrl(list)
        }
    }

    fun updateUrl(url: Url) {
        dataSource.updateUrl(url)
    }

    fun removeUrlString(type: String, url: String) {
        dataSource.removeUrlString(type, url)
    }

    fun updateRequestList(item: BlockedRequest) {
        _requestList.value = _requestList.value.orEmpty().toMutableList().apply {
            add(0, item)
        }
    }

    fun onClearAll() {
        if (_requestList.value?.isNotEmpty() == true) {
            _requestList.value = emptyList()
        }
    }
}

class UrlViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BlockListViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return BlockListViewModel(
                dataSource = DataSource.getDataSource(context)
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
