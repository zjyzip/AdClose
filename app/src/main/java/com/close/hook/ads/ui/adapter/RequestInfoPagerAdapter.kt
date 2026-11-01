package com.close.hook.ads.ui.adapter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.R
import com.close.hook.ads.data.model.ResponseBodyContent
import com.close.hook.ads.databinding.ItemRequestInfoBinding
import com.close.hook.ads.preference.HookPrefs
import com.close.hook.ads.ui.viewmodel.RequestInfoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RequestInfoPagerAdapter(
    private val arguments: Bundle,
    private val tabs: List<String>,
    private val viewModel: RequestInfoViewModel,
    private val lifecycleOwner: LifecycleOwner
) : RecyclerView.Adapter<RequestInfoPagerAdapter.RequestInfoViewHolder>() {

    object Payload {
        const val REFRESH_HIGHLIGHT = 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RequestInfoViewHolder {
        val binding =
            ItemRequestInfoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RequestInfoViewHolder(binding)
    }

    override fun onViewRecycled(holder: RequestInfoViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
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
                is RequestInfoViewModel.BodyDisplay -> holder.showBody(payload)
                is CharSequence -> holder.updateRequestBodyContent(payload.toString())
                is ResponseBodyContent -> holder.updateResponseBodyContent(payload)
            }
        }
    }

    inner class RequestInfoViewHolder(val binding: ItemRequestInfoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var imageJob: Job? = null
        private var shownBodyPage: Pair<String, Int>? = null

        fun recycle() {
            imageJob?.cancel()
            imageJob = null
            binding.responseBodyImage.setImageDrawable(null)
        }

        private val stackGestureDetector = GestureDetector(
            binding.root.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = true

                override fun onLongPress(event: MotionEvent) {
                    binding.stackText.parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
        )

        fun bind(tabTitle: String) {
            val sections = listOf(
                binding.dnsSection, binding.requestSection, binding.requestBodySection,
                binding.responseSection, binding.responseBodySection, binding.stackSection
            )
            sections.forEach { it.visibility = View.GONE }
            imageJob?.cancel()
            binding.bodyPagingHint.visibility = View.GONE
            binding.stackText.setOnTouchListener(null)

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
                RequestInfoViewModel.TAB_REQUEST_BODY -> {
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
                RequestInfoViewModel.TAB_RESPONSE_BODY -> {
                    binding.responseBodySection.visibility = View.VISIBLE
                    setupCollectResponseBodySwitch()
                    updateResponseBodyContent(viewModel.responseBody.value)
                }
                "Stack" -> {
                    binding.stackSection.visibility = View.VISIBLE
                    binding.stackText.apply {
                        setTextIsSelectable(true)
                        isFocusableInTouchMode = true
                        isLongClickable = true
                        setOnTouchListener { view, event ->
                            stackGestureDetector.onTouchEvent(event)
                            if (event.actionMasked == MotionEvent.ACTION_UP ||
                                event.actionMasked == MotionEvent.ACTION_CANCEL
                            ) {
                                view.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                            false
                        }
                    }
                    updateStaticTextViews(binding.stackText to arguments.getString("stack"))
                }
            }
        }

        fun refreshHighlights() {
            tabs.getOrNull(bindingAdapterPosition)?.let(::bind)
        }

        fun showBody(display: RequestInfoViewModel.BodyDisplay) {
            if (tabs.getOrNull(bindingAdapterPosition) != display.tab) return
            val textView = if (display.tab == RequestInfoViewModel.TAB_REQUEST_BODY) {
                binding.requestBodyText
            } else binding.responseBodyText
            binding.bodyPagingHint.visibility = if (display.pageCount > 1) View.VISIBLE else View.GONE
            val pageKey = display.tab to display.page
            if (shownBodyPage != pageKey) {
                binding.scrollView.scrollTo(0, 0)
                shownBodyPage = pageKey
            }
            textView.visibility = View.VISIBLE
            // Do not reset the TextView/selection for duplicate state deliveries.
            if (textView.text !== display.text) textView.text = display.text
        }

        fun updateRequestBodyContent(content: String) {
            binding.requestBodyText.visibility = View.VISIBLE
            binding.responseBodyImage.visibility = View.GONE
            viewModel.displayFor(RequestInfoViewModel.TAB_REQUEST_BODY)?.let(::showBody)
        }

        fun updateResponseBodyContent(result: ResponseBodyContent) {
            binding.responseBodyText.visibility = View.GONE
            binding.responseBodyImage.visibility = View.GONE

            when (result) {
                is ResponseBodyContent.Text -> {
                    binding.responseBodyText.visibility = View.VISIBLE
                    viewModel.displayFor(RequestInfoViewModel.TAB_RESPONSE_BODY)?.let(::showBody)
                }
                is ResponseBodyContent.Image -> {
                    binding.responseBodyImage.visibility = View.VISIBLE
                    decodeAndSetImage(result.bytes)
                }
                is ResponseBodyContent.Error -> {
                    binding.responseBodyText.visibility = View.VISIBLE
                    binding.responseBodyText.text = result.message
                }
                is ResponseBodyContent.Loading -> {
                    binding.responseBodyText.visibility = View.VISIBLE
                    binding.responseBodyText.setText(R.string.body_loading)
                }
            }
        }

        private fun decodeAndSetImage(bytes: ByteArray) {
            imageJob?.cancel()
            imageJob = lifecycleOwner.lifecycleScope.launch {
                val bitmap: Bitmap? = withContext(Dispatchers.IO) {
                    try {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (bitmap != null) {
                    binding.responseBodyImage.setImageBitmap(bitmap)
                } else {
                    binding.responseBodyImage.visibility = View.GONE
                    binding.responseBodyText.visibility = View.VISIBLE
                    binding.responseBodyText.setText(R.string.body_image_decode_failed)
                }
            }
        }

        private fun updateStaticTextViews(vararg pairs: Pair<TextView, String?>) {
            pairs.forEach { (textView, text) ->
                textView.text = if (text != null) {
                    viewModel.createHighlightedText(
                        text, viewModel.currentQuery.value, currentIndex = -1
                    )
                } else {
                    ""
                }
            }
        }

        private fun setupCollectResponseBodySwitch() {
            binding.collectResponseBodySwitch.apply {
                setOnCheckedChangeListener(null)
                isChecked = HookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false)
                setOnCheckedChangeListener { _, isChecked ->
                    HookPrefs.setBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, isChecked)
                }
            }
        }
    }
}
