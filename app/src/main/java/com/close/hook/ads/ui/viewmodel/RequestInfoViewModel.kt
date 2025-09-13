package com.close.hook.ads.ui.viewmodel

import android.app.Application
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.brotli.dec.BrotliInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

data class ResponseBodyResult(
    val text: String? = null,
    val imageBytes: ByteArray? = null,
    val error: String? = null,
    val mimeType: String?
)

data class SearchState(
    val query: String = "",
    val matches: List<Pair<Int, Int>> = emptyList(),
    val currentMatchIndex: Int = -1,
    val textContent: CharSequence? = null
)

class RequestInfoViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private val HIGHLIGHT_COLOR = Color.YELLOW
        private val ACTIVE_HIGHLIGHT_COLOR = Color.parseColor("#FF9800")
    }

    private val _requestBody = MutableStateFlow<String?>(null)
    val requestBody: StateFlow<String?> = _requestBody.asStateFlow()

    private val _responseBody = MutableStateFlow<ResponseBodyResult?>(null)
    val responseBody: StateFlow<ResponseBodyResult?> = _responseBody.asStateFlow()
    
    val currentQuery = MutableStateFlow("")
    
    private val _searchState = MutableStateFlow(SearchState())
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()
    
    private var currentContentProvider: (() -> String?)? = null

    fun init(arguments: Bundle) {
        arguments.getString("requestBodyUriString")?.let { loadRequestBody(it) }
        arguments.getString("responseBodyUriString")?.let { loadResponseBody(it) }

        viewModelScope.launch {
            currentQuery
                .debounce(300)
                .distinctUntilChanged()
                .flatMapLatest { query ->
                    flow {
                        val content = currentContentProvider?.invoke()
                        if (query.isEmpty() || content.isNullOrEmpty()) {
                            emit(SearchState(query = query))
                        } else {
                            val matches = findMatches(content, query)
                            val newIndex = if (matches.isNotEmpty()) 0 else -1
                            emit(SearchState(query, matches, newIndex, highlightText(content, matches, newIndex)))
                        }
                    }.flowOn(Dispatchers.Default)
                }
                .collect { state ->
                    _searchState.value = state
                }
        }
    }

    fun setCurrentContentProvider(provider: (() -> String?)) {
        currentContentProvider = provider
        currentQuery.value = currentQuery.value 
    }
    
    fun resetSearchState() {
        _searchState.value = SearchState(query = currentQuery.value)
    }
    
    fun navigateToNextMatch() {
        _searchState.update { currentState ->
            if (currentState.matches.isEmpty()) return@update currentState
            val newIndex = (currentState.currentMatchIndex + 1) % currentState.matches.size
            currentState.copy(
                currentMatchIndex = newIndex,
                textContent = highlightText(currentState.textContent.toString(), currentState.matches, newIndex)
            )
        }
    }

    fun navigateToPreviousMatch() {
        _searchState.update { currentState ->
            if (currentState.matches.isEmpty()) return@update currentState
            val newIndex = (currentState.currentMatchIndex - 1 + currentState.matches.size) % currentState.matches.size
            currentState.copy(
                currentMatchIndex = newIndex,
                textContent = highlightText(currentState.textContent.toString(), currentState.matches, newIndex)
            )
        }
    }
    
    private fun findMatches(text: String, query: String): List<Pair<Int, Int>> {
        if (query.isEmpty()) return emptyList()
        val positions = mutableListOf<Pair<Int, Int>>()
        val normalizedText = text.lowercase(Locale.ROOT)
        val normalizedQuery = query.lowercase(Locale.ROOT)
        var index = normalizedText.indexOf(normalizedQuery)
        while (index >= 0) {
            positions.add(Pair(index, index + query.length))
            index = normalizedText.indexOf(normalizedQuery, index + 1)
        }
        return positions
    }

    private fun highlightText(text: String, matches: List<Pair<Int, Int>>, currentIndex: Int): SpannableString {
        val spannable = SpannableString(text)
        matches.forEachIndexed { index, (start, end) ->
            val color = if (index == currentIndex) ACTIVE_HIGHLIGHT_COLOR else HIGHLIGHT_COLOR
            spannable.setSpan(BackgroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return spannable
    }

    private fun loadRequestBody(uriString: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = getBytesFromUri(uriString)
                val content = bytes?.let { formatText(it) } ?: "Error: Failed to load request body."
                _requestBody.value = content
            } catch (e: Exception) {
                Log.e("RequestInfoViewModel", "Error loading request body", e)
                _requestBody.value = "Error: ${e.message}"
            }
        }
    }

    private fun loadResponseBody(uriString: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (stream, mime) = getResponseBodyStream(uriString) ?: throw IOException("Failed to open response body stream.")
                stream?.use { inputStream ->
                    val encoding = mime?.split(";")?.find { it.trim().startsWith("encoding=") }?.substringAfter("=")
                    decompressStream(encoding, inputStream)?.use { decompressedStream ->
                        val bytes = decompressedStream.readBytes()
                        if (mime?.startsWith("image/") == true && isBitmap(bytes)) {
                            _responseBody.value = ResponseBodyResult(imageBytes = bytes, mimeType = mime)
                        } else {
                            _responseBody.value = ResponseBodyResult(text = formatText(bytes), mimeType = mime)
                        }
                    } ?: throw IOException("Decompression failed, resulting stream is null.")
                } ?: throw IOException("Stream is null after getting it from URI.")
            } catch (e: Exception) {
                Log.e("RequestInfoViewModel", "Error processing response body", e)
                _responseBody.value = ResponseBodyResult(error = "Error: ${e.message}", mimeType = null)
            }
        }
    }

    private fun isBitmap(bytes: ByteArray): Boolean {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            options.outWidth != -1 && options.outHeight != -1
        } catch (e: Exception) {
            false
        }
    }

    private fun getBytesFromUri(uriString: String): ByteArray? = try {
        getApplication<Application>().contentResolver.openInputStream(Uri.parse(uriString))?.use { it.readBytes() }
    } catch (e: Exception) {
        Log.e("RequestInfoViewModel", "Failed to get bytes from URI", e)
        null
    }

    private fun getResponseBodyStream(uriString: String): Pair<InputStream?, String?> = try {
        val uri = Uri.parse(uriString)
        val resolver = getApplication<Application>().contentResolver
        resolver.openInputStream(uri) to (resolver.getType(uri) ?: "application/octet-stream;")
    } catch (e: Exception) {
        Log.e("RequestInfoViewModel", "Failed to get response body stream", e)
        null to null
    }

    private fun decompressStream(encoding: String?, stream: InputStream): InputStream? = try {
        when (encoding?.lowercase(Locale.ROOT)) {
            "gzip" -> GZIPInputStream(stream)
            "deflate" -> InflaterInputStream(stream)
            "br" -> BrotliInputStream(stream)
            else -> stream
        }
    } catch (e: Exception) {
        Log.e("RequestInfoViewModel", "Decompression failed, using original stream", e)
        stream
    }

    private fun formatText(bytes: ByteArray): String = try {
        GsonBuilder().setPrettyPrinting().create().toJson(JsonParser.parseString(String(bytes, StandardCharsets.UTF_8)))
    } catch (e: Exception) {
        String(bytes, StandardCharsets.UTF_8)
    }
}
