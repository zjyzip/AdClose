package com.close.hook.ads.ui.fragment.request

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.content.ContentValues
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.lifecycleScope
import com.close.hook.ads.databinding.FragmentResponseBodyInfoBinding
import com.close.hook.ads.preference.HookPrefs
import com.close.hook.ads.ui.fragment.base.BaseFragment
import com.close.hook.ads.util.dp
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.floatingactionbutton.FloatingActionButton
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

class ResponseBodyInfoFragment : BaseFragment<FragmentResponseBodyInfoBinding>() {

    private var contentToExportAndDisplay: String? = null
    private var imageBytesToExportAndDisplay: ByteArray? = null
    private var contentType: String? = null

    private val createDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri?.let { fileUri ->
            contentToExportAndDisplay?.let { content ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        requireContext().contentResolver.openOutputStream(fileUri)?.use { outputStream ->
                            outputStream.write(content.toByteArray(StandardCharsets.UTF_8))
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "文件导出成功", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: IOException) {
                        e.printStackTrace()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "文件导出失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val RESPONSE_BODY_URI_KEY = "responseBodyUri"

        fun newInstance(responseBodyUri: String?): ResponseBodyInfoFragment {
            return ResponseBodyInfoFragment().apply {
                arguments = Bundle().apply {
                    putString(RESPONSE_BODY_URI_KEY, responseBodyUri)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupFab()
        setupCollectResponseBodySwitch()
        loadAndDisplayResponseBody()
    }

    private fun setupFab() {
        binding.exportFab.apply {
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = 16.dp
                bottomMargin = 16.dp
                behavior = HideBottomViewOnScrollBehavior<FloatingActionButton>()
            }
            visibility = View.GONE
            setOnClickListener {
                imageBytesToExportAndDisplay?.let { saveImageToGallery(it) }
                contentToExportAndDisplay?.let { exportContent() }
            }
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

    private fun loadAndDisplayResponseBody() {
        val responseBodyUriString = arguments?.getString(RESPONSE_BODY_URI_KEY)

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

                val decompressedBytes = decompressStream(encoding, rawInputStream)?.use {
                    it.readBytes()
                }

                withContext(Dispatchers.Main) {
                    if (decompressedBytes == null) {
                        throw IOException("Failed to decompress response body.")
                    }
                    when {
                        mimeType.startsWith("image/") -> displayImage(decompressedBytes, mimeType)
                        else -> displayText(decompressedBytes, mimeType)
                    }
                }
            } catch (e: Exception) {
                Log.e("ResponseBodyInfoFragment", "Error processing response body", e)
                withContext(Dispatchers.Main) {
                    binding.responseBodyText.text = "Error: ${e.message}"
                    binding.exportFab.visibility = View.GONE
                }
            }
        }
    }

    private fun displayImage(bodyBytes: ByteArray, mimeType: String) {
        val bitmap = BitmapFactory.decodeByteArray(bodyBytes, 0, bodyBytes.size)
        
        if (bitmap != null) {
            binding.responseBodyImage.setImageBitmap(bitmap)
            binding.responseBodyImage.visibility = View.VISIBLE
            binding.responseBodyText.visibility = View.GONE
            imageBytesToExportAndDisplay = bodyBytes
            contentToExportAndDisplay = null
        } else {
            Log.e("ResponseBodyInfoFragment", "Error decoding image from byte array.")
            binding.responseBodyText.text = "Error: Could not display image.\n\n$mimeType"
            contentToExportAndDisplay = String(bodyBytes, StandardCharsets.UTF_8)
            imageBytesToExportAndDisplay = null
        }
        this.contentType = mimeType
        binding.exportFab.visibility = View.VISIBLE
    }

    private fun displayText(bodyBytes: ByteArray, mimeType: String) {
        val content = runCatching {
            val rawBody = String(bodyBytes, StandardCharsets.UTF_8)
            JsonParser.parseString(rawBody).let {
                GsonBuilder().setPrettyPrinting().create().toJson(it)
            }
        }.getOrElse {
            String(bodyBytes, StandardCharsets.UTF_8)
        }

        binding.responseBodyText.text = content
        binding.responseBodyText.visibility = View.VISIBLE
        binding.responseBodyImage.visibility = View.GONE
        contentToExportAndDisplay = content
        imageBytesToExportAndDisplay = null
        this.contentType = mimeType
        binding.exportFab.visibility = View.VISIBLE
    }

    private fun getResponseBodyStream(responseBodyUriString: String): Pair<InputStream?, String?> {
        val uri = Uri.parse(responseBodyUriString)
        val resolver = requireContext().contentResolver
        return try {
            val combinedMimeType = resolver.getType(uri)
                ?: throw IOException("MIME type is null.")
            val inputStream = resolver.openInputStream(uri)
                ?: throw IOException("Could not open input stream.")
            inputStream to combinedMimeType
        } catch (e: Exception) {
            Log.e("ResponseBodyInfoFragment", "Error getting response body stream for URI: $uri", e)
            null to null
        }
    }
    
    private fun decompressStream(encoding: String?, compressedStream: InputStream?): InputStream? {
        if (compressedStream == null) return null
        return try {
            when (encoding?.lowercase()) {
                "gzip" -> GZIPInputStream(compressedStream)
                "deflate" -> InflaterInputStream(compressedStream)
                "br" -> BrotliInputStream(compressedStream)
                else -> compressedStream
            }
        } catch (e: Exception) {
            Log.e("ResponseBodyInfoFragment", "Decompression stream creation failed for encoding '$encoding'", e)
            compressedStream
        }
    }

    private fun saveImageToGallery(imageBytes: ByteArray) {
        val mimeType = contentType ?: "image/jpeg"
        val fileName = "AdClose_${System.currentTimeMillis()}.${mimeType.substringAfter("/")}"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        }
        runCatching {
            val resolver = requireContext().contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                resolver.openOutputStream(it)?.use { outputStream ->
                    ByteArrayInputStream(imageBytes).copyTo(outputStream)
                    Toast.makeText(requireContext(), "图片已保存到相册", Toast.LENGTH_SHORT).show()
                } ?: run {
                    Toast.makeText(requireContext(), "图片保存失败", Toast.LENGTH_SHORT).show()
                }
            }
        }.onFailure { e ->
            Log.e("ResponseBodyInfoFragment", "Error saving image to gallery", e)
            Toast.makeText(requireContext(), "图片保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportContent() {
        contentToExportAndDisplay?.let { content ->
            val fileExtension = runCatching {
                JsonParser.parseString(content)
                "json"
            }.getOrElse {
                "txt"
            }
            val fileName = "response_body_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.$fileExtension"
            createDocumentLauncher.launch(fileName)
        }
    }
}
