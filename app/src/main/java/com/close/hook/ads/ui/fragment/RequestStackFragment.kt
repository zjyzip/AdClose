package com.close.hook.ads.ui.fragment

import android.os.Bundle
import android.view.View
import com.close.hook.ads.databinding.FragmentStackBinding

class RequestStackFragment : BaseFragment<FragmentStackBinding>() {

    companion object {
        private const val STACK = "stack"

        fun newInstance(stack: String?): RequestStackFragment {
            return RequestStackFragment().apply {
                arguments = Bundle().apply {
                    putString(STACK, stack)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val stack = arguments?.getString(STACK) ?: ""
        binding.stackText.setText(stack)
    }
}
