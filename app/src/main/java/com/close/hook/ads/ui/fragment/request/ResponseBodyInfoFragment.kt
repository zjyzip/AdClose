package com.close.hook.ads.ui.fragment.request

import android.net.Uri
import android.util.Log
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.close.hook.ads.preference.HookPrefs

class ResponseBodyInfoFragment : BaseFragment<FragmentResponseBodyBinding>() {

    private var contentToExportAndDisplay: String? = null
    private lateinit var hookPrefs: HookPrefs

    private val createDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri?.let { fileUri ->
            contentToExportAndDisplay?.let { content ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        requireContext().contentResolver.openOutputStream(fileUri)?.use { outputStream ->
                            outputStream.write(content.toByteArray(StandardCharsets.UTF_8))
                        }
                    } catch (e: IOException) {
                        e.printStackTrace()
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
            setOnClickListener { exportContent() }
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
            val processedContent = getAndFormatResponseBody(responseBodyUriString)
            withContext(Dispatchers.Main) {
                binding.responseBodyText.text = processedContent
                contentToExportAndDisplay = processedContent
                binding.exportFab.visibility = View.VISIBLE
            }
        }
    }

    private fun getAndFormatResponseBody(responseBodyUriString: String): String {
        val uri = Uri.parse(responseBodyUriString)
        var rawBody: String? = null

        try {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val bodyContentIndex = cursor.getColumnIndex("body_content")
                    if (bodyContentIndex != -1) {
                        val encryptedBody = cursor.getString(bodyContentIndex)
                        rawBody = EncryptionUtil.decrypt(encryptedBody)
                    } else {
                        Log.e("ResponseBodyInfoFragment", "Column 'body_content' not found in cursor.")
                    }
                } else {
                    Log.w("ResponseBodyInfoFragment", "Cursor is empty for URI: $responseBodyUriString")
                }
            }
        } catch (e: Exception) {
            Log.e("ResponseBodyInfoFragment", "Error reading or decrypting response body from Content Provider", e)
            return "Error reading or decrypting response body from Content Provider: ${e.message}\n\nURI: $responseBodyUriString"
        }

        if (rawBody.isNullOrEmpty()) {
            return "No content found for URI: $responseBodyUriString"
        }

        return runCatching {
            val gson = GsonBuilder().setPrettyPrinting().create()
            val jsonElement = JsonParser.parseString(rawBody)
            gson.toJson(jsonElement)
        }.getOrElse {
            rawBody
        }
    }

    private fun exportContent() {
        if (contentToExportAndDisplay.isNullOrEmpty()) {
            return
        }

        val fileExtension = runCatching {
            JsonParser.parseString(contentToExportAndDisplay)
            "json"
        }.getOrElse {
            "txt"
        }
        
        val fileName = "response_body_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.$fileExtension"

        createDocumentLauncher.launch(fileName)
    }
}
