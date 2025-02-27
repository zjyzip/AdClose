package com.close.hook.ads.provider

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import com.close.hook.ads.data.dao.UrlDao
import com.close.hook.ads.data.database.UrlDatabase
import com.close.hook.ads.data.model.Url

class UrlContentProvider : ContentProvider() {

    private lateinit var urlDao: UrlDao

    override fun onCreate(): Boolean {
        context?.let { ctx ->
            urlDao = UrlDatabase.getDatabase(ctx).urlDao
        } ?: return false
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        if (!::urlDao.isInitialized) return null

        return when (uriMatcher.match(uri)) {
            ID_URL_DATA -> handleQuery(selectionArgs)
            else -> null
        }
    }

    override fun getType(uri: Uri): String? {
        return when (uriMatcher.match(uri)) {
            ID_URL_DATA -> "vnd.android.cursor.dir/$AUTHORITY.$URL_TABLE_NAME"
            ID_URL_DATA_ITEM -> "vnd.android.cursor.item/$AUTHORITY.$URL_TABLE_NAME"
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (!::urlDao.isInitialized || values == null) return null

        return when (uriMatcher.match(uri)) {
            ID_URL_DATA -> {
                val id = urlDao.insert(contentValuesToUrl(values))
                notifyChange(uri)
                ContentUris.withAppendedId(uri, id)
            }
            else -> null
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        if (!::urlDao.isInitialized) return 0

        return when (uriMatcher.match(uri)) {
            ID_URL_DATA_ITEM -> {
                val id = ContentUris.parseId(uri)
                val count = urlDao.deleteById(id)
                notifyChange(uri)
                count
            }
            else -> 0
        }
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
        if (!::urlDao.isInitialized || values == null) return 0

        return when (uriMatcher.match(uri)) {
            ID_URL_DATA_ITEM -> {
                val url = contentValuesToUrl(values).apply { id = ContentUris.parseId(uri) }
                val count = urlDao.update(url)
                notifyChange(uri)
                count
            }
            else -> 0
        }
    }

    private fun notifyChange(uri: Uri) {
        context?.contentResolver?.notifyChange(uri, null)
    }

    private fun contentValuesToUrl(values: ContentValues): Url {
        return try {
            Url(
                type = values.getAsString(Url.URL_TYPE) ?: "",
                url = values.getAsString(Url.URL_ADDRESS) ?: ""
            )
        } catch (e: Exception) {
            Url(type = "", url = "")
        }
    }

    private fun handleQuery(selectionArgs: Array<String>?): Cursor? {
        return when {
            selectionArgs?.size == 2 -> {
                val type = selectionArgs[0]
                val url = selectionArgs[1]
                if (type == "Domain") {
                    urlDao.findExactMatchCursor(url)
                } else {
                    urlDao.findPartialMatchCursor(url)
                }
            }
            else -> urlDao.findAll()
        }
    }

    companion object {
        const val AUTHORITY = "com.close.hook.ads.provider"
        const val URL_TABLE_NAME = "url_info"
        const val ID_URL_DATA = 1
        const val ID_URL_DATA_ITEM = 2

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, URL_TABLE_NAME, ID_URL_DATA)
            addURI(AUTHORITY, "$URL_TABLE_NAME/#", ID_URL_DATA_ITEM)
        }
    }
}
