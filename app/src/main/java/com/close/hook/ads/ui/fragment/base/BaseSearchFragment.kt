package com.close.hook.ads.ui.fragment.base

import android.content.Context
import android.graphics.drawable.AnimatedVectorDrawable
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.viewbinding.ViewBinding

abstract class BaseSearchFragment<VB : ViewBinding> : BaseFragment<VB>() {

    protected val imm by lazy {
        requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }

    protected abstract val searchIconView: ImageView
    protected abstract val searchEditTextView: EditText

    protected fun setIconAndFocus(drawableId: Int, focus: Boolean) {
        searchIconView.setImageDrawable(ContextCompat.getDrawable(requireContext(), drawableId))
        (searchIconView.drawable as? AnimatedVectorDrawable)?.start()
        if (focus) {
            searchEditTextView.requestFocus()
            imm.showSoftInput(searchEditTextView, InputMethodManager.SHOW_IMPLICIT)
        } else {
            searchEditTextView.clearFocus()
            imm.hideSoftInputFromWindow(searchEditTextView.windowToken, 0)
        }
    }
}
