package com.close.hook.ads.data

import android.content.Context
import android.database.Cursor
import com.close.hook.ads.data.database.UrlDatabase
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.util.AppUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class DataSource(context: Context) {

    private val urlDao = UrlDatabase.getDatabase(context).urlDao

    fun searchUrls(searchText: String): Flow<List<Url>> =
        if (searchText.isBlank()) urlDao.loadAllList() else urlDao.searchUrls(searchText)

    suspend fun addUrl(url: Url) {
        if (!urlDao.isExist(url.type, url.url)) {
            urlDao.insert(url)
        }
    }

    suspend fun removeList(list: List<Url>) {
        if (list.isNotEmpty()) {
            urlDao.deleteList(list)
        }
    }

    suspend fun removeUrl(url: Url) {
        urlDao.deleteUrl(url)
    }

    suspend fun removeAll() {
        urlDao.deleteAll()
    }

    suspend fun addListUrl(list: List<Url>) {
        if (list.isNotEmpty()) {
            urlDao.insertAll(list)
        }
    }

    suspend fun updateUrl(url: Url) {
        urlDao.update(url)
    }

    suspend fun removeUrlString(type: String, url: String) {
        urlDao.deleteUrlString(type, url)
    }

    suspend fun isExist(type: String, url: String): Boolean =
        withContext(Dispatchers.IO) { urlDao.isExist(type, url) }

    suspend fun insertAll(urls: List<Url>): List<Long> =
        withContext(Dispatchers.IO) { urlDao.insertAll(urls) }

    suspend fun deleteAll(): Int =
        withContext(Dispatchers.IO) { urlDao.deleteAll() }

    fun getAllUrls(): List<Url> {
        return urlDao.findAllList()
    }

    companion object {
        @Volatile
        private var INSTANCE: DataSource? = null

        fun getDataSource(context: Context): DataSource {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DataSource(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
