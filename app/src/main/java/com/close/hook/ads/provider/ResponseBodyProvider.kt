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
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ResponseBodyProvider : ContentProvider() {

    companion object {
        private const val TAG = "ResponseBodyProvider"
        const val AUTHORITY = "com.close.hook.ads.provider.responsebody"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/response_bodies")

        private const val RESPONSE_BODIES = 1
        private const val RESPONSE_BODY_ID = 2

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "response_bodies", RESPONSE_BODIES)
            addURI(AUTHORITY, "response_bodies/*", RESPONSE_BODY_ID)
        }

        private val responseBodyStore: Cache<String, Pair<ByteArray, String>> = CacheBuilder.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(100)
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
        val id = when (uriMatcher.match(uri)) {
            RESPONSE_BODY_ID -> uri.lastPathSegment
            else -> null
        } ?: return null

        return responseBodyStore.getIfPresent(id)?.second
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") {
            throw IllegalArgumentException("Unsupported mode: $mode")
        }
        
        val id = when (uriMatcher.match(uri)) {
            RESPONSE_BODY_ID -> uri.lastPathSegment
            else -> throw FileNotFoundException("Invalid URI for openFile: $uri")
        } ?: throw FileNotFoundException("No ID found in URI: $uri")

        val data = responseBodyStore.getIfPresent(id)
            ?: throw FileNotFoundException("Data not found or expired for URI: $uri")

        val bodyBytes = data.first

        try {
            val pipe = ParcelFileDescriptor.createPipe()
            val readSide = pipe[0]
            val writeSide = pipe[1]

            thread(isDaemon = true) {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use {
                        it.write(bodyBytes)
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Error writing to pipe", e)
                }
            }
            
            return readSide
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create pipe", e)
            throw FileNotFoundException("Could not open file for URI: $uri")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (uriMatcher.match(uri) != RESPONSE_BODIES) {
            throw IllegalArgumentException("Cannot insert into URI: $uri")
        }
        
        val bodyContent = values?.getAsByteArray("body_content")
        val mimeType = values?.getAsString("mime_type")

        if (bodyContent == null || mimeType == null) {
            Log.e(TAG, "ContentValues must contain 'body_content' and 'mime_type'.")
            return null
        }

        val id = UUID.randomUUID().toString()
        responseBodyStore.put(id, Pair(bodyContent, mimeType))

        return Uri.withAppendedPath(CONTENT_URI, id)
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
