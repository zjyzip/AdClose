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
        urlDao = UrlDatabase.getDatabase(context!!).urlDao
        return true
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? =
        when (uriMatcher.match(uri)) {
            ID_URL_DATA -> context?.let { ctx ->
                urlDao.findAll().also { cursor ->
                    cursor.setNotificationUri(ctx.contentResolver, uri)
                }
            }
            else -> null
        }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        when (uriMatcher.match(uri)) {
            ID_URL_DATA -> values?.let {
                val id = urlDao.insert(contentValuesToUrl(it))
                notifyChange(uri)
                ContentUris.withAppendedId(uri, id)
            }
            else -> null
        }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
        when (uriMatcher.match(uri)) {
            ID_URL_DATA_ITEM -> {
                val id = ContentUris.parseId(uri)
                val count = urlDao.deleteById(id)
                notifyChange(uri)
                count
            }
            else -> 0
        }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int =
        when (uriMatcher.match(uri)) {
            ID_URL_DATA_ITEM -> values?.let {
                val url = contentValuesToUrl(it).apply { id = ContentUris.parseId(uri) }
                val count = urlDao.update(url)
                notifyChange(uri)
                count
            } ?: 0
            else -> 0
        }

    private fun notifyChange(uri: Uri) {
        context?.contentResolver?.notifyChange(uri, null)
    }

    private fun contentValuesToUrl(values: ContentValues): Url =
        Url(
            type = values.getAsString(Url.URL_TYPE),
            url = values.getAsString(Url.URL_ADDRESS)
        )

    companion object {
        const val AUTHORITY = "com.close.hook.ads.provider"
        const val URL_TABLE_NAME = "url_info"
        const val ID_URL_DATA = 1
        const val ID_URL_DATA_ITEM = 2

        val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, URL_TABLE_NAME, ID_URL_DATA)
            addURI(AUTHORITY, "$URL_TABLE_NAME/#", ID_URL_DATA_ITEM)
        }
    }
}
