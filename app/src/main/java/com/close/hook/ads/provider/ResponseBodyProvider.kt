package com.close.hook.ads.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ResponseBodyProvider : ContentProvider() {

    companion object {
        private const val TAG = "ResponseBodyProvider"
        const val AUTHORITY = "com.close.hook.ads.provider.responsebody"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/response_bodies")

        const val KEY_BODY_CONTENT = "body_content"
        const val KEY_MIME_TYPE = "mime_type"

        private const val RESPONSE_BODIES = 1
        private const val RESPONSE_BODY_ID = 2

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "response_bodies", RESPONSE_BODIES)
            addURI(AUTHORITY, "response_bodies/*", RESPONSE_BODY_ID)
        }

        private val responseBodyStore: Cache<String, Pair<ByteArray, String>> = CacheBuilder.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(150)
            .build()
    }

    override fun onCreate(): Boolean {
        return true
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? {
        return null
    }

    override fun getType(uri: Uri): String? {
        return if (uriMatcher.match(uri) == RESPONSE_BODY_ID) {
            uri.lastPathSegment?.let { id ->
                responseBodyStore.getIfPresent(id)?.second
            }
        } else {
            null
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") {
            throw IllegalArgumentException("Unsupported mode: $mode. Only 'r' (read) is supported.")
        }

        val id = uri.lastPathSegment.takeIf { uriMatcher.match(uri) == RESPONSE_BODY_ID }
            ?: throw FileNotFoundException("Invalid or malformed URI for openFile: $uri")

        val (bodyBytes, _) = responseBodyStore.getIfPresent(id)
            ?: throw FileNotFoundException("Data not found or expired for URI: $uri")

        return try {
            val pipe = ParcelFileDescriptor.createReliablePipe()
            val readSide = pipe[0]
            val writeSide = pipe[1]

            thread(isDaemon = true, name = "ResponseBodyProvider-PipeWriter") {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { outputStream ->
                        outputStream.write(bodyBytes)
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Error writing to pipe for URI: $uri", e)
                    try {
                        writeSide.closeWithError("IO error during write: ${e.message}")
                    } catch (closeError: IOException) {
                        Log.e(TAG, "Error closing pipe with error message", closeError)
                    }
                }
            }
            
            readSide
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create pipe for URI: $uri", e)
            throw FileNotFoundException("Could not open file for URI: $uri due to pipe creation failure.")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (uriMatcher.match(uri) != RESPONSE_BODIES) {
            throw IllegalArgumentException("Cannot insert into URI: $uri. Use CONTENT_URI.")
        }
        
        val bodyContent = values?.getAsByteArray(KEY_BODY_CONTENT)
        val mimeType = values?.getAsString(KEY_MIME_TYPE)
        requireNotNull(bodyContent) { "'$KEY_BODY_CONTENT' must not be null." }
        requireNotNull(mimeType) { "'$KEY_MIME_TYPE' must not be null." }

        val id = UUID.randomUUID().toString()
        responseBodyStore.put(id, bodyContent to mimeType)

        val newUri = Uri.withAppendedPath(CONTENT_URI, id)
        Log.d(TAG, "Inserted new response body with URI: $newUri")
        return newUri
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        return 0
    }

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?
    ): Int {
        return 0
    }
}
