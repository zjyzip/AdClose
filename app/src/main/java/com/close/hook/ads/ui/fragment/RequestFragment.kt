package com.close.hook.ads.ui.fragment

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.View.OnFocusChangeListener
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.close.hook.ads.R
import com.close.hook.ads.databinding.FragmentHostsBinding
import com.close.hook.ads.ui.fragment.RequestListFragment.Companion.newInstance
import com.close.hook.ads.util.DensityTool
import com.close.hook.ads.util.IFabContainer
import com.close.hook.ads.util.IOnFabClickContainer
import com.close.hook.ads.util.IOnFabClickListener
import com.close.hook.ads.util.IOnTabClickContainer
import com.close.hook.ads.util.IOnTabClickListener
import com.close.hook.ads.util.OnBackPressContainer
import com.close.hook.ads.util.OnBackPressFragmentContainer
import com.close.hook.ads.util.OnBackPressFragmentListener
import com.close.hook.ads.util.OnBackPressListener
import com.close.hook.ads.util.OnCLearCLickContainer
import com.close.hook.ads.util.OnClearClickListener
import com.close.hook.ads.util.dp
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayout.OnTabSelectedListener
import com.google.android.material.tabs.TabLayoutMediator
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.TimeUnit

class RequestFragment : BaseFragment<FragmentHostsBinding>(), OnCLearCLickContainer,
    OnBackPressListener, IOnTabClickContainer, IOnFabClickContainer, IFabContainer,
    OnBackPressFragmentContainer {

    private val imm by lazy { requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager }
    private var lastSearchQuery = ""
    private var searchDisposable: Disposable? = null
    private val fabViewBehavior by lazy { HideBottomViewOnScrollBehavior<FloatingActionButton>() }

    override var controller: OnClearClickListener? = null
    override var tabController: IOnTabClickListener? = null
    override var fabController: IOnFabClickListener? = null
    override var backController: OnBackPressFragmentListener? = null

    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            handleSearchTextChange(s.toString())
        }

        override fun afterTextChanged(s: Editable?) {
            binding.searchClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupViewPager()
        initEditText()
        initFab()
        binding.searchEditText.removeTextChangedListener(textWatcher)
    }

    private fun setupToolbar() {
        binding.toolbar.apply {
            setTitle(R.string.bottom_item_2)
            inflateMenu(R.menu.menu_hosts)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.clear -> controller?.onClearAll()
                }
                true
            }
        }
    }

    private fun setupViewPager() {
        binding.viewPager.apply {
            adapter = object : FragmentStateAdapter(this@RequestFragment) {
                override fun getItemCount() = 3
                override fun createFragment(position: Int) = when (position) {
                    0 -> newInstance("all")
                    1 -> newInstance("block")
                    else -> newInstance("pass")
                }
            }
            offscreenPageLimit = 3
            isUserInputEnabled = false
        }

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.setText(
                when (position) {
                    0 -> R.string.tab_domain_list
                    1 -> R.string.tab_host_list
                    else -> R.string.tab_host_whitelist
                }
            )
        }.attach()

        binding.tabLayout.addOnTabSelectedListener(object : OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = resetSearch()
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {
                tabController?.onReturnTop()
                fabViewBehavior.slideUp(binding.fab)
            }
        })
    }

    private fun initFab() {
        with(binding.fab) {
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 25.dp, fabMarginBottom)
                gravity = Gravity.BOTTOM or Gravity.END
                behavior = fabViewBehavior
            }
            visibility = View.VISIBLE
            setOnClickListener { fabController?.onExport() }
        }

        with(binding.block) {
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 25.dp, fabMarginBottom + 81.dp)
                gravity = Gravity.BOTTOM or Gravity.END
                behavior = fabViewBehavior
            }
            visibility = View.VISIBLE
            setOnClickListener { fabController?.onBlock() }
        }
    }

    private fun initEditText() {
        with(binding.searchEditText) {
            onFocusChangeListener = OnFocusChangeListener { _, hasFocus ->
                setIcon(if (hasFocus) R.drawable.ic_back else R.drawable.ic_search, hasFocus)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun afterTextChanged(s: Editable?) {
                    binding.searchClear.visibility =
                        if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    handleSearchTextChange(s.toString())
                }
            })
        }
        binding.searchClear.setOnClickListener { resetSearch() }
    }

    private fun handleSearchTextChange(query: String) {
        searchDisposable?.dispose()
        lastSearchQuery = query

        if (query.isNotEmpty()) {
            searchDisposable = Observable.just(query)
                .debounce(300, TimeUnit.MILLISECONDS)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { actualQuery ->
                    controller?.search(actualQuery)
                }
        } else {
            controller?.search("")
        }
    }

    private fun resetSearch() {
        with(binding.searchEditText) {
            setText("")
            clearFocus()
        }
        binding.searchClear.visibility = View.GONE
        searchDisposable?.dispose()
        controller?.search("")
    }

    private fun setIcon(drawableId: Int, focus: Boolean) {
        with(binding.searchIcon) {
            setImageResource(drawableId)
        }
        if (focus) {
            binding.searchEditText.requestFocus()
            imm?.showSoftInput(binding.searchEditText, 0)
        } else {
            binding.searchEditText.clearFocus()
            imm?.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        searchDisposable?.dispose()
    }

    override fun onDestroyView() {
        binding.searchEditText.removeTextChangedListener(textWatcher)
        super.onDestroyView()
    }

    override fun onBackPressed() = with(binding.searchEditText) {
        if (backController?.onBackPressed() == false) {
            if (isFocused) {
                setText("")
                setIcon(R.drawable.ic_search, false)
                true
            } else false
        } else true
    }

    override fun onStop() {
        super.onStop()
        (requireContext() as? OnBackPressContainer)?.controller = null
    }

    override fun onResume() {
        super.onResume()
        (requireContext() as? OnBackPressContainer)?.controller = this
    }

    private val fabMarginBottom
        get() = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            DensityTool.getNavigationBarHeight(requireContext()) + 105.dp
        } else 25.dp

    override fun showBlock() {
        fabViewBehavior.slideUp(binding.block)
    }

    override fun hideBlock() {
        fabViewBehavior.slideDown(binding.block)
    }
}
