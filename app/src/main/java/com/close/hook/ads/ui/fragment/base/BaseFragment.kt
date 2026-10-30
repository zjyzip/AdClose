package com.close.hook.ads.ui.fragment.base

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.close.hook.ads.util.INavContainer
import com.close.hook.ads.util.MotionUtils
import com.close.hook.ads.util.dp
import com.google.android.material.transition.MaterialFadeThrough
import java.lang.reflect.ParameterizedType

abstract class BaseFragment<VB : ViewBinding> : Fragment() {

    protected var _binding: VB? = null
    protected val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (MotionUtils.areAnimationsEnabled(requireContext())) {
            enterTransition = MaterialFadeThrough().apply { duration = 250L }
            reenterTransition = MaterialFadeThrough().apply { duration = 250L }
            exitTransition = MaterialFadeThrough().apply { duration = 220L }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val type = javaClass.genericSuperclass as ParameterizedType
        val bindingClass = type.actualTypeArguments[0] as Class<*>
        val method = bindingClass.getDeclaredMethod(
            "inflate",
            LayoutInflater::class.java,
            ViewGroup::class.java,
            Boolean::class.java
        )
        @Suppress("UNCHECKED_CAST")
        _binding = method.invoke(null, inflater, container, false) as VB
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** Attaches a scroll listener that hides/shows the bottom nav after [threshold] dp of scroll. */
    protected fun RecyclerView.attachNavScrollListener(threshold: Int = 20) {
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            private var accumulated = 0
            private val px = threshold.dp

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val nav = activity as? INavContainer ?: return
                accumulated += dy
                when {
                    accumulated > px  -> { nav.hideNavigation(); accumulated = 0 }
                    accumulated < -px -> { nav.showNavigation();  accumulated = 0 }
                }
            }
        })
    }
}
