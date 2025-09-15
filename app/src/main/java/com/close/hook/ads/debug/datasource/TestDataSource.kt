package com.close.hook.ads.debug.datasource

import com.close.hook.ads.data.dao.UrlDao
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.util.AppUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TestDataSource(private val testUrlDao: UrlDao) {

    suspend fun insertAll(urls: List<Url>): List<Long> = withContext(Dispatchers.IO) {
        testUrlDao.insertAll(urls)
    }

    suspend fun deleteAll(): Int = withContext(Dispatchers.IO) {
        testUrlDao.deleteAll()
    }

    suspend fun getUrlListOnce(): List<Url> = withContext(Dispatchers.IO) {
        testUrlDao.findAllList()
    }

    suspend fun existsUrlMatch(fullUrl: String): Boolean = withContext(Dispatchers.IO) {
        testUrlDao.existsUrlMatch(fullUrl)
    }

    suspend fun existsDomainMatch(inputUrl: String): Boolean = withContext(Dispatchers.IO) {
        val host = AppUtils.extractHostOrSelf(inputUrl)
        testUrlDao.existsDomainMatch(host)
    }

    suspend fun existsKeywordMatch(value: String): Boolean = withContext(Dispatchers.IO) {
        testUrlDao.existsKeywordMatch(value)
    }
}
