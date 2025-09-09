package com.close.hook.ads.ui.viewmodel

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
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
    val mimeType: String?
)

class RequestInfoViewModel(application: Application) : AndroidViewModel(application) {

    private val _requestBody = MutableLiveData<String>()
    val requestBody: LiveData<String> = _requestBody

    private val _responseBody = MutableLiveData<ResponseBodyResult>()
    val responseBody: LiveData<ResponseBodyResult> = _responseBody

    val currentQuery = MutableLiveData("")

    fun init(arguments: Bundle) {
        arguments.getString("requestBodyUriString")?.let { loadRequestBody(it) }
        arguments.getString("responseBodyUriString")?.let { loadResponseBody(it) }
    }

    private fun loadRequestBody(uriString: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = getBytesFromUri(uriString)
                val content = bytes?.let { formatText(it) } ?: "Error: Failed to load request body."
                _requestBody.postValue(content)
            } catch (e: Exception) {
                Log.e("RequestInfoViewModel", "Error loading request body", e)
                _requestBody.postValue("Error: ${e.message}")
            }
        }
    }

    private fun loadResponseBody(uriString: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (stream, mime) = getResponseBodyStream(uriString) ?: throw IOException("Stream failed")
                val encoding = mime?.split(";")?.find { it.trim().startsWith("encoding=") }?.substringAfter("=")
                decompressStream(encoding, stream)?.use {
                    val bytes = it.readBytes()
                    if (mime?.startsWith("image/") == true && isBitmap(bytes)) {
                        _responseBody.postValue(ResponseBodyResult(imageBytes = bytes, mimeType = mime))
                    } else {
                        _responseBody.postValue(ResponseBodyResult(text = formatText(bytes), mimeType = mime))
                    }
                }
            } catch (e: Exception) {
                Log.e("RequestInfoViewModel", "Error processing response body", e)
                _responseBody.postValue(ResponseBodyResult(text = "Error: ${e.message}", mimeType = null))
            }
        }
    }

    private fun isBitmap(bytes: ByteArray): Boolean {
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null
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

    private fun decompressStream(encoding: String?, stream: InputStream?): InputStream? = stream?.let {
        try {
            when (encoding?.lowercase(Locale.ROOT)) {
                "gzip" -> GZIPInputStream(it)
                "deflate" -> InflaterInputStream(it)
                "br" -> BrotliInputStream(it)
                else -> it
            }
        } catch (e: Exception) {
            Log.e("RequestInfoViewModel", "Decompression failed, using original stream", e)
            it
        }
    }

    private fun formatText(bytes: ByteArray): String = try {
        GsonBuilder().setPrettyPrinting().create().toJson(JsonParser.parseString(String(bytes, StandardCharsets.UTF_8)))
    } catch (e: Exception) {
        String(bytes, StandardCharsets.UTF_8)
    }
}
