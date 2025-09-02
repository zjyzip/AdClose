package com.close.hook.ads.ui.fragment.request

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.lifecycleScope
import com.close.hook.ads.databinding.FragmentRequestBodyBinding
import com.close.hook.ads.ui.fragment.base.BaseFragment
import com.close.hook.ads.util.dp
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RequestBodyInfoFragment : BaseFragment<FragmentRequestBodyBinding>() {

    private var contentToExportAndDisplay: String? = null

    private val createDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
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
                        Log.e("RequestBodyInfoFragment", "File export failed", e)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "文件导出失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val REQUEST_BODY_URI_KEY = "requestBodyUri"

        fun newInstance(requestBodyUri: String?): RequestBodyInfoFragment {
            return RequestBodyInfoFragment().apply {
                arguments = Bundle().apply {
                    putString(REQUEST_BODY_URI_KEY, requestBodyUri)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupFab()
        loadAndDisplayRequestBody()
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
                exportContent()
            }
        }
    }

    private fun loadAndDisplayRequestBody() {
        val requestBodyUriString = arguments?.getString(REQUEST_BODY_URI_KEY)

        if (requestBodyUriString.isNullOrEmpty()) {
            binding.requestBodyText.text = "No Request Body Available"
            binding.exportFab.visibility = View.GONE
            return
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val bodyBytes = getRequestBodyBytes(requestBodyUriString)
            withContext(Dispatchers.Main) {
                if (bodyBytes != null) {
                    displayText(bodyBytes)
                } else {
                    binding.requestBodyText.text = "Error: Failed to load request body."
                    binding.exportFab.visibility = View.GONE
                }
            }
        }
    }

    private fun getRequestBodyBytes(requestBodyUriString: String): ByteArray? {
        return try {
            val uri = Uri.parse(requestBodyUriString)
            requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.e("RequestBodyInfoFragment", "Error reading request body from provider for URI: $requestBodyUriString", e)
            null
        }
    }

    private fun displayText(bodyBytes: ByteArray) {
        val content = runCatching {
            val rawBody = String(bodyBytes, StandardCharsets.UTF_8)
            JsonParser.parseString(rawBody).let {
                GsonBuilder().setPrettyPrinting().create().toJson(it)
            }
        }.getOrElse {
            String(bodyBytes, StandardCharsets.UTF_8)
        }

        binding.requestBodyText.text = content
        contentToExportAndDisplay = content
        binding.exportFab.visibility = View.VISIBLE
    }

    private fun exportContent() {
        contentToExportAndDisplay?.let { content ->
            val fileExtension = if (content.trimStart().startsWith("{") || content.trimStart().startsWith("[")) {
                "json"
            } else {
                "txt"
            }
            val fileName = "request_body_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.$fileExtension"
            createDocumentLauncher.launch(fileName)
        }
    }
}