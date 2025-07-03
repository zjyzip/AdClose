package com.close.hook.ads.provider

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.close.hook.ads.data.dao.UrlDao
import com.close.hook.ads.data.database.UrlDatabase
import com.close.hook.ads.data.model.Url
import kotlinx.coroutines.runBlocking

class UrlContentProvider : ContentProvider() {
    private lateinit var urlDao: UrlDao

    override fun onCreate(): Boolean = context?.let {
        urlDao = UrlDatabase.getDatabase(it).urlDao
        true
    } ?: false

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = when (uriMatcher.match(uri)) {
        ID_URL_DATA -> runBlocking { handleQuery(selectionArgs) }
        ID_URL_DATA_ITEM -> null
        else -> null
    }

    private suspend fun handleQuery(selectionArgs: Array<String>?): Cursor {
        val columns = arrayOf("id", "type", "url")
        if (selectionArgs == null || selectionArgs.size < 2) return urlDao.findAllList()
        val queryValue = selectionArgs[1]
        urlDao.findMatchByUrlPrefix(queryValue)?.let { return toMatrixCursor(columns, it) }
        urlDao.findMatchByHost(queryValue)?.let { return toMatrixCursor(columns, it) }
        urlDao.findMatchByKeyword(queryValue)?.let { return toMatrixCursor(columns, it) }
        return MatrixCursor(columns)
    }

    private fun toMatrixCursor(columns: Array<String>, url: Url): MatrixCursor {
        val cursor = MatrixCursor(columns)
        cursor.addRow(arrayOf(url.id, url.type, url.url))
        return cursor
    }

    override fun getType(uri: Uri): String? = when (uriMatcher.match(uri)) {
        ID_URL_DATA -> "vnd.android.cursor.dir/$AUTHORITY.$URL_TABLE_NAME"
        ID_URL_DATA_ITEM -> "vnd.android.cursor.item/$AUTHORITY.$URL_TABLE_NAME"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        if (uriMatcher.match(uri) == ID_URL_DATA && values != null) {
            urlDao.insert(values.toUrl()).takeIf { it > 0 }?.let { id ->
                notifyChange(uri)
                ContentUris.withAppendedId(uri, id)
            }
        } else null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
        if (uriMatcher.match(uri) == ID_URL_DATA_ITEM) {
            urlDao.deleteById(ContentUris.parseId(uri)).also { count ->
                if (count > 0) notifyChange(uri)
            }
        } else 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int =
        if (uriMatcher.match(uri) == ID_URL_DATA_ITEM && values != null) {
            val url = values.toUrl().apply { id = ContentUris.parseId(uri) }
            urlDao.update(url).also { count ->
                if (count > 0) notifyChange(uri)
            }
        } else 0

    private fun notifyChange(uri: Uri) {
        context?.contentResolver?.notifyChange(uri, null)
    }

    private fun ContentValues.toUrl(): Url =
        Url(type = getAsString(Url.URL_TYPE).orEmpty(), url = getAsString(Url.URL_ADDRESS).orEmpty())

    companion object {
        const val AUTHORITY = "com.close.hook.ads.provider.url"
        const val URL_TABLE_NAME = "url_info"
        private const val ID_URL_DATA = 1
        private const val ID_URL_DATA_ITEM = 2
        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, URL_TABLE_NAME, ID_URL_DATA)
            addURI(AUTHORITY, "$URL_TABLE_NAME/#", ID_URL_DATA_ITEM)
        }
    }
}
