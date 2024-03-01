package com.close.hook.ads.ui.viewmodel

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.close.hook.ads.data.DataSource
import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.data.model.Url

class BlockListViewModel(private val dataSource: DataSource) : ViewModel() {

    var requestList = ArrayList<BlockedRequest>()

    val blackListLiveData: LiveData<List<Url>> = dataSource.getUrlList()

    fun editUrl(data: Pair<Url, Url>) {
        dataSource.editUrl(data)
    }


    fun addUrl(url: Url) {
        dataSource.addUrl(url)
    }

    fun removeUrl(url: Url) {
        dataSource.removeUrl(url)
    }

    fun removeUrlString(url: String) {
        dataSource.removeUrlString(url)
    }

    fun removeList(list: List<String>) {
        dataSource.removeList(list)
    }

    fun removeAll() {
        dataSource.removeAll()
    }

    fun addListUrl(list: List<Url>) {
        dataSource.addListUrl(list)
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