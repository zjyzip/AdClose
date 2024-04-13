package com.close.hook.ads.data

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import com.close.hook.ads.BlockedBean
import com.close.hook.ads.closeApp
import com.close.hook.ads.data.database.UrlDatabase
import com.close.hook.ads.data.model.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow

class DataSource(context: Context) {
    private val urlDao = UrlDatabase.getDatabase(context).urlDao

    fun getUrlList(): Flow<List<Url>> = urlDao.loadAllList()

    fun addUrl(url: Url) {
        CoroutineScope(Dispatchers.IO).launch {
            if (!urlDao.isExist(url.type, url.url))
                urlDao.insert(url)
        }
    }

    fun removeList(list: List<Url>) {
        CoroutineScope(Dispatchers.IO).launch {
            urlDao.deleteList(list)
        }
    }

    fun removeUrl(url: Url) {
        CoroutineScope(Dispatchers.IO).launch {
            urlDao.deleteUrl(url)
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

    fun updateUrl(url: Url) {
        CoroutineScope(Dispatchers.IO).launch {
            urlDao.update(url)
        }
    }

    fun removeUrlString(type: String, url: String) {
        CoroutineScope(Dispatchers.IO).launch {
            urlDao.deleteUrlString(type, url)
        }
    }

    fun search(searchText: String): List<Url> {
        return urlDao.searchUrls(searchText)
    }

    fun isExist(type: String, url: String): Boolean {
        return urlDao.isExist(type, url)
    }

    fun checkIsBlocked(type: String, value: String): BlockedBean {
        var isBlocked = false
        var blockedType: String? = null
        var blockedValue: String? = null
        run breaking@{
            urlDao.getAllUrls().forEach {
                when (it.type) {
                    "KeyWord" -> {
                        if (it.url in value) {
                            isBlocked = true
                            return@breaking
                        }
                    }

                    "Domain" -> {
                        if (type == "Domain" && value == it.url) {
                            isBlocked = true
                            return@breaking
                        }
                    }

                    "URL" -> {
                        if (type == "URL" && value == it.url) {
                            isBlocked = true
                            return@breaking
                        }
                    }
                }
                blockedType = it.type
                blockedValue = it.url
            }
        }
        return BlockedBean(isBlocked, blockedType, blockedValue)
    }

    companion object {
        @Volatile
        private var INSTANCE: DataSource? = null

        fun getDataSource(context: Context): DataSource {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DataSource(context).also { INSTANCE = it }
            }
        }
    }
}
