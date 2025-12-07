package com.close.hook.ads.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.close.hook.ads.util.SimpleMemoryCache
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

class TemporaryFileProvider : ContentProvider() {

    companion object {
        private const val TAG = "TemporaryFileProvider"
        const val AUTHORITY = "com.close.hook.ads.provider.temporaryfile"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/temporary_files")

        const val KEY_BODY_CONTENT = "body_content"
        const val KEY_MIME_TYPE = "mime_type"

        private const val TEMPORARY_FILES = 1
        private const val TEMPORARY_FILE_ID = 2

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "temporary_files", TEMPORARY_FILES)
            addURI(AUTHORITY, "temporary_files/*", TEMPORARY_FILE_ID)
        }
    }

    private val contentStore by lazy { SimpleMemoryCache() }

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? {
        return if (uriMatcher.match(uri) == TEMPORARY_FILE_ID) {
            uri.lastPathSegment?.let { id ->
                contentStore.get(id)?.second
            }
        } else {
            null
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw IllegalArgumentException("Only 'r' mode is supported.")

        val id = uri.lastPathSegment ?: throw FileNotFoundException("Invalid URI")
        val (bodyBytes, _) = contentStore.get(id) ?: throw FileNotFoundException("Data expired or not found")

        return try {
            val pipe = ParcelFileDescriptor.createReliablePipe()
            thread(isDaemon = true) {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bodyBytes) }
                } catch (e: IOException) {
                    try {
                        pipe[1].closeWithError("IO error: ${e.message}")
                    } catch (ignored: IOException) {}
                }
            }
            pipe[0]
        } catch (e: IOException) {
            throw FileNotFoundException("Pipe creation failed: ${e.message}")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (uriMatcher.match(uri) != TEMPORARY_FILES) throw IllegalArgumentException("Invalid URI for insert")
        
        val bodyContent = values?.getAsByteArray(KEY_BODY_CONTENT)
        val mimeType = values?.getAsString(KEY_MIME_TYPE)
        requireNotNull(bodyContent)
        requireNotNull(mimeType)

        val id = UUID.randomUUID().toString()
        contentStore.put(id, bodyContent to mimeType)

        val newUri = Uri.withAppendedPath(CONTENT_URI, id)
        Log.d(TAG, "Inserted new temporary file with URI: $newUri")
        return newUri
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
