package com.close.hook.ads.ui.adapter

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.databinding.ItemRequestInfoBinding
import com.close.hook.ads.preference.HookPrefs
import com.close.hook.ads.ui.viewmodel.RequestInfoViewModel
import com.close.hook.ads.ui.viewmodel.ResponseBodyResult
import com.close.hook.ads.ui.viewmodel.SearchState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class RequestInfoPagerAdapter(
    private val arguments: Bundle,
    private val tabs: List<String>,
    private val viewModel: RequestInfoViewModel,
    private val lifecycleScope: LifecycleCoroutineScope
) : RecyclerView.Adapter<RequestInfoPagerAdapter.RequestInfoViewHolder>() {

    private var searchState: SearchState = SearchState()

    fun updateSearchState(newState: SearchState) {
        val oldQuery = this.searchState.query
        this.searchState = newState
        if (oldQuery != newState.query) {
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RequestInfoViewHolder {
        val binding = ItemRequestInfoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RequestInfoViewHolder(binding)
    }

    override fun getItemCount(): Int = tabs.size

    override fun onBindViewHolder(holder: RequestInfoViewHolder, position: Int) {
        holder.bind(tabs[position])
    }

    inner class RequestInfoViewHolder(val binding: ItemRequestInfoBinding) : RecyclerView.ViewHolder(binding.root) {
        
        init {
            lifecycleScope.launch {
                viewModel.requestBody.collectLatest { content ->
                    if (bindingAdapterPosition != RecyclerView.NO_POSITION && tabs[bindingAdapterPosition] == "RequestBody") {
                        content?.let { updateWithRequestBody(it) }
                    }
                }
            }
            lifecycleScope.launch {
                viewModel.responseBody.collectLatest { result ->
                    if (bindingAdapterPosition != RecyclerView.NO_POSITION && tabs[bindingAdapterPosition] == "ResponseBody") {
                        result?.let { updateWithResponseBody(it) }
                    }
                }
            }
        }

        fun bind(tabTitle: String) {
            val sections = listOf(
                binding.dnsSection, binding.requestSection, binding.requestBodySection,
                binding.responseSection, binding.responseBodySection, binding.stackSection
            )
            sections.forEach { it.visibility = View.GONE }

            val query = searchState.query
            fun highlightTextView(textView: TextView, fullText: String?) {
                if (fullText.isNullOrEmpty()) {
                    textView.text = ""
                    return
                }
                textView.text = if (query.isEmpty()) {
                    SpannableString(fullText)
                } else {
                    val spannable = SpannableString(fullText)
                    var index = fullText.lowercase().indexOf(query.lowercase())
                    while (index >= 0) {
                        spannable.setSpan(BackgroundColorSpan(Color.YELLOW), index, index + query.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        index = fullText.lowercase().indexOf(query.lowercase(), index + 1)
                    }
                    spannable
                }
            }
            
            when (tabTitle) {
                "DNS Info" -> {
                    binding.dnsSection.visibility = View.VISIBLE
                    highlightTextView(binding.dnsHostText, arguments.getString("dnsHost"))
                    highlightTextView(binding.fullAddressText, arguments.getString("fullAddress"))
                }
                "Request" -> {
                    binding.requestSection.visibility = View.VISIBLE
                    highlightTextView(binding.methodText, arguments.getString("method"))
                    highlightTextView(binding.urlStringText, arguments.getString("urlString"))
                    highlightTextView(binding.requestHeadersText, arguments.getString("requestHeaders"))
                }
                "RequestBody" -> {
                    binding.requestBodySection.visibility = View.VISIBLE
                    viewModel.requestBody.value?.let { updateWithRequestBody(it) }
                }
                "Response" -> {
                    binding.responseSection.visibility = View.VISIBLE
                    highlightTextView(binding.responseCodeText, arguments.getString("responseCode"))
                    highlightTextView(binding.responseMessageText, arguments.getString("responseMessage"))
                    highlightTextView(binding.responseHeadersText, arguments.getString("responseHeaders"))
                }
                "ResponseBody" -> {
                    binding.responseBodySection.visibility = View.VISIBLE
                    setupCollectResponseBodySwitch()
                    viewModel.responseBody.value?.let { updateWithResponseBody(it) }
                }
                "Stack" -> {
                    binding.stackSection.visibility = View.VISIBLE
                    highlightTextView(binding.stackText, arguments.getString("stack"))
                }
            }
        }

        fun updateHighlight(highlightedText: CharSequence?) {
            if (highlightedText == null) return
            
            val currentTab = tabs.getOrNull(bindingAdapterPosition)
            val textView = when (currentTab) {
                "RequestBody" -> binding.requestBodyText
                "ResponseBody" -> binding.responseBodyText
                else -> null
            }
            textView?.text = highlightedText
        }

        private fun updateWithRequestBody(content: String) {
            binding.requestBodyText.visibility = View.VISIBLE
            binding.responseBodyImage.visibility = View.GONE
            binding.requestBodyText.text = if (searchState.query.isNotEmpty() && content == searchState.textContent?.toString()) {
                searchState.textContent
            } else {
                SpannableString(content)
            }
        }

        private fun updateWithResponseBody(result: ResponseBodyResult) {
            binding.requestBodyText.visibility = View.GONE
            binding.responseBodyImage.visibility = View.GONE
            result.text?.let {
                binding.responseBodyText.visibility = View.VISIBLE
                binding.responseBodyText.text = if (searchState.query.isNotEmpty() && it == searchState.textContent?.toString()) {
                    searchState.textContent
                } else {
                    SpannableString(it)
                }
            }
            result.imageBytes?.let {
                binding.responseBodyImage.visibility = View.VISIBLE
                binding.responseBodyImage.setImageBitmap(BitmapFactory.decodeByteArray(it, 0, it.size))
            }
            result.error?.let {
                binding.responseBodyText.visibility = View.VISIBLE
                binding.responseBodyText.text = it
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
    }
}
