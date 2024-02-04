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
        context?.let {
            urlDao = UrlDatabase.getDatabase(it).urlDao
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? =
        when (uriMatcher.match(uri)) {
            ID_URL_DATA -> urlDao.findAll().also { cursor ->
                cursor.setNotificationUri(context!!.contentResolver, uri)
            }
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        when (uriMatcher.match(uri)) {
            ID_URL_DATA -> {
                val id = values?.let {
                    urlDao.insert(
                        Url(
                            it.getAsString(Url.URL_TYPE),
                            it.getAsString(Url.URL_ADDRESS)
                        )
                    )
                } ?: throw IllegalArgumentException("Invalid URI: Insert failed $uri")
                
                if (id != 0L) {
                    notifyChange(uri)
                    ContentUris.withAppendedId(uri, id)
                } else null
            }
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
        when (uriMatcher.match(uri)) {
            ID_URL_DATA_ITEM -> {
                val count = urlDao.delete(ContentUris.parseId(uri))
                notifyChange(uri)
                count
            }
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int =
        when (uriMatcher.match(uri)) {
            ID_URL_DATA -> {
                val count = values?.let {
                    urlDao.update(
                        Url(
                            it.getAsString(Url.URL_TYPE),
                            it.getAsString(Url.URL_ADDRESS)
                        )
                    )
                } ?: throw IllegalArgumentException("Invalid URI: Update failed $uri")
                
                if (count > 0) {
                    notifyChange(uri)
                }
                count
            }
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }

    private fun notifyChange(uri: Uri) {
        context?.contentResolver?.notifyChange(uri, null)
    }

    companion object {
        const val AUTHORITY = "com.close.hook.ads.provider"
        const val URL_TABLE_NAME = "url_info"
        const val ID_URL_DATA = 1
        const val ID_URL_DATA_ITEM = 2

        val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, URL_TABLE_NAME, ID_URL_DATA)
            addURI(AUTHORITY, "$URL_TABLE_NAME/*", ID_URL_DATA_ITEM)
        }
    }
}
