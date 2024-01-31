package com.close.hook.ads.provider

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import com.close.hook.ads.CloseApplication
import com.close.hook.ads.data.dao.UrlDao
import com.close.hook.ads.data.database.UrlDatabase
import com.close.hook.ads.data.model.Url

class UrlContentProvider : ContentProvider() {

    private var urlDao: UrlDao? = null

    override fun onCreate(): Boolean {
        urlDao = context?.let { UrlDatabase.getDatabase(it).urlDao }
        return false
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor {
        val cursor: Cursor
        when (uriMatcher.match(uri)) {
            ID_URL_DATA -> {
                urlDao?.let {
                    cursor = it.findAll()
                    if (context != null) {
                        cursor.setNotificationUri(CloseApplication.context.contentResolver, uri)
                        return cursor
                    }
                }
                throw IllegalArgumentException("Unknown URI: $uri")
            }

            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }

    override fun getType(uri: Uri): String? {
        return null
    }

    override fun insert(
        uri: Uri,
        values: ContentValues?
    ): Uri {
        when (uriMatcher.match(uri)) {
            ID_URL_DATA -> {
                if (context != null && values != null) {
                    urlDao?.let {
                        val id = it.insert(
                            Url(
                                values.getAsLong(Url.URL_ID),
                                values.getAsString(Url.URL_ADDRESS),
                            )
                        )
                        if (id != 0L) {
                            context!!.contentResolver
                                .notifyChange(uri, null)
                            return ContentUris.withAppendedId(uri, id)
                        }
                    }
                }
                throw IllegalArgumentException("Invalid URI: Insert failed$uri")
            }

            ID_URL_DATA_ITEM -> throw IllegalArgumentException(
                "Invalid URI: Insert failed$uri"
            )

            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        when (uriMatcher.match(uri)) {
            ID_URL_DATA -> throw IllegalArgumentException("Invalid uri: cannot delete")
            ID_URL_DATA_ITEM -> {
                if (context != null) {
                    urlDao?.let {
                        val count = it.delete(ContentUris.parseId(uri))
                        context!!.contentResolver
                            .notifyChange(uri, null)
                        return count
                    }
                }
                throw IllegalArgumentException("Unknown URI:$uri")
            }

            else -> throw IllegalArgumentException("Unknown URI:$uri")
        }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        when (uriMatcher.match(uri)) {
            ID_URL_DATA -> {
                if (context != null && values != null) {
                    urlDao?.let {
                        val count = it.update(
                            Url(
                                values.getAsLong(Url.URL_ID),
                                values.getAsString(Url.URL_ADDRESS),
                            )
                        )
                        if (count != 0) {
                            context!!.contentResolver
                                .notifyChange(uri, null)
                            return count
                        }
                    }
                }
                throw IllegalArgumentException("Invalid URI:  cannot update")
            }

            ID_URL_DATA_ITEM -> throw IllegalArgumentException("Invalid URI:  cannot update")
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }

    companion object {

        const val AUTHORITY = "com.close.hook.ads.provider"
        const val URL_TABLE_NAME = "url_info"

        const val ID_URL_DATA = 1
        const val ID_URL_DATA_ITEM = 2
        val uriMatcher = UriMatcher(UriMatcher.NO_MATCH)

        init {
            uriMatcher.addURI(
                AUTHORITY,
                URL_TABLE_NAME,
                ID_URL_DATA
            )
            uriMatcher.addURI(
                AUTHORITY,
                URL_TABLE_NAME +
                        "/*", ID_URL_DATA_ITEM
            )
        }
    }
}
