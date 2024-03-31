package com.close.hook.ads.ui.viewmodel

import android.content.Context
import androidx.lifecycle.asLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.close.hook.ads.data.DataSource
import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.data.model.Url
import kotlinx.coroutines.flow.MutableStateFlow

class BlockListViewModel(val dataSource: DataSource) : ViewModel() {

    var requestList = ArrayList<BlockedRequest>()

    val blackListLiveData = dataSource.getUrlList().asLiveData()

    val searchQuery = MutableStateFlow("")

    fun addUrl(url: Url) {
        dataSource.addUrl(url)
    }

    fun removeList(list: List<Url>) {
        dataSource.removeList(list)
    }

    fun removeUrl(url: Url) {
        dataSource.removeUrl(url)
    }

    fun removeAll() {
        dataSource.removeAll()
    }

    fun addListUrl(list: List<Url>) {
        dataSource.addListUrl(list)
    }

    fun updateUrl(url: Url) {
        dataSource.updateUrl(url)
    }

    fun removeUrlString(type: String, url: String) {
        dataSource.removeUrlString(type, url)
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