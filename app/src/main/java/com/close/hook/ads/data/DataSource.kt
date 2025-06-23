package com.close.hook.ads.data

import android.content.Context
import com.close.hook.ads.data.database.UrlDatabase
import com.close.hook.ads.data.model.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class DataSource(context: Context) {
    private val urlDao = UrlDatabase.getDatabase(context).urlDao

    fun getUrlList(): Flow<List<Url>> = urlDao.loadAllList()

    suspend fun addUrl(url: Url) {
        if (!urlDao.isExist(url.type, url.url)) {
            urlDao.insert(url)
        }
    }

    suspend fun removeList(list: List<Url>) {
        urlDao.deleteList(list)
    }

    suspend fun removeUrl(url: Url) {
        urlDao.deleteUrl(url)
    }

    suspend fun removeAll() {
        urlDao.deleteAll()
    }

    suspend fun addListUrl(list: List<Url>) {
        urlDao.insertAll(list)
    }

    suspend fun updateUrl(url: Url) {
        urlDao.update(url)
    }

    suspend fun removeUrlString(type: String, url: String) {
        urlDao.deleteUrlString(type, url)
    }

    fun search(searchText: String, offset: Int, limit: Int): Flow<List<Url>> = urlDao.searchUrls(searchText, offset, limit)

    suspend fun isExist(type: String, url: String): Boolean {
        return withContext(Dispatchers.IO) {
            urlDao.isExist(type, url)
        }
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
