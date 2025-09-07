package com.close.hook.ads.ui.fragment.request

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.AnimatedVectorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.close.hook.ads.R
import com.close.hook.ads.databinding.FragmentRequestInfoBinding
import com.close.hook.ads.preference.HookPrefs
import com.close.hook.ads.ui.fragment.base.BaseFragment
import com.google.android.material.tabs.TabLayout
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
    private val originalTexts = mutableMapOf<Int, CharSequence>()

    private val contentSections by lazy {
        mapOf(
            "DNS Info" to binding.dnsSection,
            "Request" to binding.requestSection,
            "RequestBody" to binding.requestBodySection,
            "Response" to binding.responseSection,
            "ResponseBody" to binding.responseBodySection,
            "Stack" to binding.stackSection
        )
    }

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
            uri?.let { fileUri ->
                contentToExport?.let { content ->
                    saveTextToFile(fileUri, content)
                }
            }
        }

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (binding.editText.isFocused) {
                updateSearchUI(isFocused = false)
            } else {
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, onBackPressedCallback)
        setupToolbarAndSearch()
        setupTabs()
        populateData()
    }

    private fun setupToolbarAndSearch() {
        binding.searchIcon.setOnClickListener {
            updateSearchUI(isFocused = !binding.editText.isFocused)
        }

        binding.editText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                updateSearchUI(isFocused = true)
            }
        }

        binding.clear.setOnClickListener { binding.editText.text.clear() }

        binding.editText.addTextChangedListener {
            val query = it.toString()
            binding.clear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            performSearch(query)
        }

        updateSearchUI(isFocused = false)
    }

    private fun updateSearchUI(isFocused: Boolean) {
        val drawableId = if (isFocused) R.drawable.ic_magnifier_to_back else R.drawable.ic_back_to_magnifier
        binding.searchIcon.apply {
            setImageDrawable(ContextCompat.getDrawable(requireContext(), drawableId))
            (drawable as? AnimatedVectorDrawable)?.start()
        }

        if (isFocused) {
            binding.editText.showKeyboard()
        } else {
            binding.editText.hideKeyboard()
            binding.editText.setText("")
        }
    }
    
    private fun setupTabs() {
        val availableTabs = getAvailableTabs()
        binding.tabs.removeAllTabs()
        availableTabs.forEach { binding.tabs.addTab(binding.tabs.newTab().setText(it)) }

        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tab?.text?.toString()?.let { title ->
                    showSection(title)
                    performSearch(binding.editText.text.toString())
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        if (availableTabs.isNotEmpty()) {
            showSection(availableTabs.first())
        }
    }

    private fun getAvailableTabs(): List<String> = arguments?.run {
        mutableListOf<String>().apply {
            if (!getString("dnsHost").isNullOrEmpty()) add("DNS Info")
            if (!getString("method").isNullOrEmpty() || !getString("urlString").isNullOrEmpty() || !getString("requestHeaders").isNullOrEmpty()) add("Request")
            
            if (!getString("requestBodyUriString").isNullOrEmpty()) add("RequestBody")
            
            if (!getString("responseMessage").isNullOrEmpty() || !getString("responseHeaders").isNullOrEmpty()) {
                add("Response")
                if (!getString("responseBodyUriString").isNullOrEmpty()) add("ResponseBody")
            }
            
            if (!getString("stack").isNullOrEmpty()) add("Stack")
        }
    } ?: emptyList()

    private fun showSection(tabText: String) {
        contentSections.values.forEach { it.visibility = View.GONE }
        contentSections[tabText]?.visibility = View.VISIBLE

        val fabIsVisible = when (tabText) {
            "RequestBody" -> {
                binding.exportFab.setOnClickListener { exportTextContent() }
                !binding.requestBodyText.text.contains("No Request Body Available")
            }
            "ResponseBody" -> {
                binding.exportFab.setOnClickListener { exportResponseContent() }
                !binding.responseBodyText.text.contains("No Response Body Available") || binding.responseBodyImage.visibility == View.VISIBLE
            }
            else -> false
        }
        if (fabIsVisible) binding.exportFab.show() else binding.exportFab.hide()
    }

    private fun populateData() {
        populateDnsInfo()
        populateRequestInfo()
        populateResponseInfo()
        populateStackInfo()
        loadAndDisplayRequestBody()
        loadAndDisplayResponseBody()
    }

    private fun populateDnsInfo() {
        binding.dnsHostText.text = arguments?.getString("dnsHost")
        binding.fullAddressText.text = arguments?.getString("fullAddress")
        saveOriginalTexts(binding.dnsHostText, binding.fullAddressText)
    }

    private fun populateRequestInfo() {
        binding.methodText.text = arguments?.getString("method")
        binding.urlStringText.text = arguments?.getString("urlString")
        binding.requestHeadersText.text = arguments?.getString("requestHeaders")
        saveOriginalTexts(binding.methodText, binding.urlStringText, binding.requestHeadersText)
    }

    private fun populateResponseInfo() {
        binding.responseCodeText.text = arguments?.getString("responseCode")
        binding.responseMessageText.text = arguments?.getString("responseMessage")
        binding.responseHeadersText.text = arguments?.getString("responseHeaders")
        saveOriginalTexts(binding.responseCodeText, binding.responseMessageText, binding.responseHeadersText)
    }

    private fun populateStackInfo() {
        binding.stackText.text = arguments?.getString("stack")
        saveOriginalTexts(binding.stackText)
    }

    private fun loadAndDisplayRequestBody() {
        val uriString = arguments?.getString("requestBodyUriString")
        if (uriString.isNullOrEmpty()) {
            binding.requestBodyText.text = "No Request Body Available"
            return
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bytes = getBytesFromUri(uriString)
                val content = bytes?.let { formatText(it) } ?: "Error: Failed to load request body."
                withContext(Dispatchers.Main) {
                    binding.requestBodyText.text = content
                    contentToExport = content
                    saveOriginalTexts(binding.requestBodyText)
                }
            } catch (e: Exception) {
                Log.e("RequestInfoFragment", "Error loading request body", e)
                withContext(Dispatchers.Main) {
                    binding.requestBodyText.text = "Error: ${e.message}"
                }
            }
        }
    }

    private fun loadAndDisplayResponseBody() {
        setupCollectResponseBodySwitch()
        val uriString = arguments?.getString("responseBodyUriString")
        if (uriString.isNullOrEmpty()) {
            binding.responseBodyText.text = "No Response Body Available"
            return
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (stream, mime) = getResponseBodyStream(uriString) ?: throw IOException("Stream failed")
                val encoding = mime?.split(";")?.find { it.trim().startsWith("encoding=") }?.substringAfter("=")
                decompressStream(encoding, stream)?.use {
                    val bytes = it.readBytes()
                    withContext(Dispatchers.Main) {
                        if (mime?.startsWith("image/") == true) displayImage(bytes, mime)
                        else displayText(bytes, mime)
                    }
                }
            } catch (e: Exception) {
                Log.e("RequestInfoFragment", "Error processing response body", e)
                withContext(Dispatchers.Main) { binding.responseBodyText.text = "Error: ${e.message}" }
            }
        }
    }

    private fun getBytesFromUri(uriString: String): ByteArray? = try {
        requireContext().contentResolver.openInputStream(Uri.parse(uriString))?.use { it.readBytes() }
    } catch (e: Exception) {
        Log.e("RequestInfoFragment", "Failed to get bytes from URI", e)
        null
    }

    private fun getResponseBodyStream(uriString: String): Pair<InputStream?, String?> = try {
        val uri = Uri.parse(uriString)
        val resolver = requireContext().contentResolver
        resolver.openInputStream(uri) to (resolver.getType(uri) ?: "application/octet-stream;")
    } catch (e: Exception) {
        Log.e("RequestInfoFragment", "Failed to get response body stream", e)
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
            Log.e("RequestInfoFragment", "Decompression failed, using original stream", e)
            it
        }
    }

    private fun displayText(bytes: ByteArray, mimeType: String?) {
        val content = formatText(bytes)
        binding.responseBodyText.text = content
        binding.responseBodyText.visibility = View.VISIBLE
        binding.responseBodyImage.visibility = View.GONE
        contentToExport = content
        imageBytesToExport = null
        imageContentType = mimeType
        saveOriginalTexts(binding.responseBodyText)
    }

    private fun displayImage(bytes: ByteArray, mimeType: String) {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        binding.responseBodyImage.setImageBitmap(bitmap)
        binding.responseBodyImage.visibility = if (bitmap != null) View.VISIBLE else View.GONE
        binding.responseBodyText.visibility = if (bitmap != null) View.GONE else View.VISIBLE
        if (bitmap == null) {
            binding.responseBodyText.text = "Error: Could not display image.\n\n$mimeType"
            contentToExport = String(bytes, StandardCharsets.UTF_8)
            imageBytesToExport = null
        } else {
            imageBytesToExport = bytes
            contentToExport = null
        }
        imageContentType = mimeType
    }

    private fun formatText(bytes: ByteArray): String = try {
        GsonBuilder().setPrettyPrinting().create().toJson(JsonParser.parseString(String(bytes, StandardCharsets.UTF_8)))
    } catch (e: Exception) {
        String(bytes, StandardCharsets.UTF_8)
    }

    private fun setupCollectResponseBodySwitch() {
        binding.collectResponseBodySwitch.apply {
            isChecked = HookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false)
            setOnCheckedChangeListener { _, isChecked -> HookPrefs.setBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, isChecked) }
        }
    }

    private fun saveOriginalTexts(vararg textViews: TextView) {
        textViews.forEach { if (it.text?.isNotEmpty() == true) originalTexts[it.id] = it.text }
    }

    private fun highlightText(textView: TextView, query: String) {
        val originalText = originalTexts[textView.id] ?: return
        if (query.isEmpty()) {
            textView.text = originalText
            return
        }
        val spannable = SpannableString(originalText)
        val normalizedText = originalText.toString().lowercase(Locale.ROOT)
        val normalizedQuery = query.lowercase(Locale.ROOT)
        var index = normalizedText.indexOf(normalizedQuery)
        while (index >= 0) {
            spannable.setSpan(
                BackgroundColorSpan(Color.YELLOW), index, index + query.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            index = normalizedText.indexOf(normalizedQuery, index + 1)
        }
        textView.text = spannable
    }

    fun performSearch(query: String) {
        val currentVisibleSection = contentSections.values.firstOrNull { it.visibility == View.VISIBLE } ?: return
        findTextViews(currentVisibleSection).forEach {
            highlightText(it, query)
        }
    }

    private fun findTextViews(view: View): List<TextView> {
        val textViews = mutableListOf<TextView>()
        if (view is TextView) {
            textViews.add(view)
        } else if (view is ViewGroup) {
            view.children.forEach { child ->
                textViews.addAll(findTextViews(child))
            }
        }
        return textViews
    }

    private fun exportTextContent() {
        val content = contentToExport ?: return
        val ext = if (content.trimStart().startsWith("{") || content.trimStart().startsWith("[")) "json" else "txt"
        val tabTitle = binding.tabs.getTabAt(binding.tabs.selectedTabPosition)?.text?.toString()?.replace(" ","")?.lowercase(Locale.ROOT) ?: "content"
        val fileName = "${tabTitle}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.$ext"
        createDocumentLauncher.launch(fileName)
    }

    private fun saveTextToFile(uri: Uri, content: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                requireContext().contentResolver.openOutputStream(uri)?.use {
                    it.write(content.toByteArray(StandardCharsets.UTF_8))
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

    private fun exportResponseContent() {
        if (imageBytesToExport != null) {
            saveImageToGallery(imageBytesToExport!!)
        } else if (contentToExport != null) {
            exportTextContent()
        }
    }

    private fun saveImageToGallery(bytes: ByteArray) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val mime = imageContentType ?: "image/jpeg"
            val fileName = "ResponseBody_${System.currentTimeMillis()}.${mime.substringAfter('/')}"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            try {
                val resolver = requireContext().contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    resolver.openOutputStream(it)?.use { out -> ByteArrayInputStream(bytes).copyTo(out) }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(it, values, null, null)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "图片已保存到相册", Toast.LENGTH_SHORT).show()
                    }
                } ?: throw IOException("URI generation failed")
            } catch (e: Exception) {
                Log.e("RequestInfoFragment", "Image save failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "图片保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun View.showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        this.requestFocus()
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun View.hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
        this.clearFocus()
    }
}
