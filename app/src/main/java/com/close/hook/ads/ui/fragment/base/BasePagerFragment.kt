package com.close.hook.ads.ui.fragment.base

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.close.hook.ads.R
import com.close.hook.ads.databinding.BaseTablayoutViewpagerBinding
import com.close.hook.ads.util.IOnTabClickContainer
import com.close.hook.ads.util.IOnTabClickListener
import com.close.hook.ads.util.OnBackPressContainer
import com.close.hook.ads.util.OnBackPressListener
import com.close.hook.ads.util.OnCLearCLickContainer
import com.close.hook.ads.util.OnClearClickListener
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

abstract class BasePagerFragment : BaseSearchFragment<BaseTablayoutViewpagerBinding>(), OnBackPressListener,
    IOnTabClickContainer, OnCLearCLickContainer {

    override var tabController: IOnTabClickListener? = null
    override var controller: OnClearClickListener? = null
    abstract val tabList: List<Int>
    private var searchJob: Job? = null

    override val searchIconView: ImageView get() = binding.searchIcon
    override val searchEditTextView: EditText get() = binding.editText

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initView()
        initEditText()
        initButton()
    }

    open fun initButton() {
        binding.searchIcon.setOnClickListener {
            if (binding.editText.isFocused) {
                binding.editText.setText("")
                setIconAndFocus(R.drawable.ic_back_to_magnifier, false)
            } else {
                setIconAndFocus(R.drawable.ic_magnifier_to_back, true)
            }
        }
        binding.clear.setOnClickListener { binding.editText.setText("") }
    }

    private fun initEditText() {
        binding.editText.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            setIconAndFocus(
                if (hasFocus) R.drawable.ic_magnifier_to_back else R.drawable.ic_back_to_magnifier,
                hasFocus
            )
        }

        binding.editText.addTextChangedListener { text ->
            val query = text.toString().lowercase()
            binding.clear.isVisible = query.isNotBlank()
            searchJob?.cancel()
            searchJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(300L)
                search(query)
            }
        }
    }

    abstract fun search(text: String)

    open fun initView() {
        binding.viewPager.offscreenPageLimit = tabList.size
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun createFragment(position: Int) = getFragment(position)
            override fun getItemCount() = tabList.size
        }

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = getString(tabList[position])
        }.attach()

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {}
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {
                tabController?.onReturnTop()
            }
        })
    }

    abstract fun getFragment(position: Int): Fragment

    override fun onDestroyView() {
        searchJob?.cancel()
        super.onDestroyView()
    }

    override fun onPause() {
        super.onPause()
        (activity as? OnBackPressContainer)?.backController = null
    }

    override fun onResume() {
        super.onResume()
        (activity as? OnBackPressContainer)?.backController = this
    }

    override fun onBackPressed(): Boolean {
        if (binding.editText.isFocused) {
            binding.editText.setText("")
            setIconAndFocus(R.drawable.ic_back_to_magnifier, false)
            return true
        }
        return false
    }
}
