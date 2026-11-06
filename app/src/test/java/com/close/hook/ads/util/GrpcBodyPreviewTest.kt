package com.close.hook.ads.util

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.DeflaterOutputStream

class GrpcBodyPreviewTest {
    private fun frame(body: ByteArray, compressed: Int = 0) = byteArrayOf(compressed.toByte(),
        (body.size ushr 24).toByte(), (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()) + body
    private fun parse(bytes: ByteArray, mime: String = "application/grpc+proto") =
        JsonParser.parseString(GrpcBodyPreview.render(bytes, mime)).asJsonObject

    @Test fun identifiesGrpcWithoutMisclassifyingGrpcWeb() {
        assertTrue(GrpcBodyPreview.isGrpc("Application/GRPC+proto; grpc-encoding=gzip"))
        assertTrue(GrpcBodyPreview.isGrpc("application/grpc"))
        assertFalse(GrpcBodyPreview.isGrpc("application/grpc-web+proto"))
        assertFalse(GrpcBodyPreview.isGrpc("application/json"))
    }

    @Test fun multipleAndEmptyMessagesRetainIndependentBoundaries() {
        val result = parse(frame("one".toByteArray()) + frame(byteArrayOf()) + frame("two".toByteArray()))
        assertEquals(3, result["messageCount"].asInt)
        val messages = result["messages"].asJsonArray
        assertEquals("one", messages[0].asJsonObject["utf8Preview"].asString)
        assertEquals(0, messages[1].asJsonObject["decodedLength"].asInt)
        assertEquals("74 77 6f", messages[2].asJsonObject["hexPreview"].asString)
        assertEquals(0, result["unparsedBytes"].asInt)
    }

    @Test fun compressionIsPerMessageRatherThanPerHttpBody() {
        val compressed = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write("压缩的 message".toByteArray()) }
        }.toByteArray()
        val result = parse(frame(compressed, 1) + frame("plain".toByteArray()), "application/grpc; grpc-encoding=gzip")
        val messages = result["messages"].asJsonArray
        assertEquals("压缩的 message", messages[0].asJsonObject["utf8Preview"].asString)
        assertEquals("identity", messages[1].asJsonObject["encoding"].asString)
        assertEquals("plain", messages[1].asJsonObject["utf8Preview"].asString)
    }

    @Test fun deflateCompressionUsesThePerMessageEncoding() {
        val compressed = ByteArrayOutputStream().apply {
            DeflaterOutputStream(this).use { it.write("deflated message".toByteArray()) }
        }.toByteArray()
        val result = parse(frame(compressed, 1), "application/grpc; grpc-encoding=deflate")
        val message = result["messages"].asJsonArray[0].asJsonObject
        assertFalse(message.has("error"))
        assertEquals("deflated message", message["utf8Preview"].asString)
    }

    @Test fun invalidFlagsAndUnsignedLengthsProduceExplicitErrors() {
        assertTrue(parse(byteArrayOf(0, 0))["error"].asString.contains("header"))
        assertTrue(parse(byteArrayOf(2, 0, 0, 0, 0))["error"].asString.contains("flag"))
        val result = parse(byteArrayOf(0, -1, -1, -1, -1))
        assertTrue(result["error"].asString.contains("4294967295"))
        assertEquals(0, result["messageCount"].asInt)
        assertEquals(5, result["unparsedBytes"].asInt)
    }

    @Test fun unknownCompressionDoesNotEraseLaterMessageBoundaries() {
        val result = parse(frame(byteArrayOf(1, 2, 3), 1) + frame("next".toByteArray()),
            "application/grpc; grpc-encoding=unsupported")
        val messages = result["messages"].asJsonArray
        assertTrue(messages[0].asJsonObject["error"].asString.contains("Unsupported"))
        assertEquals("next", messages[1].asJsonObject["utf8Preview"].asString)
    }

    @Test fun gzipBombsAndMessageCountsHaveHardLimits() {
        val compressed = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(ByteArray(2 * 1024 * 1024)) }
        }.toByteArray()
        val result = parse(frame(compressed, 1), "application/grpc; grpc-encoding=gzip")
        assertTrue(result["messages"].asJsonArray[0].asJsonObject["error"].asString.contains("limit"))
        val many = ByteArray(5000 * 5)
        val limited = parse(many)
        assertEquals(4096, limited["messageCount"].asInt)
        assertEquals(64, limited["messages"].asJsonArray.size())
        assertTrue(limited["unparsedBytes"].asInt > 0)
        assertTrue(limited["error"].asString.contains("limit"))
    }

    @Test fun payloadPreviewsAreBoundedAndDoNotPretendToDecodeProtobufFields() {
        val result = parse(frame(ByteArray(1000) { 1 }))
        val message = result["messages"].asJsonArray[0].asJsonObject
        assertEquals(1000, message["decodedLength"].asInt)
        assertEquals(256 * 3 - 1, message["hexPreview"].asString.length)
        assertTrue(message["previewTruncated"].asBoolean)
        assertFalse(message.has("utf8Preview"))
        assertTrue(result["representation"].asString.contains("schema"))
    }
}
