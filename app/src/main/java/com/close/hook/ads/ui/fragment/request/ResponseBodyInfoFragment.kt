package com.close.hook.ads.ui.fragment.request

import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.lifecycleScope
import com.close.hook.ads.databinding.FragmentResponseBodyBinding
import com.close.hook.ads.ui.fragment.base.BaseFragment
import com.close.hook.ads.util.dp
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream

class ResponseBodyInfoFragment : BaseFragment<FragmentResponseBodyBinding>() {

    private var contentToExportAndDisplay: String? = null

    companion object {
        private const val RESPONSE_BODY_KEY = "responseBody"
        private const val IS_BODY_COMPRESSED_KEY = "isBodyCompressed"
        private const val BUFFER_SIZE = 8192

        fun newInstance(responseBody: String?, isBodyCompressed: Boolean): ResponseBodyInfoFragment {
            return ResponseBodyInfoFragment().apply {
                arguments = Bundle().apply {
                    putString(RESPONSE_BODY_KEY, responseBody)
                    putBoolean(IS_BODY_COMPRESSED_KEY, isBodyCompressed)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupFab()
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
            setOnClickListener { exportContent() }
        }
    }

    private fun loadAndDisplayResponseBody() {
        val responseBodyInput = arguments?.getString(RESPONSE_BODY_KEY) ?: ""
        val isBodyCompressed = arguments?.getBoolean(IS_BODY_COMPRESSED_KEY, false) ?: false

        if (responseBodyInput.isEmpty()) {
            binding.responseBodyText.text = "No Response Body Available"
            binding.exportFab.visibility = View.GONE
            return
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val processedContent = processAndFormatResponseBody(responseBodyInput, isBodyCompressed)
            withContext(Dispatchers.Main) {
                binding.responseBodyText.text = processedContent
                contentToExportAndDisplay = processedContent
                binding.exportFab.visibility = View.VISIBLE
            }
        }
    }

    private fun processAndFormatResponseBody(
        responseBodyInput: String,
        isBodyCompressed: Boolean
    ): String {
        val decompressedBody = try {
            if (isBodyCompressed) decompressString(responseBodyInput) else responseBodyInput
        } catch (e: IOException) {
            return "Error decompressing body: ${e.message}\n\nOriginal (compressed) body:\n$responseBodyInput"
        } catch (e: Exception) {
            return "An unexpected error occurred: ${e.message}"
        }

        return try {
            val gson = GsonBuilder().setPrettyPrinting().create()
            val jsonElement = JsonParser.parseString(decompressedBody)
            gson.toJson(jsonElement)
        } catch (e: Exception) {
            decompressedBody
        }
    }

    private fun decompressString(compressedBase64Data: String): String {
        if (compressedBase64Data.isEmpty()) {
            return compressedBase64Data
        }
        val decoded = Base64.decode(compressedBase64Data, Base64.DEFAULT)
        val bos = ByteArrayOutputStream(decoded.size * 2)

        GZIPInputStream(ByteArrayInputStream(decoded)).use { gzip ->
            val buffer = ByteArray(BUFFER_SIZE)
            var len: Int
            while (gzip.read(buffer).also { len = it } != -1) {
                bos.write(buffer, 0, len)
            }
        }
        return bos.toString(StandardCharsets.UTF_8.name())
    }

    private fun exportContent() {
        if (contentToExportAndDisplay.isNullOrEmpty()) {
            return
        }

        val fileExtension = try {
            JsonParser.parseString(contentToExportAndDisplay)
            "json"
        } catch (e: Exception) {
            "txt"
        }
        
        val fileName = "response_body_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.$fileExtension"

        val launcher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            uri?.let { fileUri ->
                contentToExportAndDisplay?.let { content ->
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            requireContext().contentResolver.openOutputStream(fileUri)?.use { outputStream ->
                                outputStream.write(content.toByteArray(StandardCharsets.UTF_8))
                            }
                        } catch (e: IOException) {
                        }
                    }
                }
            }
        }
        launcher.launch(fileName)
    }
}
