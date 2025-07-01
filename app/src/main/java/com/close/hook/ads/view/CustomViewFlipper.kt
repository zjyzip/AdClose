package com.close.hook.ads.view

import android.content.Context
import android.util.AttributeSet
import android.widget.ViewFlipper
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CustomViewFlipper : ViewFlipper {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    private var listener: OnDisplayedChildChangedListener? = null

    override fun setDisplayedChild(whichChild: Int) {
        super.setDisplayedChild(whichChild)
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            withContext(Dispatchers.Main) {
                listener?.onChanged(whichChild)
            }
        }
    }

    fun getOnDisplayedChildChangedListener() = listener

    fun setOnDisplayedChildChangedListener(listener: OnDisplayedChildChangedListener) {
        this.listener = listener
    }

    fun setOnDisplayedChildChangedListener(onChanged: (whichChild: Int) -> Unit) {
        this.listener = object : OnDisplayedChildChangedListener {
            override fun onChanged(whichChild: Int) = onChanged(whichChild)
        }
    }

    interface OnDisplayedChildChangedListener {
        fun onChanged(whichChild: Int)
    }
}
