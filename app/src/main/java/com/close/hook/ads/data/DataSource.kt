package com.close.hook.ads.data

import android.content.Context
import androidx.lifecycle.LiveData
import com.close.hook.ads.data.database.UrlDatabase
import com.close.hook.ads.data.model.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DataSource(context: Context) {

    private val urlDao = UrlDatabase.getDatabase(context).urlDao

    val urlsLiveData: LiveData<List<Url>> = urlDao.loadAllList()

    fun getUrlList(): LiveData<List<Url>> {
        return urlsLiveData
    }

    fun addUrl(url: Url) {
        CoroutineScope(Dispatchers.IO).launch {
            urlDao.insert(url)
        }
    }

    fun removeList(list: List<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            list.forEach { url ->
                urlDao.delete(url)
            }
        }
    }

    fun removeUrl(url: Url) {
        CoroutineScope(Dispatchers.IO).launch {
            urlDao.delete(url.url)
        }
    }

    fun removeAll() {
        CoroutineScope(Dispatchers.IO).launch {
            urlDao.deleteAll()
        }
    }

    fun addListUrl(list: List<Url>) {
        CoroutineScope(Dispatchers.IO).launch {
            urlDao.insertAll(list)
        }
    }

    fun editUrl(data: Pair<Url, Url>) {
        CoroutineScope(Dispatchers.IO).launch {
            urlDao.delete(data.first.url)
            urlDao.insert(data.second)
        }
    }

    fun removeUrlString(url: String) {
        CoroutineScope(Dispatchers.IO).launch {
            urlDao.delete(url)
        }
    }

    companion object {
        @Volatile private var INSTANCE: DataSource? = null

        fun getDataSource(context: Context): DataSource {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DataSource(context).also { INSTANCE = it }
            }
        }
    }
}
