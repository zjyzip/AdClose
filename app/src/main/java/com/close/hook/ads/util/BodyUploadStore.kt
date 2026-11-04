package com.close.hook.ads.util

import java.io.ByteArrayOutputStream
import java.util.UUID

/** Bounded, caller-owned uploads. Individual chunks stay comfortably below Binder's transaction limit. */
internal class BodyUploadStore(
    private val maxBodyBytes: Int = 5 * 1024 * 1024,
    private val maxPendingBytes: Int = 16 * 1024 * 1024,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    companion object { const val CHUNK_BYTES = 128 * 1024 }
    data class Completed(val bytes: ByteArray, val mime: String)
    private data class Upload(val owner: Int, val size: Int, val mime: String, val created: Long,
        val output: ByteArrayOutputStream)
    private val uploads = linkedMapOf<String, Upload>()
    private var pendingBytes = 0

    @Synchronized fun begin(owner: Int, size: Int, mime: String): String {
        cleanUp()
        require(size in 0..maxBodyBytes) { "Body exceeds capture limit" }
        require(mime.length <= 1024 && mime.none { it == '\r' || it == '\n' }) { "Invalid MIME type" }
        check(uploads.size < 32 && size <= maxPendingBytes - pendingBytes) { "Body upload capacity exceeded" }
        val token = UUID.randomUUID().toString()
        uploads[token] = Upload(owner, size, mime, now(), ByteArrayOutputStream(minOf(size, CHUNK_BYTES)))
        pendingBytes += size
        return token
    }

    @Synchronized fun append(owner: Int, token: String, bytes: ByteArray) {
        val upload = owned(owner, token)
        require(bytes.size <= CHUNK_BYTES && bytes.size <= upload.size - upload.output.size()) { "Invalid upload chunk" }
        upload.output.write(bytes)
    }

    @Synchronized fun finish(owner: Int, token: String): Completed {
        val upload = owned(owner, token)
        uploads.remove(token)
        pendingBytes -= upload.size
        check(upload.output.size() == upload.size) { "Incomplete upload" }
        return Completed(upload.output.toByteArray(), upload.mime)
    }

    @Synchronized fun abort(owner: Int, token: String) {
        val upload = owned(owner, token)
        uploads.remove(token)
        pendingBytes -= upload.size
    }

    private fun owned(owner: Int, token: String): Upload {
        cleanUp()
        val upload = uploads[token] ?: throw IllegalArgumentException("Upload expired or missing")
        if (owner != upload.owner) throw SecurityException("Upload belongs to another caller")
        return upload
    }

    private fun cleanUp() {
        val deadline = now() - 30_000
        val iterator = uploads.values.iterator()
        while (iterator.hasNext()) {
            val upload = iterator.next()
            if (upload.created <= deadline) { pendingBytes -= upload.size; iterator.remove() }
        }
    }
}
