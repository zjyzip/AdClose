package com.close.hook.ads.hook.network

import com.close.hook.ads.util.GrpcBodyPreview
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/** Optional real grpcio fixtures, captured and replayed by app/src/test/integration/grpc_capture.py. */
class GrpcWireIntegrationTest {
    @Test fun previewsMatchRealGrpcioMessagesFromProductionNativeCapture() {
        val rootPath = System.getProperty("adclose.grpcFixtures")
        assumeTrue("Pass -PgrpcIntegrationFixtures=<replay directory>", rootPath != null)
        val root = File(rootPath!!)
        val expected = JsonParser.parseString(File(root, "expected.json").readText()).asJsonObject
        val cases = expected["cases"].asJsonArray.associateBy { it.asJsonObject["path"].asString }
        val directories = root.listFiles()!!.filter { File(it, "path.txt").isFile }
        assertEquals(cases.size, directories.size)
        var compressedMessages = 0
        for (directory in directories) {
            val path = File(directory, "path.txt").readText()
            val case = cases.getValue(path).asJsonObject
            assertEquals("http", File(directory, "scheme.txt").readText())
            for (direction in listOf("request", "response")) {
                val headers = NetworkHeaders.alternating(File(directory, "$direction.headers").readText())
                val mime = NetworkHeaders.bodyContentType(headers)
                assertTrue(GrpcBodyPreview.isGrpc(mime))
                val bytes = File(directory, "$direction.bin").readBytes()
                val result = JsonParser.parseString(GrpcBodyPreview.render(bytes, mime)).asJsonObject
                assertFalse("$path $direction: $result", result.has("error"))
                val messages = result["messages"].asJsonArray
                val wanted = case[direction + "s"].asJsonArray
                assertEquals("$path $direction", wanted.size(), result["messageCount"].asInt)
                assertEquals(0, result["unparsedBytes"].asInt)
                for (index in 0 until wanted.size()) {
                    val payload = Base64.getDecoder().decode(wanted[index].asString)
                    val message = messages[index].asJsonObject
                    assertFalse(message.toString(), message.has("error"))
                    assertEquals(payload.size, message["decodedLength"].asInt)
                    assertEquals(payload.take(256).joinToString(" ") { "%02x".format(it.toInt() and 255) }, message["hexPreview"].asString)
                    if (message["compressed"].asBoolean) compressedMessages++
                }
                if (direction == "response") assertEquals(case["grpcStatus"].asString,
                    headers.first { it.first == "grpc-status" }.second)
            }
        }
        assertTrue("Real request and response gzip messages must both be exercised", compressedMessages >= 2)
    }
}
