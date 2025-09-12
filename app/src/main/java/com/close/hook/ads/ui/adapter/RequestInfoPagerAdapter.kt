package com.close.hook.ads.ui.adapter

import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.SpannableString
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
    private val lifecycleOwner: LifecycleOwner
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
                    binding.dnsHostText.text = arguments.getString("dnsHost")
                    binding.fullAddressText.text = arguments.getString("fullAddress")
                }
                "Request" -> {
                    binding.requestSection.visibility = View.VISIBLE
                    binding.methodText.text = arguments.getString("method")
                    binding.urlStringText.text = arguments.getString("urlString")
                    binding.requestHeadersText.text = arguments.getString("requestHeaders")
                }
                "RequestBody" -> {
                    binding.requestBodySection.visibility = View.VISIBLE
                    binding.requestBodyText.visibility = View.GONE
                    viewModel.requestBody.observe(lifecycleOwner) { content ->
                        binding.requestBodyText.post {
                            binding.requestBodyText.text = SpannableString(content)
                            binding.requestBodyText.visibility = View.VISIBLE
                        }
                    }
                }
                "Response" -> {
                    binding.responseSection.visibility = View.VISIBLE
                    binding.responseCodeText.text = arguments.getString("responseCode")
                    binding.responseMessageText.text = arguments.getString("responseMessage")
                    binding.responseHeadersText.text = arguments.getString("responseHeaders")
                }
                "ResponseBody" -> {
                    binding.responseBodySection.visibility = View.VISIBLE
                    setupCollectResponseBodySwitch()
                    binding.responseBodyText.visibility = View.GONE
                    binding.responseBodyImage.visibility = View.GONE

                    viewModel.responseBody.observe(lifecycleOwner) { result ->
                        result.text?.let {
                            binding.responseBodyText.post {
                                binding.responseBodyText.text = SpannableString(it)
                                binding.responseBodyText.visibility = View.VISIBLE
                            }
                        }
                        result.imageBytes?.let {
                            binding.responseBodyImage.setImageBitmap(BitmapFactory.decodeByteArray(it, 0, it.size))
                            binding.responseBodyImage.visibility = View.VISIBLE
                        }
                    }
                }
                "Stack" -> {
                    binding.stackSection.visibility = View.VISIBLE
                    binding.stackText.text = arguments.getString("stack")
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
