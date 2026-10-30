package com.close.hook.ads.data

import android.content.Context
import android.net.Uri
import com.close.hook.ads.data.database.UrlDatabase
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.provider.UrlContentProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Locale

class DataSource(context: Context) {

    private val appContext = context.applicationContext
    private val urlDao = UrlDatabase.getDatabase(appContext).urlDao

    fun searchUrls(searchText: String): Flow<List<Url>> =
        if (searchText.isBlank()) urlDao.loadAllList() else urlDao.searchUrls(searchText)

    suspend fun addUrl(url: Url): Boolean {
        val normalized = url.copy(type = canonicalType(url.type))
        return (urlDao.insertOrIgnore(normalized) > 0L).also {
            if (it) notifyRulesChanged()
        }
    }

    suspend fun removeList(list: List<Url>) {
        if (list.isNotEmpty() && urlDao.deleteList(list) > 0) notifyRulesChanged()
    }

    suspend fun removeUrl(url: Url) {
        if (urlDao.deleteUrl(url) > 0) notifyRulesChanged()
    }

    suspend fun removeAll() {
        if (urlDao.deleteAll() > 0) notifyRulesChanged()
    }

    suspend fun addListUrl(list: List<Url>) {
        if (list.isNotEmpty()) {
            urlDao.insertAll(list.map { it.copy(type = canonicalType(it.type)) })
            notifyRulesChanged()
        }
    }

    suspend fun updateUrl(url: Url) {
        if (urlDao.update(url.copy(type = canonicalType(url.type))) > 0) notifyRulesChanged()
    }

    suspend fun removeUrlString(type: String, url: String) {
        if (urlDao.deleteUrlString(canonicalType(type), url) > 0) notifyRulesChanged()
    }

    suspend fun isExist(type: String, url: String): Boolean =
        withContext(Dispatchers.IO) { urlDao.isExist(canonicalType(type), url) }

    suspend fun insertAll(urls: List<Url>): List<Long> =
        withContext(Dispatchers.IO) {
            urlDao.insertAll(urls).also { if (urls.isNotEmpty()) notifyRulesChanged() }
        }

    suspend fun deleteAll(): Int =
        withContext(Dispatchers.IO) {
            urlDao.deleteAll().also { if (it > 0) notifyRulesChanged() }
        }

    suspend fun getAllUrls(): List<Url> =
        withContext(Dispatchers.IO) { urlDao.findAllList() }

    private fun canonicalType(type: String): String = when (type.trim().lowercase(Locale.ROOT)) {
        "url" -> "URL"
        "domain" -> "Domain"
        "keyword" -> "KeyWord"
        else -> type.trim()
    }

    private fun notifyRulesChanged() {
        appContext.contentResolver.notifyChange(
            Uri.parse("content://${UrlContentProvider.AUTHORITY}/${UrlContentProvider.URL_TABLE_NAME}"),
            null
        )
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
