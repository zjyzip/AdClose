package com.close.hook.ads.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Binder
import android.os.Bundle
import android.util.Log
import com.close.hook.ads.util.BodyUploadStore
import com.close.hook.ads.util.SimpleMemoryCache
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TemporaryFileProvider : ContentProvider() {

    companion object {
        private const val TAG = "TemporaryFileProvider"
        const val AUTHORITY = "com.close.hook.ads.provider.temporaryfile"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/temporary_files")
        const val KEY_BODY_CONTENT = "body_content"
        const val KEY_MIME_TYPE = "mime_type"
        const val METHOD_BEGIN_BODY = "begin_body"
        const val METHOD_APPEND_BODY = "append_body"
        const val METHOD_FINISH_BODY = "finish_body"
        const val METHOD_ABORT_BODY = "abort_body"
        const val KEY_UPLOAD_TOKEN = "upload_token"
        const val KEY_BODY_SIZE = "body_size"
        const val KEY_BODY_URI = "body_uri"
        private const val TEMPORARY_FILES = 1
        private const val TEMPORARY_FILE_ID = 2
        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "temporary_files", TEMPORARY_FILES)
            addURI(AUTHORITY, "temporary_files/*", TEMPORARY_FILE_ID)
        }
    }

    private val uploads = BodyUploadStore()
    private val contentStore by lazy { SimpleMemoryCache() }
    private val ioExecutor: ExecutorService by lazy {
        Executors.newCachedThreadPool { r ->
            Thread(r, "AdClose-ProviderIO").apply { isDaemon = true }
        }
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val owner = Binder.getCallingUid()
        return when (method) {
            METHOD_BEGIN_BODY -> Bundle().apply {
                val size = extras?.getInt(KEY_BODY_SIZE, -1) ?: -1
                val mime = extras?.getString(KEY_MIME_TYPE).orEmpty().ifEmpty { "application/octet-stream" }
                putString(KEY_UPLOAD_TOKEN, uploads.begin(owner, size, mime))
            }
            METHOD_APPEND_BODY -> {
                val token = requireNotNull(arg)
                val bytes = requireNotNull(extras?.getByteArray(KEY_BODY_CONTENT))
                uploads.append(owner, token, bytes)
                Bundle.EMPTY
            }
            METHOD_FINISH_BODY -> {
                val token = requireNotNull(arg)
                val body = uploads.finish(owner, token)
                contentStore.put(token, body.bytes to body.mime)
                Bundle().apply { putString(KEY_BODY_URI, Uri.withAppendedPath(CONTENT_URI, token).toString()) }
            }
            METHOD_ABORT_BODY -> { uploads.abort(owner, requireNotNull(arg)); Bundle.EMPTY }
            else -> super.call(method, arg, extras)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? {
        return if (uriMatcher.match(uri) == TEMPORARY_FILE_ID) {
            uri.lastPathSegment?.let { id -> contentStore.get(id)?.second }
        } else null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw IllegalArgumentException("Only 'r' mode is supported.")
        val id = uri.lastPathSegment ?: throw FileNotFoundException("Invalid URI")
        val (bodyBytes, _) = contentStore.get(id) ?: throw FileNotFoundException("Data expired or not found")
        return try {
            val pipe = ParcelFileDescriptor.createReliablePipe()
            ioExecutor.execute {
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
        if (uriMatcher.match(uri) != TEMPORARY_FILES) {
            Log.w(TAG, "insert: invalid URI $uri")
            return null
        }
        val bodyContent = values?.getAsByteArray(KEY_BODY_CONTENT) ?: run {
            Log.w(TAG, "insert: missing body_content")
            return null
        }
        if (bodyContent.size > 5 * 1024 * 1024) return null
        val mimeType = values.getAsString(KEY_MIME_TYPE)?.takeIf { it.isNotEmpty() }
            ?: "application/octet-stream"
        val id = UUID.randomUUID().toString()
        contentStore.put(id, bodyContent to mimeType)
        val newUri = Uri.withAppendedPath(CONTENT_URI, id)
        Log.d(TAG, "Inserted new temporary file with URI: $newUri")
        return newUri
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0
}
