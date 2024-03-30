package com.close.hook.ads.ui.fragment.request

import android.os.Bundle
import android.view.View
import com.close.hook.ads.databinding.FragmentResponseBinding
import com.close.hook.ads.ui.fragment.base.BaseFragment

class ResponseInfoFragment : BaseFragment<FragmentResponseBinding>() {

    companion object {
        private const val RESPONSE_CODE = "responseCode"
        private const val RESPONSE_MESSAGE = "responseMessage"
        private const val RESPONSE_HEADERS = "responseHeaders"

        fun newInstance(responseCode: String?, responseMessage: String?, responseHeaders: String?): ResponseInfoFragment {
            return ResponseInfoFragment().apply {
                arguments = Bundle().apply {
                    putString(RESPONSE_CODE, responseCode)
                    putString(RESPONSE_MESSAGE, responseMessage)
                    putString(RESPONSE_HEADERS, responseHeaders)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val responseCode = arguments?.getString(RESPONSE_CODE) ?: ""
        val responseMessage = arguments?.getString(RESPONSE_MESSAGE) ?: ""
        val responseHeaders = arguments?.getString(RESPONSE_HEADERS) ?: ""

        binding.responseCodeText.setText(responseCode)
        binding.responseMessageText.setText(responseMessage)
        binding.responseHeadersText.setText(responseHeaders)
    }
}
