package com.close.hook.ads.ui.fragment.request

import android.os.Bundle
import android.util.Base64
import android.view.View
import com.close.hook.ads.databinding.FragmentResponseBodyBinding
import com.close.hook.ads.ui.fragment.base.BaseFragment
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

class ResponseBodyInfoFragment : BaseFragment<FragmentResponseBodyBinding>() {

    companion object {
        private const val RESPONSE_BODY = "responseBody"
        private const val IS_BODY_COMPRESSED = "isBodyCompressed"
        private const val JSON_INDENT_SPACES = 4
        private const val BUFFER_SIZE = 8192

        fun newInstance(responseBody: String?, isBodyCompressed: Boolean): ResponseBodyInfoFragment {
            return ResponseBodyInfoFragment().apply {
                arguments = Bundle().apply {
                    putString(RESPONSE_BODY, responseBody)
                    putBoolean(IS_BODY_COMPRESSED, isBodyCompressed)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        var responseBody = arguments?.getString(RESPONSE_BODY) ?: ""
        val isBodyCompressed = arguments?.getBoolean(IS_BODY_COMPRESSED, false) ?: false

        if (responseBody.isEmpty()) {
            binding.responseBodyText.text = "No Response Body Available"
            return
        }

        if (isBodyCompressed) {
            try {
                responseBody = decompressString(responseBody)
            } catch (e: IOException) {
                binding.responseBodyText.text = "Error decompressing body: ${e.message}\n\nOriginal (compressed) body:\n$responseBody"
                return
            }
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

    private fun decompressString(compressedBase64Data: String): String {
        if (compressedBase64Data.isEmpty()) {
            return compressedBase64Data
        }
        val decoded = Base64.decode(compressedBase64Data, Base64.DEFAULT)
        val bos = ByteArrayOutputStream(decoded.size * 2) 
        
        GZIPInputStream(ByteArrayInputStream(decoded)).use { gzip ->
            val buffer = ByteArray(BUFFER_SIZE)
            var len: Int
            while (gzip.read(buffer).also { len = it } != -1) {
                bos.write(buffer, 0, len)
            }
        }
        return bos.toString(StandardCharsets.UTF_8.name())
    }
}
