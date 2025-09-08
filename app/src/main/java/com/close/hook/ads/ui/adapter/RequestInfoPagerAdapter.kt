package com.close.hook.ads.ui.adapter

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.R
import com.close.hook.ads.databinding.ItemRequestInfoBinding
import com.close.hook.ads.preference.HookPrefs
import com.close.hook.ads.ui.viewmodel.RequestInfoViewModel

class RequestInfoPagerAdapter(
    private val arguments: Bundle,
    private val tabs: List<String>,
    private val viewModel: RequestInfoViewModel,
    private val lifecycleOwner: LifecycleOwner,
    private val onTextViewPopulated: (viewId: Int, text: CharSequence) -> Unit
) : RecyclerView.Adapter<RequestInfoPagerAdapter.RequestInfoViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RequestInfoViewHolder {
        val binding = ItemRequestInfoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RequestInfoViewHolder(binding)
    }

    override fun getItemCount(): Int = tabs.size

    override fun onBindViewHolder(holder: RequestInfoViewHolder, position: Int) {
        holder.bind(tabs[position])
    }

    inner class RequestInfoViewHolder(private val binding: ItemRequestInfoBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(tabTitle: String) {
            val sections = listOf(binding.dnsSection, binding.requestSection, binding.requestBodySection,
                binding.responseSection, binding.responseBodySection, binding.stackSection)
            sections.forEach { it.visibility = View.GONE }

            when (tabTitle) {
                "DNS Info" -> {
                    binding.dnsSection.visibility = View.VISIBLE
                    binding.dnsHostText.text = arguments.getString("dnsHost")?.also { onTextViewPopulated(R.id.dnsHostText, it) }
                    binding.fullAddressText.text = arguments.getString("fullAddress")?.also { onTextViewPopulated(R.id.fullAddressText, it) }
                }
                "Request" -> {
                    binding.requestSection.visibility = View.VISIBLE
                    binding.methodText.text = arguments.getString("method")?.also { onTextViewPopulated(R.id.methodText, it) }
                    binding.urlStringText.text = arguments.getString("urlString")?.also { onTextViewPopulated(R.id.urlStringText, it) }
                    binding.requestHeadersText.text = arguments.getString("requestHeaders")?.also { onTextViewPopulated(R.id.requestHeadersText, it) }
                }
                "RequestBody" -> {
                    binding.requestBodySection.visibility = View.VISIBLE
                    viewModel.requestBody.observe(lifecycleOwner) { content ->
                        binding.requestBodyText.text = content
                        onTextViewPopulated(R.id.requestBodyText, content)
                    }
                }
                "Response" -> {
                    binding.responseSection.visibility = View.VISIBLE
                    binding.responseCodeText.text = arguments.getString("responseCode")?.also { onTextViewPopulated(R.id.responseCodeText, it) }
                    binding.responseMessageText.text = arguments.getString("responseMessage")?.also { onTextViewPopulated(R.id.responseMessageText, it) }
                    binding.responseHeadersText.text = arguments.getString("responseHeaders")?.also { onTextViewPopulated(R.id.responseHeadersText, it) }
                }
                "ResponseBody" -> {
                    binding.responseBodySection.visibility = View.VISIBLE
                    setupCollectResponseBodySwitch()
                    viewModel.responseBody.observe(lifecycleOwner) { result ->
                        result.text?.let {
                            binding.responseBodyText.text = it
                            binding.responseBodyText.visibility = View.VISIBLE
                            binding.responseBodyImage.visibility = View.GONE
                            onTextViewPopulated(R.id.responseBodyText, it)
                        }
                        result.imageBytes?.let {
                            val bitmap = BitmapFactory.decodeByteArray(it, 0, it.size)
                            binding.responseBodyImage.setImageBitmap(bitmap)
                            binding.responseBodyImage.visibility = View.VISIBLE
                            binding.responseBodyText.visibility = View.GONE
                        }
                    }
                }
                "Stack" -> {
                    binding.stackSection.visibility = View.VISIBLE
                    binding.stackText.text = arguments.getString("stack")?.also { onTextViewPopulated(R.id.stackText, it) }
                }
            }
        }
        
        private fun setupCollectResponseBodySwitch() {
            binding.collectResponseBodySwitch.apply {
                isChecked = HookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false)
                setOnCheckedChangeListener { _, isChecked -> HookPrefs.setBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, isChecked) }
            }
        }
    }
}
