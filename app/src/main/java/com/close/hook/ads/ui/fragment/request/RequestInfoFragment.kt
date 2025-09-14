package com.close.hook.ads.ui.fragment.request

import android.content.ContentValues
import android.content.Context
import android.graphics.drawable.AnimatedVectorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.Layout
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.close.hook.ads.R
import com.close.hook.ads.databinding.FragmentRequestInfoBinding
import com.close.hook.ads.ui.adapter.RequestInfoPagerAdapter
import com.close.hook.ads.ui.fragment.base.BaseFragment
import com.close.hook.ads.ui.viewmodel.RequestInfoViewModel
import com.close.hook.ads.ui.viewmodel.ResponseBodyContent
import com.close.hook.ads.util.OnBackPressContainer
import com.close.hook.ads.util.OnBackPressListener
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RequestInfoFragment : BaseFragment<FragmentRequestInfoBinding>(), OnBackPressListener {

    private val viewModel: RequestInfoViewModel by viewModels()
    private lateinit var availableTabs: List<String>
    private var contentToSave: String? = null
    private lateinit var pagerAdapter: RequestInfoPagerAdapter

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
            uri?.let { fileUri ->
                contentToSave?.let { content ->
                    saveTextToFile(fileUri, content)
                }
                contentToSave = null
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.init(requireArguments())
        availableTabs = getAvailableTabs()

        setupToolbarAndSearch()
        setupViewPagerAndTabs()
        setupSearchNavigation()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        (activity as? OnBackPressContainer)?.backController = this
    }

    override fun onPause() {
        super.onPause()
        (activity as? OnBackPressContainer)?.backController = null
    }

    override fun onBackPressed(): Boolean {
        if (binding.editText.isFocused) {
            updateSearchUI(isFocused = false)
            return true
        }
        return false
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.currentQuery.collect {
                        (0 until pagerAdapter.itemCount).forEach { position ->
                            val tab = availableTabs[position]
                            if (tab !in listOf("RequestBody", "ResponseBody")) {
                                pagerAdapter.notifyItemChanged(position, RequestInfoPagerAdapter.Payload.REFRESH_HIGHLIGHT)
                            }
                        }
                    }
                }

                launch {
                    viewModel.matches.collectLatest { updateSearchNavigationVisibility() }
                }
                
                launch {
                    viewModel.currentMatchIndex.collectLatest { index ->
                        updateSearchNavigationVisibility()
                        if (index != -1) scrollToMatch(index)
                    }
                }
                
                launch {
                    viewModel.highlightedContent.collectLatest { content ->
                        val position = binding.viewPager.currentItem
                        val tab = availableTabs.getOrNull(position)
                        if (content != null && tab in listOf("RequestBody", "ResponseBody")) {
                            pagerAdapter.notifyItemChanged(position, content)
                        }
                    }
                }
                
                launch {
                    viewModel.requestBody.collectLatest { content ->
                        val position = availableTabs.indexOf("RequestBody")
                        if (position != -1) pagerAdapter.notifyItemChanged(position, content)
                    }
                }
                
                launch {
                    viewModel.responseBody.collectLatest { result ->
                        val position = availableTabs.indexOf("ResponseBody")
                        if (position != -1) pagerAdapter.notifyItemChanged(position, result)
                    }
                }
            }
        }
    }

    private fun setupSearchNavigation() {
        binding.buttonNextMatch.setOnClickListener { viewModel.navigateToNextMatch() }
        binding.buttonPrevMatch.setOnClickListener { viewModel.navigateToPreviousMatch() }
    }

    private fun setupToolbarAndSearch() {
        binding.searchIcon.setImageResource(R.drawable.ic_magnifier_to_back)
        binding.searchIcon.isActivated = false
        binding.clear.setOnClickListener { binding.editText.setText("") }
        binding.editText.addTextChangedListener {
            viewModel.currentQuery.value = it.toString()
            binding.clear.isVisible = !it.isNullOrEmpty()
        }
        binding.searchIcon.setOnClickListener {
            updateSearchUI(isFocused = !binding.editText.isFocused)
        }
        binding.editText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus != binding.searchIcon.isActivated) {
                updateSearchUI(isFocused = hasFocus)
            }
        }
    }

    private fun updateSearchUI(isFocused: Boolean) {
        binding.searchIcon.isActivated = isFocused
        val drawableId = if (isFocused) R.drawable.ic_magnifier_to_back else R.drawable.ic_back_to_magnifier
        binding.searchIcon.setImageDrawable(ContextCompat.getDrawable(requireContext(), drawableId))
        (binding.searchIcon.drawable as? AnimatedVectorDrawable)?.start()

        if (isFocused) {
            binding.editText.showKeyboard()
        } else {
            binding.editText.setText("")
            binding.editText.hideKeyboard()
        }
    }

    private fun setupViewPagerAndTabs() {
        pagerAdapter = RequestInfoPagerAdapter(requireArguments(), availableTabs, viewModel)
        binding.viewPager.adapter = pagerAdapter

        TabLayoutMediator(binding.tabs, binding.viewPager) { tab, position ->
            tab.text = availableTabs[position]
        }.attach()

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                val newTab = availableTabs[position]
                updateFabVisibility(newTab)

                val contentProvider = when (newTab) {
                    "RequestBody" -> ({ viewModel.requestBody.value })
                    "ResponseBody" -> ({ (viewModel.responseBody.value as? ResponseBodyContent.Text)?.content })
                    else -> ({ null })
                }
                viewModel.setCurrentContentProvider(contentProvider)
                if (binding.editText.isFocused) {
                    viewModel.resetSearchState()
                }
            }
        })

        if (availableTabs.isNotEmpty()) {
            val firstTab = availableTabs.first()
            updateFabVisibility(firstTab)
            viewModel.setCurrentContentProvider {
                when (firstTab) {
                    "RequestBody" -> viewModel.requestBody.value
                    "ResponseBody" -> (viewModel.responseBody.value as? ResponseBodyContent.Text)?.content
                    else -> null
                }
            }
        }
    }

    private fun updateSearchNavigationVisibility() {
        val matchesCount = viewModel.matches.value.size
        val currentIndex = viewModel.currentMatchIndex.value
        val currentTab = availableTabs.getOrNull(binding.viewPager.currentItem)
        val hasFocus = binding.editText.isFocused
        binding.searchNavigationContainer.isVisible = hasFocus &&
                (currentTab == "RequestBody" || currentTab == "ResponseBody") &&
                matchesCount > 0
        binding.matchesCount.text = if (matchesCount > 0 && hasFocus) {
            "${currentIndex + 1}/$matchesCount"
        } else ""
    }

    private fun scrollToMatch(index: Int) {
        val vh = (binding.viewPager.getChildAt(0) as? RecyclerView)?.findViewHolderForAdapterPosition(
            binding.viewPager.currentItem
        ) as? RequestInfoPagerAdapter.RequestInfoViewHolder ?: return

        val scrollView = vh.binding.scrollView
        val textView = when (availableTabs.getOrNull(binding.viewPager.currentItem)) {
            "RequestBody" -> vh.binding.requestBodyText
            "ResponseBody" -> vh.binding.responseBodyText
            else -> null
        } ?: return

        val matches = viewModel.matches.value
        if (index !in matches.indices) return

        textView.post {
            val layout: Layout? = textView.layout
            if (layout != null) {
                val line = layout.getLineForOffset(matches[index].first)
                val y = layout.getLineTop(line)
                val scrollY = textView.top + y - (scrollView.height / 3)
                scrollView.smoothScrollTo(0, scrollY.coerceAtLeast(0))
            }
        }
    }

    private fun getAvailableTabs(): List<String> = arguments?.run {
        mutableListOf<String>().apply {
            if (!getString("dnsHost").isNullOrEmpty()) add("DNS Info")
            if (!getString("method").isNullOrEmpty() || !getString("urlString").isNullOrEmpty() || !getString("requestHeaders").isNullOrEmpty()) add(
                "Request"
            )
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

        binding.exportFab.setOnClickListener {
            if (tabText == "RequestBody") {
                exportRequestBody()
            } else if (tabText == "ResponseBody") {
                exportResponseContent()
            }
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

    private fun exportResponseBodyText(content: String) {
        if (content.isEmpty()) {
            Toast.makeText(requireContext(), "内容为空或仍在加载中", Toast.LENGTH_SHORT).show()
            return
        }
        contentToSave = content
        val ext = if (content.trimStart().startsWith("{") || content.trimStart().startsWith("[")) "json" else "txt"
        val fileName = "responsebody_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.$ext"
        createDocumentLauncher.launch(fileName)
    }

    private fun saveTextToFile(uri: Uri, content: String) {
        lifecycleScope.launch(Dispatchers.IO) {
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
        when (val responseData = viewModel.responseBody.value) {
            is ResponseBodyContent.Image -> saveImageToGallery(responseData.bytes, responseData.mimeType)
            is ResponseBodyContent.Text -> exportResponseBodyText(responseData.content)
            else -> Toast.makeText(requireContext(), "内容为空或仍在加载中", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveImageToGallery(bytes: ByteArray, mimeType: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
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
