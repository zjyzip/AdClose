package com.close.hook.ads.ui.fragment.request

import android.content.ContentValues
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.AnimatedVectorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.Layout
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.close.hook.ads.R
import com.close.hook.ads.data.model.ResponseBodyContent
import com.close.hook.ads.data.model.RequestInfo
import com.close.hook.ads.data.model.detailArguments
import com.close.hook.ads.databinding.FragmentRequestInfoBinding
import com.close.hook.ads.ui.adapter.RequestInfoPagerAdapter
import com.close.hook.ads.ui.fragment.base.BaseFragment
import com.close.hook.ads.ui.viewmodel.RequestInfoViewModel
import com.close.hook.ads.util.MotionUtils
import com.close.hook.ads.util.showFeedback
import com.close.hook.ads.util.OnBackPressContainer
import com.close.hook.ads.util.OnBackPressListener
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RequestInfoFragment : BaseFragment<FragmentRequestInfoBinding>(), OnBackPressListener {

    private val viewModel: RequestInfoViewModel by viewModels()
    private lateinit var availableTabs: List<String>
    private var contentToSave: String? = null
    private lateinit var pagerAdapter: RequestInfoPagerAdapter
    private var boundScrollView: NestedScrollView? = null
    private val scrollListener = NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
        val delta = scrollY - oldScrollY
        when {
            delta > 8 -> binding.exportFab.hide()
            delta < -8 && isExportTab(availableTabs.getOrNull(binding.viewPager.currentItem)) ->
                binding.exportFab.show()
        }
    }

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

        viewModel.setHighlightColors(
            MaterialColors.getColor(binding.root, MaterialR.attr.colorSecondaryContainer),
            MaterialColors.getColor(binding.root, MaterialR.attr.colorTertiaryContainer),
            MaterialColors.getColor(binding.root, MaterialR.attr.colorOnSecondaryContainer),
            MaterialColors.getColor(binding.root, MaterialR.attr.colorOnTertiaryContainer)
        )
        viewModel.init(requireArguments())
        availableTabs = getAvailableTabs()

        setupToolbarAndSearch()
        setupViewPagerAndTabs()
        setupSearchNavigation()
        observeViewModel()
    }

    fun updateRequest(item: RequestInfo) {
        val args = arguments ?: return
        args.putAll(item.detailArguments())
        if (_binding == null || !::pagerAdapter.isInitialized) return
        val selected = availableTabs.getOrNull(binding.viewPager.currentItem)
        viewModel.updateSources(args)
        availableTabs = getAvailableTabs()
        pagerAdapter.updateTabs(availableTabs)
        val position = availableTabs.indexOf(selected).takeIf { it >= 0 } ?: 0
        if (availableTabs.isNotEmpty()) binding.viewPager.setCurrentItem(position, false)
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
        if (binding.editText.isFocused || !binding.editText.text.isNullOrEmpty()) {
            updateSearchUI(isFocused = false)
            return true
        }
        return false
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.currentQuery.collect {
                        (0 until pagerAdapter.itemCount).forEach { position ->
                            val tab = availableTabs[position]
                            if (tab !in listOf(
                                    RequestInfoViewModel.TAB_REQUEST_BODY,
                                    RequestInfoViewModel.TAB_RESPONSE_BODY
                                )
                            ) {
                                pagerAdapter.notifyItemChanged(
                                    position,
                                    RequestInfoPagerAdapter.Payload.REFRESH_HIGHLIGHT
                                )
                            }
                        }
                    }
                }

                launch {
                    viewModel.bodyDisplay.collectLatest { display ->
                        updateSearchNavigationVisibility(display)
                        if (display != null) {
                            val position = availableTabs.indexOf(display.tab)
                            if (position >= 0) pagerAdapter.notifyItemChanged(position, display)
                            if (display.matchStart >= 0 && display.matchIndex >= 0 && !display.searching) {
                                binding.viewPager.post {
                                    if (_binding != null && viewModel.bodyDisplay.value === display) scrollToMatch(display)
                                }
                            }
                        }
                    }
                }

                launch {
                    viewModel.requestBody.collectLatest { content ->
                        val position =
                            availableTabs.indexOf(RequestInfoViewModel.TAB_REQUEST_BODY)
                        if (position != -1) pagerAdapter.notifyItemChanged(position, content)
                    }
                }

                launch {
                    viewModel.responseBody.collectLatest { result ->
                        val position =
                            availableTabs.indexOf(RequestInfoViewModel.TAB_RESPONSE_BODY)
                        if (position != -1) pagerAdapter.notifyItemChanged(position, result)
                    }
                }
            }
        }
    }

    private fun setupSearchNavigation() {
        binding.buttonNextMatch.setOnClickListener { viewModel.navigateToNextMatch() }
        binding.buttonPrevMatch.setOnClickListener { viewModel.navigateToPreviousMatch() }
        binding.buttonPrevPage.setOnClickListener { viewModel.changeBodyPage(-1) }
        binding.buttonNextPage.setOnClickListener { viewModel.changeBodyPage(1) }
    }

    private fun setupToolbarAndSearch() {
        binding.searchIcon.setImageResource(R.drawable.ic_magnifier_to_back)
        binding.searchIcon.isActivated = false
        binding.clear.setOnClickListener { binding.editText.setText("") }
        binding.editText.addTextChangedListener {
            viewModel.updateQuery(it.toString())
            binding.clear.isVisible = !it.isNullOrEmpty()
        }
        binding.searchIcon.setOnClickListener {
            val wasActive = binding.editText.isFocused
            updateSearchUI(isFocused = !wasActive)
        }
        binding.editText.setOnFocusChangeListener { _, hasFocus ->
            // Selecting body text must not clear the query or rebind the selected TextView.
            updateSearchIcon(hasFocus)
        }
    }

    private fun updateSearchIcon(isFocused: Boolean) {
        binding.searchIcon.isActivated = isFocused
        val drawableId =
            if (isFocused) R.drawable.ic_magnifier_to_back else R.drawable.ic_back_to_magnifier
        binding.searchIcon.setImageDrawable(
            ContextCompat.getDrawable(requireContext(), drawableId)
        )
        (binding.searchIcon.drawable as? AnimatedVectorDrawable)?.start()
        binding.searchIcon.contentDescription = getString(if (isFocused) R.string.search_close else R.string.search)
    }

    private fun updateSearchUI(isFocused: Boolean) {
        updateSearchIcon(isFocused)
        if (isFocused) {
            binding.editText.showKeyboard()
        } else {
            binding.editText.setText("")
            binding.editText.hideKeyboard()
            viewModel.updateQuery("")
        }
    }

    private fun setupViewPagerAndTabs() {
        pagerAdapter = RequestInfoPagerAdapter(requireArguments(), availableTabs, viewModel, viewLifecycleOwner)
        binding.viewPager.adapter = pagerAdapter

        TabLayoutMediator(binding.tabs, binding.viewPager) { tab, position ->
            tab.text = getString(when (availableTabs[position]) {
                "DNS Info" -> R.string.detail_dns
                "Request" -> R.string.detail_request
                "Response" -> R.string.detail_response
                "Stack" -> R.string.log_stack_trace
                RequestInfoViewModel.TAB_REQUEST_BODY -> R.string.body_request_title
                else -> R.string.body_response_title
            })
        }.attach()

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                val newTab = availableTabs.getOrNull(position) ?: return
                updateFabVisibility(newTab)
                binding.viewPager.post { bindFabToCurrentPage() }

                viewModel.setCurrentTab(newTab)
                updateSearchNavigationVisibility(viewModel.displayFor(newTab))
            }
        })

        if (availableTabs.isNotEmpty()) {
            val firstTab = availableTabs.first()
            updateFabVisibility(firstTab)
            viewModel.setCurrentTab(firstTab)
            binding.viewPager.post { bindFabToCurrentPage() }
        }
    }

    private fun updateSearchNavigationVisibility(display: RequestInfoViewModel.BodyDisplay?) {
        val bodyTab = isExportTab(availableTabs.getOrNull(binding.viewPager.currentItem))
        val hasQuery = bodyTab && !binding.editText.text.isNullOrEmpty()
        binding.searchProgressBar.isVisible = bodyTab && display?.searching == true
        binding.searchNavigationContainer.isVisible = hasQuery
        val count = display?.matchCount ?: 0
        binding.buttonNextMatch.isEnabled = count > 0 && display?.searching == false
        binding.buttonPrevMatch.isEnabled = binding.buttonNextMatch.isEnabled
        binding.matchesCount.text = when {
            display?.searching == true -> getString(R.string.body_searching)
            count == 0 -> getString(R.string.body_no_matches)
            else -> getString(R.string.body_match_count, display!!.matchIndex + 1,
                count.toString() + if (display.truncated) "+" else "")
        }
        binding.bodyPageNavigation.isVisible = bodyTab && display != null && display.pageCount > 1
        if (display != null) {
            binding.bodyPageCount.text = getString(R.string.body_page_count, display.page + 1, display.pageCount)
            binding.buttonPrevPage.isEnabled = display.page > 0 && !display.searching
            binding.buttonNextPage.isEnabled = display.page + 1 < display.pageCount && !display.searching
        }
    }

    private fun scrollToMatch(display: RequestInfoViewModel.BodyDisplay) {
        if (availableTabs.getOrNull(binding.viewPager.currentItem) != display.tab) return
        val holder = (binding.viewPager.getChildAt(0) as? RecyclerView)
            ?.findViewHolderForAdapterPosition(binding.viewPager.currentItem)
            as? RequestInfoPagerAdapter.RequestInfoViewHolder ?: return
        holder.showBody(display)
        val scrollView = holder.binding.scrollView
        val textView = if (display.tab == RequestInfoViewModel.TAB_REQUEST_BODY) {
            holder.binding.requestBodyText
        } else holder.binding.responseBodyText
        textView.post {
            if (_binding == null || viewModel.bodyDisplay.value !== display) return@post
            val layout: Layout = textView.layout ?: return@post
            val offset = display.matchStart.coerceIn(0, textView.length())
            val line = layout.getLineForOffset(offset)
            val rect = Rect(0, layout.getLineTop(line), 0, 0)
            scrollView.offsetDescendantRectToMyCoords(textView, rect)
            val y = (rect.top - scrollView.height / 3).coerceAtLeast(0)
            if (MotionUtils.areAnimationsEnabled(textView.context)) scrollView.smoothScrollTo(0, y)
            else scrollView.scrollTo(0, y)
        }
    }

    private fun getAvailableTabs(): List<String> = arguments?.run {
        mutableListOf<String>().apply {
            val isHttpRequest =
                !getString("method").isNullOrEmpty() || !getString("urlString").isNullOrEmpty()

            if (!getString("dnsHost").isNullOrEmpty()) add("DNS Info")

            if (isHttpRequest) {
                add("Request")
                if (!getString("requestBodyUriString").isNullOrEmpty())
                    add(RequestInfoViewModel.TAB_REQUEST_BODY)

                if ((getString("responseCode")?.toIntOrNull() ?: -1) >= 100 ||
                    !getString("responseHeaders").isNullOrEmpty()) add("Response")
                if (!getString("responseBodyUriString").isNullOrEmpty()) {
                    add(RequestInfoViewModel.TAB_RESPONSE_BODY)
                }
            }

            if (!getString("stack").isNullOrEmpty()) add("Stack")
        }
    } ?: emptyList()

    private fun isExportTab(tab: String?): Boolean =
        tab == RequestInfoViewModel.TAB_REQUEST_BODY || tab == RequestInfoViewModel.TAB_RESPONSE_BODY

    private fun bindFabToCurrentPage() {
        boundScrollView?.setOnScrollChangeListener(null as NestedScrollView.OnScrollChangeListener?)
        boundScrollView = null

        val recyclerView = binding.viewPager.getChildAt(0) as? RecyclerView ?: return
        val holder = recyclerView.findViewHolderForAdapterPosition(binding.viewPager.currentItem)
            as? RequestInfoPagerAdapter.RequestInfoViewHolder ?: return
        boundScrollView = holder.binding.scrollView
        boundScrollView?.setOnScrollChangeListener(scrollListener)
    }

    private fun updateFabVisibility(tabText: String) {
        val fabIsVisible = isExportTab(tabText)
        if (fabIsVisible) {
            binding.exportFab.show()
            bindFabToCurrentPage()
        } else {
            binding.exportFab.hide()
        }

        binding.exportFab.setOnClickListener {
            if (tabText == RequestInfoViewModel.TAB_REQUEST_BODY) {
                exportRequestBody()
            } else if (tabText == RequestInfoViewModel.TAB_RESPONSE_BODY) {
                exportResponseContent()
            }
        }
    }

    override fun onDestroyView() {
        boundScrollView?.setOnScrollChangeListener(null as NestedScrollView.OnScrollChangeListener?)
        boundScrollView = null
        super.onDestroyView()
    }

    private fun exportRequestBody() {
        val content = viewModel.requestBody.value
        if (content.isNullOrEmpty()) {
            binding.root.showFeedback(getString(R.string.content_empty_or_loading))
            return
        }
        contentToSave = content
        val ext =
            if (content.trimStart().startsWith("{") || content.trimStart().startsWith("["))
                "json" else "txt"
        val fileName = "requestbody_${FILE_DATE_FORMAT.format(Date())}.$ext"
        createDocumentLauncher.launch(fileName)
    }

    private fun exportResponseBodyText(content: String) {
        if (content.isEmpty()) {
            binding.root.showFeedback(getString(R.string.content_empty_or_loading))
            return
        }
        contentToSave = content
        val ext =
            if (content.trimStart().startsWith("{") || content.trimStart().startsWith("["))
                "json" else "txt"
        val fileName = "responsebody_${FILE_DATE_FORMAT.format(Date())}.$ext"
        createDocumentLauncher.launch(fileName)
    }

    private fun saveTextToFile(uri: Uri, content: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                requireContext().contentResolver.openOutputStream(uri)?.use {
                    it.write(content.toByteArray(Charsets.UTF_8))
                }
                withContext(Dispatchers.Main) {
                    binding.root.showFeedback(getString(R.string.export_success))
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    binding.root.showFeedback(getString(R.string.export_failed), error = true)
                }
            }
        }
    }

    private fun exportResponseContent() {
        when (val responseData = viewModel.responseBody.value) {
            is ResponseBodyContent.Image -> saveImageToGallery(
                responseData.bytes,
                responseData.mimeType
            )
            is ResponseBodyContent.Text -> exportResponseBodyText(responseData.content)
            else -> binding.root.showFeedback(getString(R.string.content_empty_or_loading))
        }
    }

    private fun saveImageToGallery(bytes: ByteArray, mimeType: String?) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val mime = mimeType?.substringBefore(';') ?: "image/jpeg"
            val fileName =
                "ResponseBody_${System.currentTimeMillis()}.${mime.substringAfter('/', "jpg")}"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            var uri: Uri? = null
            try {
                val resolver = requireContext().contentResolver
                uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    resolver.openOutputStream(it)
                        ?.use { out -> ByteArrayInputStream(bytes).copyTo(out) }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(it, values, null, null)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), getString(R.string.image_saved_to_gallery), Toast.LENGTH_SHORT)
                            .show()
                    }
                } ?: throw IOException("URI generation failed")
            } catch (e: Exception) {
                uri?.let { runCatching { requireContext().contentResolver.delete(it, null, null) } }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(), getString(R.string.image_save_failed, e.message), Toast.LENGTH_SHORT
                    ).show()
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

    companion object {
        private val FILE_DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    }
}
