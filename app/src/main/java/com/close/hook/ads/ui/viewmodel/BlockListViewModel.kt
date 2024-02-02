package com.close.hook.ads.ui.viewmodel

import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.data.database.UrlDatabase
import com.close.hook.ads.data.model.Item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BlockListViewModel : ViewModel() {

    val blockList = ArrayList<Item>()

    val blackListLiveData: MutableLiveData<ArrayList<String>> = MutableLiveData()
    fun getBlackList(context: Context) {
        val urlDao = UrlDatabase.getDatabase(context).urlDao
        val newList = ArrayList<String>()
        viewModelScope.launch(Dispatchers.IO) {
            urlDao.loadAllList().forEach {
                newList.add(it.url)
            }
            withContext(Dispatchers.Main) {
                blackListLiveData.value = newList
            }
        }
    }

}