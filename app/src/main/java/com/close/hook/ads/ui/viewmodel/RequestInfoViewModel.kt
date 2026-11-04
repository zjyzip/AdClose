package com.close.hook.ads.ui.viewmodel

import android.app.Application
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.R
import com.close.hook.ads.data.model.ResponseBodyContent
import com.close.hook.ads.util.BodyTextSearch
import com.close.hook.ads.util.readBytesLimited
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.brotli.dec.BrotliInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

class RequestInfoViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        const val TAB_REQUEST_BODY = "RequestBody"
        const val TAB_RESPONSE_BODY = "ResponseBody"
        private const val MAX_CONTENT_BYTES = 10 * 1024 * 1024
        private const val MAX_FORMAT_BYTES = 1024 * 1024
    }

    data class BodyDisplay(
        val tab: String,
        val query: String,
        val text: CharSequence,
        val page: Int,
        val pageCount: Int,
        val matchCount: Int = 0,
        val matchIndex: Int = -1,
        val truncated: Boolean = false,
        val searching: Boolean = false,
        val matchStart: Int = -1,
        val matchEnd: Int = -1
    )

    private var initialized = false
    private var requestBodySource: String? = null
    private var responseBodySource: String? = null
    private var requestLoadJob: Job? = null
    private var responseLoadJob: Job? = null
    private var currentTab: String? = null
    private var searchJob: Job? = null
    private var renderJob: Job? = null
    private var revision = 0L
    private var found = BodyTextSearch.Result(IntArray(0), false)
    private var activeMatch = -1
    private var appliedQuery = ""
    private val pages = mutableMapOf<String, Int>()
    private var highlightColor = Color.TRANSPARENT
    private var activeHighlightColor = Color.TRANSPARENT
    private var highlightTextColor: Int? = null
    private var activeTextColor: Int? = null

    private val _requestBody = MutableStateFlow<String?>(null)
    val requestBody = _requestBody.asStateFlow()
    private val _responseBody = MutableStateFlow<ResponseBodyContent>(ResponseBodyContent.Loading)
    val responseBody = _responseBody.asStateFlow()
    private val _currentQuery = MutableStateFlow("")
    val currentQuery = _currentQuery.asStateFlow()
    private val _bodyDisplay = MutableStateFlow<BodyDisplay?>(null)
    val bodyDisplay = _bodyDisplay.asStateFlow()

    fun init(arguments: Bundle) {
        if (initialized) return
        initialized = true
        updateSources(arguments)
    }

    fun updateSources(arguments: Bundle) {
        arguments.getString("requestBodyUriString")?.takeIf { it != requestBodySource }?.let {
            requestBodySource = it
            requestLoadJob?.cancel()
            loadRequestBody(it)
        }
        arguments.getString("responseBodyUriString")?.takeIf { it != responseBodySource }?.let {
            responseBodySource = it
            responseLoadJob?.cancel()
            loadResponseBody(it)
        }
    }

    fun setCurrentTab(tabType: String) {
        if (currentTab == tabType) return
        currentTab = tabType
        restartSearch(false)
    }

    fun updateQuery(query: String) {
        if (_currentQuery.value == query) return
        _currentQuery.value = query
        restartSearch(true)
    }

    fun setHighlightColors(normal: Int, active: Int, normalText: Int, activeText: Int) {
        highlightColor = normal
        activeHighlightColor = active
        highlightTextColor = normalText
        activeTextColor = activeText
    }

    private fun contentFor(tab: String?): String? = when (tab) {
        TAB_REQUEST_BODY -> _requestBody.value
        TAB_RESPONSE_BODY -> (_responseBody.value as? ResponseBodyContent.Text)?.content
        else -> null
    }

    /** Offscreen pages also receive a bounded window, never the entire large body. */
    fun displayFor(tab: String): BodyDisplay? {
        _bodyDisplay.value?.takeIf { it.tab == tab }?.let { return it }
        val content = contentFor(tab) ?: return null
        val window = BodyTextSearch.window(content, pages[tab] ?: 0)
        return BodyDisplay(tab, "", content.substring(window.start, window.end), window.page, window.pageCount)
    }

    private fun restartSearch(debounce: Boolean) {
        val token = ++revision
        searchJob?.cancel()
        renderJob?.cancel()
        found = BodyTextSearch.Result(IntArray(0), false)
        activeMatch = -1
        appliedQuery = ""
        val tab = currentTab ?: return
        val content = contentFor(tab)
        if (content == null) {
            _bodyDisplay.value = null
            return
        }
        val query = _currentQuery.value
        val window = BodyTextSearch.window(content, pages[tab] ?: 0)
        _bodyDisplay.value = BodyDisplay(
            tab, query, content.substring(window.start, window.end), window.page, window.pageCount,
            searching = query.isNotEmpty()
        )
        searchJob = viewModelScope.launch {
            if (debounce && query.isNotEmpty()) delay(300)
            val result = withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                BodyTextSearch.find(content, query) { context.ensureActive() }
            }
            if (token != revision || currentTab != tab || _currentQuery.value != query) return@launch
            found = result
            appliedQuery = query
            activeMatch = if (result.starts.isEmpty()) -1 else 0
            if (activeMatch >= 0) pages[tab] = BodyTextSearch.pageForOffset(content, result.starts[0])
            renderBody()
        }
    }

    fun navigateToNextMatch() = navigateMatch(1)
    fun navigateToPreviousMatch() = navigateMatch(-1)

    private fun navigateMatch(delta: Int) {
        if (found.starts.isEmpty() || appliedQuery != _currentQuery.value) return
        activeMatch = (activeMatch + delta + found.starts.size) % found.starts.size
        val tab = currentTab ?: return
        val content = contentFor(tab) ?: return
        pages[tab] = BodyTextSearch.pageForOffset(content, found.starts[activeMatch])
        renderBody()
    }

    fun changeBodyPage(delta: Int) {
        val tab = currentTab ?: return
        val content = contentFor(tab) ?: return
        val window = BodyTextSearch.window(content, (pages[tab] ?: 0) + delta)
        pages[tab] = window.page
        renderBody()
    }

    private fun renderBody() {
        renderJob?.cancel()
        val tab = currentTab ?: return
        val content = contentFor(tab) ?: return
        val query = appliedQuery
        val result = found
        val index = activeMatch
        val window = BodyTextSearch.window(content, pages[tab] ?: 0, query.length)
        val token = revision
        renderJob = viewModelScope.launch {
            val display = withContext(Dispatchers.Default) {
                val matches = result.starts.withIndex().filter { (_, start) ->
                    start >= window.start && start + query.length <= window.end
                }
                val text = content.substring(window.start, window.end)
                val highlighted = if (matches.isEmpty()) text else SpannableString(text).apply {
                    matches.take(512).forEach { (priority, start) ->
                        applyHighlight(this, start - window.start, start - window.start + query.length, priority == index)
                    }
                    matches.firstOrNull { it.index == index }?.let { (_, start) ->
                        applyHighlight(this, start - window.start, start - window.start + query.length, true)
                    }
                }
                val start = result.starts.getOrNull(index) ?: -1
                val visible = start >= window.start && start + query.length <= window.end
                BodyDisplay(
                    tab, query, highlighted, window.page, window.pageCount, result.starts.size, index,
                    result.truncated, matchStart = if (visible) start - window.start else -1,
                    matchEnd = if (visible) start - window.start + query.length else -1
                )
            }
            if (token == revision && tab == currentTab && query == _currentQuery.value) _bodyDisplay.value = display
        }
    }

    fun createHighlightedText(text: String, query: String, currentIndex: Int = -1): CharSequence {
        if (query.isEmpty()) return text
        // Static headers/stack use the same bounded search; body rendering is handled above.
        val matches = BodyTextSearch.find(text, query).starts
        if (matches.isEmpty()) return text
        return SpannableString(text).apply {
            matches.forEachIndexed { index, start -> applyHighlight(this, start, start + query.length, index == currentIndex) }
        }
    }

    private fun applyHighlight(text: SpannableString, start: Int, end: Int, active: Boolean) {
        text.setSpan(BackgroundColorSpan(if (active) activeHighlightColor else highlightColor),
            start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val foreground = if (active) activeTextColor else highlightTextColor
        foreground?.let {
            text.setSpan(ForegroundColorSpan(it), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun loadRequestBody(uriString: String) {
        requestLoadJob = viewModelScope.launch {
            try {
                _requestBody.value = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(Uri.parse(uriString))
                        ?.use { decodeText(it.readBytesLimited(MAX_CONTENT_BYTES), null) }
                        ?: throw IOException("Failed to open request body")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _requestBody.value = getApplication<Application>().getString(R.string.body_load_failed, e.message.orEmpty())
            }
            if (currentTab == TAB_REQUEST_BODY) restartSearch(false)
        }
    }

    private fun loadResponseBody(uriString: String) {
        responseLoadJob = viewModelScope.launch {
            try {
                _responseBody.value = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val uri = Uri.parse(uriString)
                    val mime = resolver.getType(uri)
                    val encoding = mime?.split(';')?.firstOrNull { it.trim().startsWith("encoding=", true) }
                        ?.substringAfter('=')?.trim()
                    val stream = resolver.openInputStream(uri) ?: throw IOException("Failed to open response body")
                    val bytes = stream.use { original ->
                        decompressStream(encoding, original).use { it.readBytesLimited(MAX_CONTENT_BYTES) }
                    }
                    if (mime?.startsWith("image/", true) == true && isBitmap(bytes)) {
                        ResponseBodyContent.Image(bytes, mime)
                    } else ResponseBodyContent.Text(decodeText(bytes, mime), mime)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _responseBody.value = ResponseBodyContent.Error(
                    getApplication<Application>().getString(R.string.body_load_failed, e.message.orEmpty())
                )
            }
            if (currentTab == TAB_RESPONSE_BODY) restartSearch(false)
        }
    }

    private fun isBitmap(bytes: ByteArray): Boolean = BitmapFactory.Options().run {
        inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this)
        outWidth > 0 && outHeight > 0
    }

    private fun decompressStream(encoding: String?, stream: InputStream): InputStream = when (encoding?.lowercase(Locale.ROOT)) {
        "gzip" -> GZIPInputStream(stream)
        "deflate" -> InflaterInputStream(stream)
        "br" -> BrotliInputStream(stream)
        else -> stream
    }

    private fun decodeText(bytes: ByteArray, mime: String?): String {
        val charsetName = mime?.split(';')?.firstOrNull { it.trim().startsWith("charset=", true) }
            ?.substringAfter('=')?.trim()?.trim('"')
        val charset = runCatching { charsetName?.let(Charset::forName) ?: StandardCharsets.UTF_8 }
            .getOrDefault(StandardCharsets.UTF_8)
        val text = String(bytes, charset)
        if (bytes.size > MAX_FORMAT_BYTES) return text
        return runCatching { GsonBuilder().setPrettyPrinting().create().toJson(JsonParser.parseString(text)) }
            .getOrDefault(text)
    }
}
