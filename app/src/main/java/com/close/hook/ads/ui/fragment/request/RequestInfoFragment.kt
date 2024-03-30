package com.close.hook.ads.ui.fragment.request

import android.os.Bundle
import android.view.View
import com.close.hook.ads.databinding.FragmentRequestBinding
import com.close.hook.ads.ui.fragment.base.BaseFragment

class RequestInfoFragment : BaseFragment<FragmentRequestBinding>() {

    companion object {
        private const val METHOD = "method"
        private const val URL_STRING = "urlString"
        private const val REQUEST_HEADERS = "requestHeaders"

        fun newInstance(method: String?, urlString: String?, requestHeaders: String?): RequestInfoFragment {
            return RequestInfoFragment().apply {
                arguments = Bundle().apply {
                    putString(METHOD, method)
                    putString(URL_STRING, urlString)
                    putString(REQUEST_HEADERS, requestHeaders)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val method = arguments?.getString(METHOD) ?: ""
        val urlString = arguments?.getString(URL_STRING) ?: ""
        val requestHeaders = arguments?.getString(REQUEST_HEADERS) ?: ""

        binding.methodText.setText(method)
        binding.urlStringText.setText(urlString)
        binding.requestHeadersText.setText(requestHeaders)
    }
}
