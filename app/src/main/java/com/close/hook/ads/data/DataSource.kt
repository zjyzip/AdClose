package com.close.hook.ads.data

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.close.hook.ads.data.database.UrlDatabase
import com.close.hook.ads.data.model.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DataSource(context: Context) {

    private val urlDao by lazy {
        UrlDatabase.getDatabase(context).urlDao
    }
    private val urlsLiveData = MutableLiveData(emptyList<Url>())

    init {
        CoroutineScope(Dispatchers.IO).launch {
            urlsLiveData.postValue(urlDao.loadAllList())
        }
    }

    fun addUrl(url: Url) {
        CoroutineScope(Dispatchers.IO).launch {
            val currentList = urlsLiveData.value
            if (currentList == null) {
                urlsLiveData.postValue(listOf(url))
                urlDao.insert(url)
            } else {
                if (currentList.indexOf(url) == -1) {
                    val updatedList = currentList.toMutableList()
                    updatedList.add(0, url)
                    urlsLiveData.postValue(updatedList)
                    urlDao.insert(url)
                }
            }
        }
    }

    fun removeList(list: List<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            val currentList = urlsLiveData.value
            if (currentList != null) {
                val updatedList = currentList.toMutableList()
                list.forEach { url ->
                    val item = updatedList.find {
                        it.url == url
                    }
                    updatedList.remove(item)
                    urlDao.delete(url)
                }
                urlsLiveData.postValue(updatedList)
            }
        }
    }


    fun removeUrl(url: Url) {
        CoroutineScope(Dispatchers.IO).launch {
            val currentList = urlsLiveData.value
            if (currentList != null) {
                val updatedList = currentList.toMutableList()
                updatedList.remove(url)
                urlsLiveData.postValue(updatedList)
                urlDao.delete(url.url)
            }
        }
    }

    fun getUrlList(): LiveData<List<Url>> {
        return urlsLiveData
    }

    fun removeAll() {
        CoroutineScope(Dispatchers.IO).launch {
            urlDao.deleteAll()
            urlsLiveData.postValue(emptyList())
        }
    }

    fun addListUrl(list: List<Url>) {
        CoroutineScope(Dispatchers.IO).launch {
            val currentList = urlsLiveData.value
            if (currentList != null) {
                val updatedList = currentList.toMutableList()
                val sortList = list.filter {
                    updatedList.indexOf(it) == -1
                }
                urlDao.insertAll(sortList)
                updatedList.addAll(0, sortList)
                urlsLiveData.postValue(updatedList)
            }
        }
    }

    fun editUrl(data: Pair<Url, Url>) {
        CoroutineScope(Dispatchers.IO).launch {
            val currentList = urlsLiveData.value
            if (currentList != null) {
                val updatedList = currentList.toMutableList()
                updatedList.remove(data.first)
                updatedList.add(0, data.second)
                urlsLiveData.postValue(updatedList)
                urlDao.delete(data.first.url)
                urlDao.insert(data.second)
            }
        }
    }

    fun removeUrlString(url: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val currentList = urlsLiveData.value
            if (currentList != null) {
                val updatedList = currentList.toMutableList()
                val index = updatedList.indexOfFirst { it.url == url }
                if (index != -1) {
                    updatedList.removeAt(index)
                    urlsLiveData.postValue(updatedList)
                    urlDao.delete(url)
                }
            }
        }
    }

    companion object {
        private var INSTANCE: DataSource? = null

        fun getDataSource(context: Context): DataSource {
            return synchronized(DataSource::class) {
                val newInstance = INSTANCE ?: DataSource(context)
                INSTANCE = newInstance
                newInstance
            }
        }
    }
}