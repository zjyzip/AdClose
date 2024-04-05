package com.close.hook.ads.ui.fragment.base

import android.content.Context
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.view.isVisible
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


abstract class BasePagerFragment : Fragment(), OnBackPressListener,
    IOnTabClickContainer, OnCLearCLickContainer {

    var _binding: BaseTablayoutViewpagerBinding? = null
    protected val binding get() = _binding!!
    override var tabController: IOnTabClickListener? = null
    override var controller: OnClearClickListener? = null
    abstract val tabList: List<Int>
    private var imm: InputMethodManager? = null
    private var searchJob: Job? = null
    private var lastQuery = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = BaseTablayoutViewpagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        imm = requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        initView()
        initEditText()
        initButton()

    }

    open fun initButton() {
        binding.apply {
            searchIcon.setOnClickListener {
                if (binding.editText.isFocused) {
                    binding.editText.setText("")
                    setIconAndFocus(R.drawable.ic_back_to_magnifier, false)
                } else {
                    setIconAndFocus(R.drawable.ic_magnifier_to_back, true)
                }
            }

            clear.setOnClickListener { binding.editText.setText("") }
        }
    }

    private fun initEditText() {
        binding.editText.onFocusChangeListener =
            View.OnFocusChangeListener { _, hasFocus ->
                setIconAndFocus(
                    if (hasFocus) R.drawable.ic_magnifier_to_back else R.drawable.ic_back_to_magnifier,
                    hasFocus
                )
            }
        binding.editText.addTextChangedListener(textWatcher)
    }

    fun setIconAndFocus(drawableId: Int, focus: Boolean) {
        binding.searchIcon.setImageDrawable(requireContext().getDrawable(drawableId))
        (binding.searchIcon.drawable as? AnimatedVectorDrawable)?.start()
        if (focus) {
            binding.editText.requestFocus()
            imm?.showSoftInput(binding.editText, 0)
        } else {
            binding.editText.clearFocus()
            imm?.hideSoftInputFromWindow(binding.editText.windowToken, 0)
        }
    }

    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            with(s.toString().lowercase()) {
                if (this != lastQuery) {
                    searchJob?.cancel()
                    searchJob = lifecycleScope.launch {
                        delay(if (s.isBlank()) 0L else 300L)
                        searchJob(this@with)
                    }
                    lastQuery = this
                }
            }
        }

        override fun afterTextChanged(s: Editable) {
            binding.clear.isVisible = s.isNotBlank()
        }
    }

    abstract fun searchJob(text: String)

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
        super.onDestroyView()
        binding.editText.removeTextChangedListener(textWatcher)
        _binding = null
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
