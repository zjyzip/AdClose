package com.close.hook.ads.ui.fragment.request

import android.os.Bundle
import android.view.View
import com.close.hook.ads.databinding.FragmentResponseBodyBinding
import com.close.hook.ads.ui.fragment.base.BaseFragment
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException

class ResponseBodyInfoFragment : BaseFragment<FragmentResponseBodyBinding>() {

    companion object {
        private const val RESPONSE_BODY = "responseBody"
        private const val JSON_INDENT_SPACES = 4

        fun newInstance(responseBody: String?): ResponseBodyInfoFragment {
            return ResponseBodyInfoFragment().apply {
                arguments = Bundle().apply {
                    putString(RESPONSE_BODY, responseBody)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val responseBody = arguments?.getString(RESPONSE_BODY) ?: ""

        if (responseBody.isEmpty()) {
            binding.responseBodyText.text = "No Response Body Available"
            return
        }

        try {
            val formattedJson = if (responseBody.trim().startsWith("[")) {
                JSONArray(responseBody).toString(JSON_INDENT_SPACES)
            } else {
                JSONObject(responseBody).toString(JSON_INDENT_SPACES)
            }
            binding.responseBodyText.text = formattedJson
        } catch (e: JSONException) {
            binding.responseBodyText.text = responseBody
        }
    }
}
