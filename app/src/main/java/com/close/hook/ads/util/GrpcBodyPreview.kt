package com.close.hook.ads.util

import com.google.gson.GsonBuilder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/** gRPC's 1-byte compression flag + unsigned 4-byte length, independent of protobuf schemas. */
internal object GrpcBodyPreview {
    private const val PREVIEW_BYTES = 256
    private const val MAX_PREVIEWS = 64
    private const val MAX_MESSAGES = 4096
    private const val MAX_DECODED_MESSAGE = 1024 * 1024
    private const val MAX_DECODED_TOTAL = 8 * 1024 * 1024
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun isGrpc(mime: String?): Boolean {
        val type = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return false
        return type == "application/grpc" || type.startsWith("application/grpc+")
    }

    private data class Message(
        val index: Int, val offset: Int, val compressed: Boolean, val encoding: String,
        val wireLength: Long, var decodedLength: Int? = null, var hexPreview: String? = null,
        var utf8Preview: String? = null, var previewTruncated: Boolean = false,
        var error: String? = null
    )
    private data class Result(
        val protocol: String = "gRPC", val representation: String = "Payload preview; protobuf schema not available",
        val messages: MutableList<Message> = mutableListOf(), var messageCount: Int = 0,
        var previewsOmitted: Int = 0, var unparsedBytes: Int = 0, var error: String? = null
    )

    fun render(bytes: ByteArray, mime: String, checkCancelled: () -> Unit = {}): String {
        val encoding = mime.split(';').drop(1).firstOrNull {
            it.substringBefore('=').trim().equals("grpc-encoding", true)
        }?.substringAfter('=')?.trim()?.lowercase(Locale.ROOT) ?: "identity"
        val result = Result()
        var offset = 0
        var decodedBudget = MAX_DECODED_TOTAL
        while (offset < bytes.size && result.messageCount < MAX_MESSAGES) {
            checkCancelled()
            val frameStart = offset
            if (bytes.size - offset < 5) {
                result.error = "Incomplete gRPC message header at offset $offset"
                break
            }
            val flag = bytes[offset].toInt() and 255
            if (flag !in 0..1) {
                result.error = "Invalid gRPC compression flag $flag at offset $offset"
                break
            }
            var length = 0L
            for (i in 1..4) length = (length shl 8) or (bytes[offset + i].toLong() and 255)
            if (length > bytes.size - offset - 5L) {
                result.error = "Incomplete gRPC message at offset $offset: declared $length bytes, captured ${bytes.size - offset - 5}"
                break
            }
            offset += 5
            result.messageCount++
            if (result.messages.size < MAX_PREVIEWS) {
                val message = Message(result.messageCount, frameStart, flag == 1,
                    if (flag == 1) encoding else "identity", length)
                result.messages += message
                if (flag == 0) {
                    message.decodedLength = length.toInt()
                    preview(message, bytes, offset, minOf(length.toInt(), PREVIEW_BYTES))
                } else {
                    try {
                        val input = ByteArrayInputStream(bytes, offset, length.toInt())
                        val stream = when (encoding) {
                            "gzip" -> GZIPInputStream(input)
                            "deflate" -> InflaterInputStream(input)
                            else -> throw IOException("Unsupported or missing grpc-encoding: $encoding")
                        }
                        stream.use {
                            val buffer = ByteArray(8192)
                            val prefix = ByteArray(PREVIEW_BYTES)
                            var prefixSize = 0
                            var decoded = 0
                            while (true) {
                                checkCancelled()
                                val count = it.read(buffer)
                                if (count < 0) break
                                if (count > MAX_DECODED_MESSAGE - decoded || count > decodedBudget) {
                                    throw IOException("gRPC decompression limit exceeded")
                                }
                                decoded += count
                                decodedBudget -= count
                                val kept = minOf(count, PREVIEW_BYTES - prefixSize)
                                buffer.copyInto(prefix, prefixSize, 0, kept)
                                prefixSize += kept
                            }
                            message.decodedLength = decoded
                            preview(message, prefix, 0, prefixSize)
                        }
                    } catch (error: IOException) {
                        message.error = error.message ?: "Invalid compressed gRPC message"
                        // Keep a bounded wire preview if decompression is unavailable or invalid.
                        preview(message, bytes, offset, minOf(length.toInt(), PREVIEW_BYTES))
                    }
                }
            }
            offset += length.toInt()
        }
        result.previewsOmitted = result.messageCount - result.messages.size
        result.unparsedBytes = bytes.size - offset
        if (result.error == null && result.unparsedBytes > 0) result.error = "gRPC message limit exceeded"
        return gson.toJson(result)
    }

    private fun preview(message: Message, bytes: ByteArray, offset: Int, count: Int) {
        val digits = "0123456789abcdef"
        message.hexPreview = buildString(count * 3) {
            repeat(count) { index ->
                if (index != 0) append(' ')
                val value = bytes[offset + index].toInt() and 255
                append(digits[value ushr 4]).append(digits[value and 15])
            }
        }
        message.previewTruncated = (message.decodedLength?.toLong() ?: message.wireLength) > count
        message.utf8Preview = try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, offset, count)).toString()
            text.takeIf { value -> value.none { it < ' ' && it !in "\n\r\t" } }
        } catch (_: IOException) { null }
    }
}
