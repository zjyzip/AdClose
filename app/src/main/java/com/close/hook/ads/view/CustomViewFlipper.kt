package com.close.hook.ads.view

import android.content.Context
import android.util.AttributeSet
import android.widget.ViewFlipper
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.close.hook.ads.ui.fragment.base.BaseFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CustomViewFlipper : ViewFlipper {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    private var mOnDisplayedChildChangedListener: OnDisplayedChildChangedListener? = null

    override fun setDisplayedChild(whichChild: Int) {
        super.setDisplayedChild(whichChild)
        (context as? FragmentActivity)?.supportFragmentManager?.findFragmentById(id)?.let {
            (it as? BaseFragment<*>)?.lifecycleScope?.launch {
                withContext(Dispatchers.Main) {
                    mOnDisplayedChildChangedListener?.onChanged(whichChild)
                }
            }
        }
    }

    fun getOnDisplayedChildChangedListener() = mOnDisplayedChildChangedListener

    fun setOnDisplayedChildChangedListener(listener: OnDisplayedChildChangedListener) {
        mOnDisplayedChildChangedListener = listener
    }

    fun setOnDisplayedChildChangedListener(onChanged: OnDisplayedChildChangedListener.() -> Unit) {
        mOnDisplayedChildChangedListener = object : OnDisplayedChildChangedListener {
            override fun onChanged(whichChild: Int) {
                onChanged(whichChild)
            }
        }
    }

    interface OnDisplayedChildChangedListener {
        fun onChanged(whichChild: Int)
    }
}
