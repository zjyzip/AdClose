package com.close.hook.ads.ui.fragment.request

import android.content.ContentValues
import android.content.Context
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
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.close.hook.ads.R
import com.close.hook.ads.databinding.FragmentRequestInfoBinding
import com.close.hook.ads.ui.adapter.RequestInfoPagerAdapter
import com.close.hook.ads.ui.fragment.base.BaseFragment
import com.close.hook.ads.ui.viewmodel.RequestInfoViewModel
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RequestInfoFragment : BaseFragment<FragmentRequestInfoBinding>() {

    private val viewModel: RequestInfoViewModel by viewModels()
    private lateinit var availableTabs: List<String>
    private var contentToSave: String? = null

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
            uri?.let { fileUri ->
                contentToSave?.let { content ->
                    saveTextToFile(fileUri, content)
                }
                contentToSave = null
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

        viewModel.init(requireArguments())
        availableTabs = getAvailableTabs()

        setupToolbarAndSearch()
        setupViewPagerAndTabs()
    }

    private fun setupToolbarAndSearch() {
        binding.searchIcon.setOnClickListener {
            val isFocused = binding.editText.isFocused
            updateSearchUI(isFocused = !isFocused)
            if (isFocused) {
                onBackPressedCallback.handleOnBackPressed()
            }
        }

        binding.editText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) updateSearchUI(isFocused = true)
        }

        binding.clear.setOnClickListener { binding.editText.text.clear() }

        binding.editText.addTextChangedListener {
            val query = it.toString()
            viewModel.currentQuery.value = query
            binding.clear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            performSearchOnCurrentPage(query)
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
            if (binding.editText.text.isNotEmpty()) {
                binding.editText.setText("")
            }
        }
    }

    private fun setupViewPagerAndTabs() {
        val pagerAdapter = RequestInfoPagerAdapter(
            requireArguments(),
            availableTabs,
            viewModel,
            viewLifecycleOwner,
        ) { viewId, text ->
            viewModel.originalTexts[viewId] = text
            val currentViewHolder = getCurrentViewHolder()
            if (currentViewHolder?.itemView?.findViewById<View>(viewId) != null && !viewModel.currentQuery.value.isNullOrEmpty()) {
                performSearchOnCurrentPage(viewModel.currentQuery.value!!)
            }
        }
        binding.viewPager.adapter = pagerAdapter

        TabLayoutMediator(binding.tabs, binding.viewPager) { tab, position ->
            tab.text = availableTabs[position]
        }.attach()

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateFabVisibility(availableTabs[position])
                performSearchOnCurrentPage(viewModel.currentQuery.value.orEmpty())
            }
        })

        if (availableTabs.isNotEmpty()) {
            updateFabVisibility(availableTabs.first())
        }
    }

    private fun highlightText(textView: TextView, originalText: CharSequence, query: String) {
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

    private fun performSearchOnCurrentPage(query: String) {
        getCurrentViewHolder()?.let { holder ->
            findTextViews(holder.itemView).forEach { textView ->
                viewModel.originalTexts[textView.id]?.let { originalText ->
                    highlightText(textView, originalText, query)
                }
            }
        }
    }

    private fun getCurrentViewHolder(): RecyclerView.ViewHolder? {
        return (binding.viewPager.getChildAt(0) as? RecyclerView)
            ?.findViewHolderForAdapterPosition(binding.viewPager.currentItem)
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

    private fun updateFabVisibility(tabText: String) {
        val fabIsVisible = when (tabText) {
            "RequestBody", "ResponseBody" -> true
            else -> false
        }
        if (fabIsVisible) binding.exportFab.show() else binding.exportFab.hide()

        if (tabText == "RequestBody") {
            binding.exportFab.setOnClickListener { exportRequestBody() }
        } else if (tabText == "ResponseBody") {
            binding.exportFab.setOnClickListener { exportResponseContent() }
        }
    }

    private fun findTextViews(view: View): List<TextView> {
        return if (view is TextView) {
            listOf(view)
        } else if (view is ViewGroup) {
            view.children.flatMap(::findTextViews).toList()
        } else {
            emptyList()
        }
    }

    private fun exportRequestBody() {
        val content = viewModel.requestBody.value
        if (content.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "内容为空或仍在加载中", Toast.LENGTH_SHORT).show()
            return
        }

        contentToSave = content
        val ext = if (content.trimStart().startsWith("{") || content.trimStart().startsWith("[")) "json" else "txt"
        val fileName = "requestbody_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.$ext"
        createDocumentLauncher.launch(fileName)
    }

    private fun exportResponseBodyText() {
        val content = viewModel.responseBody.value?.text
        if (content.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "内容为空或仍在加载中", Toast.LENGTH_SHORT).show()
            return
        }

        contentToSave = content
        val ext = if (content.trimStart().startsWith("{") || content.trimStart().startsWith("[")) "json" else "txt"
        val fileName = "responsebody_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.$ext"
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
        val responseData = viewModel.responseBody.value
        when {
            responseData?.imageBytes != null -> saveImageToGallery(responseData.imageBytes, responseData.mimeType)
            responseData?.text != null -> exportResponseBodyText()
            else -> Toast.makeText(requireContext(), "内容为空或仍在加载中", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveImageToGallery(bytes: ByteArray, mimeType: String?) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val mime = mimeType ?: "image/jpeg"
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
