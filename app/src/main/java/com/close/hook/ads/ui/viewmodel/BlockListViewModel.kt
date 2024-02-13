package com.close.hook.ads.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.data.database.UrlDatabase
import com.close.hook.ads.data.model.Item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BlockListViewModel(application: Application) : AndroidViewModel(application) {

    val blockList = ArrayList<Item>()
    val blackListLiveData: MutableLiveData<ArrayList<Item>> = MutableLiveData()

    private val urlDao by lazy {
        UrlDatabase.getDatabase(application).urlDao
    }

    init {
        getBlackList()
    }

    fun getBlackList() {
        viewModelScope.launch(Dispatchers.IO) {
            val urls = urlDao.loadAllList()
            val newList = urls.map { Item(it.type, it.url) }
            withContext(Dispatchers.Main) {
                blackListLiveData.value = ArrayList(newList)
            }
        }
    }
}
