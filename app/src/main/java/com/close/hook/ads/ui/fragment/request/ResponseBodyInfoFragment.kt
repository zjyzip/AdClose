package com.close.hook.ads.ui.fragment.request

import android.net.Uri
import android.util.Log
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.content.ContentValues
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.lifecycleScope
import com.close.hook.ads.databinding.FragmentResponseBodyBinding
import com.close.hook.ads.ui.fragment.base.BaseFragment
import com.close.hook.ads.util.dp
import com.close.hook.ads.util.EncryptionUtil
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.close.hook.ads.preference.HookPrefs
import android.graphics.BitmapFactory
import android.graphics.Bitmap

class ResponseBodyInfoFragment : BaseFragment<FragmentResponseBodyBinding>() {

    private var contentToExportAndDisplay: String? = null
    private var imageBytesToExportAndDisplay: ByteArray? = null
    private var contentType: String? = null
    private lateinit var hookPrefs: HookPrefs

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hookPrefs = HookPrefs.getInstance(requireContext())
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
            isChecked = hookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false)
            setOnCheckedChangeListener { _, isChecked ->
                hookPrefs.setBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, isChecked)
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
            val (bodyBytes, mimeType) = getResponseBodyData(responseBodyUriString)
            withContext(Dispatchers.Main) {
                when {
                    bodyBytes == null || mimeType == null -> {
                        binding.responseBodyText.text = "Error: No content or MIME type found."
                        binding.exportFab.visibility = View.GONE
                    }
                    mimeType.startsWith("image/") -> displayImage(bodyBytes, mimeType)
                    else -> displayText(bodyBytes, mimeType)
                }
            }
        }
    }

    private fun displayImage(bodyBytes: ByteArray, mimeType: String) {
        val bitmap: Bitmap? = BitmapFactory.decodeByteArray(bodyBytes, 0, bodyBytes.size)
        if (bitmap != null) {
            binding.responseBodyImage.setImageBitmap(bitmap)
            binding.responseBodyImage.visibility = View.VISIBLE
            binding.responseBodyText.visibility = View.GONE
            imageBytesToExportAndDisplay = bodyBytes
            contentToExportAndDisplay = null
        } else {
            Log.e("ResponseBodyInfoFragment", "Error decoding image from byte array.")
            binding.responseBodyText.text = "Error: Could not display image.\n\n$mimeType"
            binding.responseBodyText.visibility = View.VISIBLE
            binding.responseBodyImage.visibility = View.GONE
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

    private fun getResponseBodyData(responseBodyUriString: String): Pair<ByteArray?, String?> {
        val uri = Uri.parse(responseBodyUriString)
        var bodyBytes: ByteArray? = null
        var mimeType: String? = null
        try {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val bodyContentIndex = cursor.getColumnIndex("body_content")
                    val mimeTypeIndex = cursor.getColumnIndex("mime_type")
                    if (bodyContentIndex != -1 && mimeTypeIndex != -1) {
                        val encryptedBody = cursor.getString(bodyContentIndex)
                        mimeType = cursor.getString(mimeTypeIndex)
                        bodyBytes = EncryptionUtil.decrypt(encryptedBody)
                    } else {
                        Log.e("ResponseBodyInfoFragment", "Column not found")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ResponseBodyInfoFragment", "Error reading or decrypting response body", e)
        }
        return bodyBytes to mimeType
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
