package com.close.hook.ads.data

import android.content.Context
import android.database.Cursor
import com.close.hook.ads.data.database.UrlDatabase
import com.close.hook.ads.data.model.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class DataSource(context: Context) {

    private val urlDao = UrlDatabase.getDatabase(context).urlDao

    fun getUrlList(): Flow<List<Url>> = urlDao.loadAllList()

    fun searchUrls(searchText: String): Flow<List<Url>> =
        if (searchText.isBlank()) urlDao.loadAllList() else urlDao.searchUrls(searchText)

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

    suspend fun isExist(type: String, url: String): Boolean =
        withContext(Dispatchers.IO) { urlDao.isExist(type, url) }

    suspend fun isExist(url: String): Boolean =
        withContext(Dispatchers.IO) { urlDao.isExist(url) }

    suspend fun findAllList(): Cursor =
        withContext(Dispatchers.IO) { urlDao.findAllList() }

    suspend fun deleteById(id: Long): Int =
        withContext(Dispatchers.IO) { urlDao.deleteById(id) }

    suspend fun insert(url: Url): Long =
        withContext(Dispatchers.IO) { urlDao.insert(url) }

    suspend fun insertAll(urls: List<Url>): List<Long> =
        withContext(Dispatchers.IO) { urlDao.insertAll(urls) }

    suspend fun update(url: Url): Int =
        withContext(Dispatchers.IO) { urlDao.update(url) }

    suspend fun deleteList(list: List<Url>): Int =
        withContext(Dispatchers.IO) { urlDao.deleteList(list) }

    suspend fun deleteUrl(url: Url): Int =
        withContext(Dispatchers.IO) { urlDao.deleteUrl(url) }

    suspend fun deleteUrlString(type: String, url: String): Int =
        withContext(Dispatchers.IO) { urlDao.deleteUrlString(type, url) }

    suspend fun deleteAll(): Int =
        withContext(Dispatchers.IO) { urlDao.deleteAll() }

    suspend fun findMatchByUrlPrefix(testUrl: String): Url? =
        withContext(Dispatchers.IO) { urlDao.findMatchByUrlPrefix(testUrl) }

    suspend fun findMatchByHost(testUrl: String): Url? =
        withContext(Dispatchers.IO) { urlDao.findMatchByHost(testUrl) }

    suspend fun findMatchByKeyword(testUrl: String): Url? =
        withContext(Dispatchers.IO) { urlDao.findMatchByKeyword(testUrl) }

    suspend fun findByExactUrlSuspend(url: String): Url? =
        withContext(Dispatchers.IO) { urlDao.findMatchByUrlPrefix(url) }

    suspend fun findByUrlContainingSuspend(keyword: String): Url? =
        withContext(Dispatchers.IO) { urlDao.findMatchByKeyword(keyword) }

    suspend fun getUrlListOnce(): List<Url> =
        withContext(Dispatchers.IO) { urlDao.loadAllList().first() }

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
