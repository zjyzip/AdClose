package com.close.hook.ads

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import com.close.hook.ads.data.RequestDetailUpdates
import com.close.hook.ads.data.model.RequestInfo
import com.close.hook.ads.hook.network.NativeRequestHook
import com.close.hook.ads.hook.network.RequestHook
import com.close.hook.ads.preference.HookPrefs
import com.close.hook.ads.ui.activity.RequestInfoActivity
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Optional ART/JNI -> H2BodyStore -> broadcast -> Provider -> real detail screen regression. */
@RunWith(AndroidJUnit4::class)
class GrpcCaptureUiTest {
    private fun visibleText(view: View, id: Int): TextView? {
        if (view.id == id && view.isShown) return view as TextView
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            visibleText(view.getChildAt(i), id)?.let { return it }
        }
        return null
    }

    @Test fun realGrpcTrafficReachesBodiesAndDetailPreviewsThroughJni() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixturePath = InstrumentationRegistry.getArguments().getString("grpcFixtureDir")
        assumeTrue("Push replay fixtures and pass -e grpcFixtureDir <directory>", fixturePath != null)
        val root = File(fixturePath!!)
        val expected = JsonParser.parseString(File(root, "expected.json").readText()).asJsonObject
        val cases = expected["cases"].asJsonArray.associateBy { it.asJsonObject["path"].asString }
        val context = instrumentation.targetContext
        val previous = HookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false)
        val captured = ConcurrentHashMap<String, RequestInfo>()
        var activity: Activity? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val batch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(RequestHook.REQUEST_BATCH, RequestInfo::class.java).orEmpty()
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<RequestInfo>(RequestHook.REQUEST_BATCH).orEmpty()
                }
                for (item in batch) {
                    val path = item.urlString?.let { Uri.parse(it).path } ?: continue
                    if (path in cases) captured[path] = RequestDetailUpdates.update(item)
                }
            }
        }
        val connectionId = (1L shl 56) + (System.nanoTime() and 0xffffff)
        ContextCompat.registerReceiver(context, receiver, IntentFilter(RequestHook.REQUEST_ACTION), ContextCompat.RECEIVER_EXPORTED)
        try {
            HookPrefs.setBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, true)
            RequestHook.init(context)
            System.loadLibrary("native_hook")
            // Initialize the real parser JNI callbacks without installing process-wide syscall hooks.
            val init = NativeRequestHook.javaClass.getDeclaredMethod("initNativeHook", Boolean::class.javaPrimitiveType)
            init.isAccessible = true
            init.invoke(NativeRequestHook, false)
            DataInputStream(File(root, "traffic.bin").inputStream()).use { input ->
                val sizes = intArrayOf(1, 2, 7, 31, 4096)
                var step = 0
                while (input.available() > 0) {
                    val write = input.readUnsignedByte() != 0
                    val length = input.readInt()
                    assertTrue(length in 1..1024 * 1024)
                    val bytes = ByteArray(length)
                    input.readFully(bytes)
                    var offset = 0
                    while (offset < length) {
                        val count = minOf(length - offset, sizes[step++ % sizes.size])
                        NativeRequestHook.feedH2Data(connectionId, write, bytes, offset, count, true)
                        offset += count
                    }
                }
            }
            val deadline = SystemClock.uptimeMillis() + 10000
            while (SystemClock.uptimeMillis() < deadline && (captured.size != cases.size || captured.values.any {
                    !it.responseHeaders.orEmpty().contains("grpc-status:")
                })) SystemClock.sleep(50)
            assertEquals(cases.size, captured.size)
            val directories = root.listFiles()!!.filter { File(it, "path.txt").isFile }.associateBy { File(it, "path.txt").readText() }
            for ((path, json) in cases) {
                val item = captured.getValue(path)
                assertEquals("gRPC", item.captureType)
                assertTrue(item.urlString!!.startsWith("http://"))
                assertTrue(item.responseHeaders.orEmpty().contains("grpc-status: ${json.asJsonObject["grpcStatus"].asString}"))
                val directory = directories.getValue(path)
                for ((direction, uri) in listOf("request" to item.requestBodyUriString, "response" to item.responseBodyUriString)) {
                    val bytes = File(directory, "$direction.bin").readBytes()
                    if (bytes.isEmpty()) assertNull(uri)
                    else {
                        assertNotNull(uri)
                        assertArrayEquals(bytes, context.contentResolver.openInputStream(Uri.parse(uri))!!.use { it.readBytes() })
                        assertTrue(context.contentResolver.getType(Uri.parse(uri))!!.startsWith("application/grpc"))
                    }
                }
            }
            for ((method, count) in listOf("Gzip" to 1, "Bidi" to 3)) {
                val item = captured.getValue("/fixture.Service/$method")
                activity = instrumentation.startActivitySync(Intent(context, RequestInfoActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("requestInfo", item))
                instrumentation.waitForIdleSync()
                SystemClock.sleep(700)
                val shown = activity!!
                for ((tab, textId) in listOf(1 to R.id.requestBodyText, 3 to R.id.responseBodyText)) {
                    instrumentation.runOnMainSync { shown.findViewById<ViewPager2>(R.id.view_pager).setCurrentItem(tab, false) }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(700)
                    var failure: Throwable? = null
                    instrumentation.runOnMainSync {
                        try {
                            val text = visibleText(shown.window.decorView, textId)!!.text.toString()
                            val preview = JsonParser.parseString(text).asJsonObject
                            assertEquals("gRPC", preview["protocol"].asString)
                            assertEquals(count, preview["messageCount"].asInt)
                            assertFalse(preview.has("error"))
                            if (method == "Gzip") assertEquals("gzip", preview["messages"].asJsonArray[0].asJsonObject["encoding"].asString)
                        } catch (error: Throwable) { failure = error }
                    }
                    failure?.let { throw AssertionError(it) }
                }
                instrumentation.runOnMainSync { shown.finish() }
                activity = null
            }
        } finally {
            activity?.let { value -> instrumentation.runOnMainSync { value.finish() } }
            context.unregisterReceiver(receiver)
            NativeRequestHook.freeH2Conn(connectionId)
            NativeRequestHook.onH2ConnectionReset(connectionId)
            HookPrefs.setBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, previous)
        }
    }
}
