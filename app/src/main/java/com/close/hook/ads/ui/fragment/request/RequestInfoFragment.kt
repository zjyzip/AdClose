package com.close.hook.ads.ui.fragment.request

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.close.hook.ads.databinding.FragmentRequestInfoBinding
import com.close.hook.ads.preference.HookPrefs
import com.close.hook.ads.ui.fragment.base.BaseFragment
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

class RequestInfoFragment : BaseFragment<FragmentRequestInfoBinding>() {

    private var contentToExport: String? = null
    private var imageBytesToExport: ByteArray? = null
    private var imageContentType: String? = null

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
            uri?.let { fileUri ->
                contentToExport?.let { content ->
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            requireContext().contentResolver.openOutputStream(fileUri)?.use { outputStream ->
                                outputStream.write(content.toByteArray(StandardCharsets.UTF_8))
                            }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(requireContext(), "文件导出成功", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: IOException) {
                            Log.e("RequestInfoFragment", "File export failed", e)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(requireContext(), "文件导出失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }

    companion object {
        fun newInstance(
            pageTitle: String, method: String, urlString: String, requestHeaders: String,
            requestBodyUriString: String?, responseCode: String, responseMessage: String,
            responseHeaders: String, responseBodyUriString: String?, stack: String,
            dnsHost: String?, fullAddress: String?
        ): RequestInfoFragment {
            return RequestInfoFragment().apply {
                arguments = Bundle().apply {
                    putString("pageTitle", pageTitle)
                    putString("method", method)
                    putString("urlString", urlString)
                    putString("requestHeaders", requestHeaders)
                    putString("requestBodyUriString", requestBodyUriString)
                    putString("responseCode", responseCode)
                    putString("responseMessage", responseMessage)
                    putString("responseHeaders", responseHeaders)
                    putString("responseBodyUriString", responseBodyUriString)
                    putString("stack", stack)
                    putString("dnsHost", dnsHost)
                    putString("fullAddress", fullAddress)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val pageTitle = arguments?.getString("pageTitle")
        when (pageTitle) {
            "DNS Info" -> setupDnsInfo()
            "Request" -> setupRequestInfo()
            "RequestBody" -> setupRequestBodyInfo()
            "Response" -> setupResponseInfo()
            "ResponseBody" -> setupResponseBodyInfo()
            "Stack" -> setupStackInfo()
        }
    }

    private fun setupDnsInfo() {
        binding.dnsSection.visibility = View.VISIBLE
        binding.dnsHostText.text = arguments?.getString("dnsHost")
        binding.fullAddressText.text = arguments?.getString("fullAddress")
    }

    private fun setupRequestInfo() {
        binding.requestSection.visibility = View.VISIBLE
        binding.methodText.text = arguments?.getString("method")
        binding.urlStringText.text = arguments?.getString("urlString")
        binding.requestHeadersText.text = arguments?.getString("requestHeaders")
    }

    private fun setupRequestBodyInfo() {
        binding.requestBodySection.visibility = View.VISIBLE
        binding.exportFab.setOnClickListener { exportTextContent() }
        loadAndDisplayRequestBody()
    }

    private fun setupResponseInfo() {
        binding.responseSection.visibility = View.VISIBLE
        binding.responseCodeText.text = arguments?.getString("responseCode")
        binding.responseMessageText.text = arguments?.getString("responseMessage")
        binding.responseHeadersText.text = arguments?.getString("responseHeaders")
    }

    private fun setupResponseBodyInfo() {
        binding.responseBodySection.visibility = View.VISIBLE
        binding.exportFab.setOnClickListener { exportResponseContent() }
        setupCollectResponseBodySwitch()
        loadAndDisplayResponseBody()
    }

    private fun setupStackInfo() {
        binding.stackSection.visibility = View.VISIBLE
        binding.stackText.text = arguments?.getString("stack")
    }

    private fun loadAndDisplayRequestBody() {
        val requestBodyUriString = arguments?.getString("requestBodyUriString")
        if (requestBodyUriString.isNullOrEmpty()) {
            binding.requestBodyText.text = "No Request Body Available"
            binding.exportFab.visibility = View.GONE
            return
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val bodyBytes = getRequestBodyBytes(requestBodyUriString)
            withContext(Dispatchers.Main) {
                if (bodyBytes != null) {
                    val content = formatText(bodyBytes)
                    binding.requestBodyText.text = content
                    contentToExport = content
                    binding.exportFab.visibility = View.VISIBLE
                } else {
                    binding.requestBodyText.text = "Error: Failed to load request body."
                    binding.exportFab.visibility = View.GONE
                }
            }
        }
    }

    private fun getRequestBodyBytes(uriString: String): ByteArray? {
        return try {
            requireContext().contentResolver.openInputStream(Uri.parse(uriString))?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.e("RequestInfoFragment", "Error reading request body from URI: $uriString", e)
            null
        }
    }

    private fun loadAndDisplayResponseBody() {
        val responseBodyUriString = arguments?.getString("responseBodyUriString")
        if (responseBodyUriString.isNullOrEmpty()) {
            binding.responseBodyText.text = "No Response Body Available"
            binding.exportFab.visibility = View.GONE
            return
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (rawInputStream, combinedMimeType) = getResponseBodyStream(responseBodyUriString)
                    ?: throw IOException("Could not retrieve response body stream.")
                val parts = combinedMimeType!!.split(";").map { it.trim() }
                val mimeType = parts.firstOrNull() ?: "application/octet-stream"
                val encoding = parts.find { it.startsWith("encoding=") }?.substringAfter("encoding=")
                val decompressedBytes = decompressStream(encoding, rawInputStream)?.readBytes()

                withContext(Dispatchers.Main) {
                    if (decompressedBytes == null) throw IOException("Failed to decompress response body.")
                    when {
                        mimeType.startsWith("image/") -> displayImage(decompressedBytes, mimeType)
                        else -> displayText(decompressedBytes, mimeType)
                    }
                }
            } catch (e: Exception) {
                Log.e("RequestInfoFragment", "Error processing response body", e)
                withContext(Dispatchers.Main) {
                    binding.responseBodyText.text = "Error: ${e.message}"
                    binding.exportFab.visibility = View.GONE
                }
            }
        }
    }

    private fun getResponseBodyStream(uriString: String): Pair<InputStream?, String?> {
        val uri = Uri.parse(uriString)
        return try {
            val resolver = requireContext().contentResolver
            val combinedMimeType = resolver.getType(uri) ?: "application/octet-stream;"
            val inputStream = resolver.openInputStream(uri)
            inputStream to combinedMimeType
        } catch (e: Exception) {
            Log.e("RequestInfoFragment", "Error getting response body stream for URI: $uri", e)
            null to null
        }
    }
    
    private fun decompressStream(encoding: String?, compressedStream: InputStream?): InputStream? {
        if (compressedStream == null) return null
        return try {
            when (encoding?.lowercase(Locale.ROOT)) {
                "gzip" -> GZIPInputStream(compressedStream)
                "deflate" -> InflaterInputStream(compressedStream)
                "br" -> BrotliInputStream(compressedStream)
                else -> compressedStream
            }
        } catch (e: Exception) {
            Log.e("RequestInfoFragment", "Decompression failed for encoding '$encoding'", e)
            compressedStream
        }
    }

    private fun displayText(bodyBytes: ByteArray, mimeType: String) {
        val content = formatText(bodyBytes)
        binding.responseBodyText.text = content
        binding.responseBodyText.visibility = View.VISIBLE
        binding.responseBodyImage.visibility = View.GONE
        contentToExport = content
        imageBytesToExport = null
        imageContentType = mimeType
        binding.exportFab.visibility = View.VISIBLE
    }

    private fun displayImage(bodyBytes: ByteArray, mimeType: String) {
        val bitmap = BitmapFactory.decodeByteArray(bodyBytes, 0, bodyBytes.size)
        if (bitmap != null) {
            binding.responseBodyImage.setImageBitmap(bitmap)
            binding.responseBodyImage.visibility = View.VISIBLE
            binding.responseBodyText.visibility = View.GONE
            imageBytesToExport = bodyBytes
            contentToExport = null
        } else {
            binding.responseBodyText.text = "Error: Could not display image.\n\n$mimeType"
            contentToExport = String(bodyBytes, StandardCharsets.UTF_8)
            imageBytesToExport = null
        }
        imageContentType = mimeType
        binding.exportFab.visibility = View.VISIBLE
    }
    
    private fun formatText(bytes: ByteArray): String {
        return try {
            val rawText = String(bytes, StandardCharsets.UTF_8)
            val jsonElement = JsonParser.parseString(rawText)
            GsonBuilder().setPrettyPrinting().create().toJson(jsonElement)
        } catch (e: Exception) {
            String(bytes, StandardCharsets.UTF_8)
        }
    }
    
    private fun setupCollectResponseBodySwitch() {
        binding.collectResponseBodySwitch.apply {
            isChecked = HookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false)
            setOnCheckedChangeListener { _, isChecked ->
                HookPrefs.setBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, isChecked)
            }
        }
    }

    private fun exportTextContent() {
        val content = contentToExport ?: return
        val fileExtension = if (content.trimStart().startsWith("{") || content.trimStart().startsWith("[")) "json" else "txt"
        val tabTitle = arguments?.getString("pageTitle")?.lowercase(Locale.ROOT) ?: "content"
        val fileName = "${tabTitle}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.$fileExtension"
        createDocumentLauncher.launch(fileName)
    }

    private fun exportResponseContent() {
        imageBytesToExport?.let { saveImageToGallery(it) }
        contentToExport?.let { exportTextContent() }
    }
    
    private fun saveImageToGallery(imageBytes: ByteArray) {
        val mimeType = imageContentType ?: "image/jpeg"
        val extension = mimeType.substringAfter('/')
        val fileName = "ResponseBody_${System.currentTimeMillis()}.$extension"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val resolver = requireContext().contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    resolver.openOutputStream(it)?.use { outputStream ->
                        ByteArrayInputStream(imageBytes).copyTo(outputStream)
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "图片已保存到相册", Toast.LENGTH_SHORT).show()
                    }
                } ?: throw IOException("Failed to create new MediaStore record.")
            } catch (e: Exception) {
                Log.e("RequestInfoFragment", "Error saving image to gallery", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "图片保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
