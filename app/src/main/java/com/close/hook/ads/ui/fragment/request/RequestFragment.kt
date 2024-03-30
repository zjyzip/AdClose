package com.close.hook.ads.ui.fragment.request

import android.content.res.Configuration
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import com.close.hook.ads.R
import com.close.hook.ads.databinding.BaseTablayoutViewpagerBinding
import com.close.hook.ads.ui.fragment.base.BasePagerFragment
import com.close.hook.ads.util.DensityTool
import com.close.hook.ads.util.IOnFabClickContainer
import com.close.hook.ads.util.IOnFabClickListener
import com.close.hook.ads.util.OnBackPressContainer
import com.close.hook.ads.util.OnBackPressListener
import com.close.hook.ads.util.dp
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.floatingactionbutton.FloatingActionButton

class RequestFragment : BasePagerFragment(), IOnFabClickContainer, OnBackPressContainer {

    override val tabList: List<Int> =
        listOf(R.string.tab_domain_list, R.string.tab_host_list, R.string.tab_host_whitelist)
    override var fabController: IOnFabClickListener? = null
    override var backController: OnBackPressListener? = null
    private lateinit var fab: FloatingActionButton
    private val fabViewBehavior by lazy { HideBottomViewOnScrollBehavior<FloatingActionButton>() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BaseTablayoutViewpagerBinding.inflate(inflater, container, false)
        fab = FloatingActionButton(requireContext()).apply {
            layoutParams = CoordinatorLayout.LayoutParams(
                CoordinatorLayout.LayoutParams.WRAP_CONTENT,
                CoordinatorLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 25.dp, fabMarginBottom)
                gravity = Gravity.BOTTOM or Gravity.END
                behavior = fabViewBehavior
            }
            setImageResource(R.drawable.ic_export)
            tooltipText = getString(R.string.export)
            setOnClickListener { fabController?.onExport() }
        }
        binding.root.addView(fab)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initBar()
    }

    private fun initBar() {
        binding.toolBar.apply {
            inflateMenu(R.menu.menu_clear)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.clear -> controller?.onClearAll()
                }
                true
            }
        }
        binding.editText.hint = getString(R.string.search_hint)
    }

    override fun initView() {
        super.initView()
        binding.viewPager.isUserInputEnabled = false
    }

    override fun searchJob(text: String) {
        controller?.search(text)
    }

    override fun getFragment(position: Int): Fragment =
        when (position) {
            0 -> RequestListFragment.newInstance("all")
            1 -> RequestListFragment.newInstance("block")
            2 -> RequestListFragment.newInstance("pass")
            else -> throw IllegalArgumentException()
        }

    private val fabMarginBottom
        get() =
            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                DensityTool.getNavigationBarHeight(requireContext()) + 105.dp
            else 25.dp

    override fun onBackPressed(): Boolean {
        if (backController?.onBackPressed() == true)
            return true
        if (binding.editText.isFocused) {
            binding.editText.setText("")
            setIconAndFocus(R.drawable.ic_search, false)
            return true
        }
        return false
    }

}
