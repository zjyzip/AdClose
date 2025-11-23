package com.close.hook.ads.ui.adapter

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.data.model.ResponseBodyContent
import com.close.hook.ads.databinding.ItemRequestInfoBinding
import com.close.hook.ads.preference.HookPrefs
import com.close.hook.ads.ui.viewmodel.RequestInfoViewModel

class RequestInfoPagerAdapter(
    private val arguments: Bundle,
    private val tabs: List<String>,
    private val viewModel: RequestInfoViewModel
) : RecyclerView.Adapter<RequestInfoPagerAdapter.RequestInfoViewHolder>() {

    object Payload {
        const val REFRESH_HIGHLIGHT = 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RequestInfoViewHolder {
        val binding =
            ItemRequestInfoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RequestInfoViewHolder(binding)
    }

    override fun getItemCount(): Int = tabs.size

    override fun onBindViewHolder(holder: RequestInfoViewHolder, position: Int) {
        holder.bind(tabs[position])
    }

    override fun onBindViewHolder(
        holder: RequestInfoViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }

        payloads.forEach { payload ->
            when (payload) {
                Payload.REFRESH_HIGHLIGHT -> holder.refreshHighlights()
                is CharSequence -> holder.updateTextHighlight(payload)
                is String -> holder.updateRequestBodyContent(payload)
                is ResponseBodyContent -> holder.updateResponseBodyContent(payload)
            }
        }
    }

    inner class RequestInfoViewHolder(val binding: ItemRequestInfoBinding) :
        RecyclerView.ViewHolder(binding.root) {
        
        fun bind(tabTitle: String) {
            val sections = listOf(
                binding.dnsSection, binding.requestSection, binding.requestBodySection,
                binding.responseSection, binding.responseBodySection, binding.stackSection
            )
            sections.forEach { it.visibility = View.GONE }

            when (tabTitle) {
                "DNS Info" -> {
                    binding.dnsSection.visibility = View.VISIBLE
                    updateStaticTextViews(
                        binding.dnsHostText to arguments.getString("dnsHost"),
                        binding.fullAddressText to arguments.getString("fullAddress")
                    )
                }
                "Request" -> {
                    binding.requestSection.visibility = View.VISIBLE
                    updateStaticTextViews(
                        binding.methodText to arguments.getString("method"),
                        binding.urlStringText to arguments.getString("urlString"),
                        binding.requestHeadersText to arguments.getString("requestHeaders")
                    )
                }
                "RequestBody" -> {
                    binding.requestBodySection.visibility = View.VISIBLE
                    viewModel.requestBody.value?.let { updateRequestBodyContent(it) }
                }
                "Response" -> {
                    binding.responseSection.visibility = View.VISIBLE
                    updateStaticTextViews(
                        binding.responseCodeText to arguments.getString("responseCode"),
                        binding.responseMessageText to arguments.getString("responseMessage"),
                        binding.responseHeadersText to arguments.getString("responseHeaders")
                    )
                }
                "ResponseBody" -> {
                    binding.responseBodySection.visibility = View.VISIBLE
                    setupCollectResponseBodySwitch()
                    updateResponseBodyContent(viewModel.responseBody.value)
                }
                "Stack" -> {
                    binding.stackSection.visibility = View.VISIBLE
                    updateStaticTextViews(binding.stackText to arguments.getString("stack"))
                }
            }
        }

        fun refreshHighlights() {
            bind(tabs[bindingAdapterPosition])
        }

        fun updateTextHighlight(highlightedText: CharSequence) {
            val textView = when (tabs.getOrNull(bindingAdapterPosition)) {
                "RequestBody" -> binding.requestBodyText
                "ResponseBody" -> binding.responseBodyText
                else -> null
            }
            textView?.text = highlightedText
        }
        
        fun updateRequestBodyContent(content: String) {
            binding.requestBodyText.visibility = View.VISIBLE
            binding.responseBodyImage.visibility = View.GONE
            binding.requestBodyText.text = content
        }

        fun updateResponseBodyContent(result: ResponseBodyContent) {
            binding.responseBodyText.visibility = View.GONE
            binding.responseBodyImage.visibility = View.GONE
            
            when(result) {
                is ResponseBodyContent.Text -> {
                    binding.responseBodyText.visibility = View.VISIBLE
                    binding.responseBodyText.text = result.content
                }
                is ResponseBodyContent.Image -> {
                    binding.responseBodyImage.visibility = View.VISIBLE
                    val bytes = result.bytes
                    binding.responseBodyImage.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                }
                is ResponseBodyContent.Error -> {
                    binding.responseBodyText.visibility = View.VISIBLE
                    binding.responseBodyText.text = result.message
                }
                is ResponseBodyContent.Loading -> {
                    binding.responseBodyText.visibility = View.VISIBLE
                    binding.responseBodyText.text = "Loading..."
                }
            }
        }

        private fun updateStaticTextViews(vararg pairs: Pair<TextView, String?>) {
            pairs.forEach { (textView, text) ->
                if (text != null) {
                    textView.text = viewModel.createHighlightedText(text, viewModel.currentQuery.value, currentIndex = -1)
                } else {
                    textView.text = ""
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
    }
}
