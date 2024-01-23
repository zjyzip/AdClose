package com.close.hook.ads.ui.fragment

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.view.View.OnFocusChangeListener
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.close.hook.ads.R
import com.close.hook.ads.databinding.FragmentHostsBinding
import com.close.hook.ads.ui.fragment.RequestListFragment.Companion.newInstance
import com.close.hook.ads.util.IOnTabClickContainer
import com.close.hook.ads.util.IOnTabClickListener
import com.close.hook.ads.util.OnBackPressContainer
import com.close.hook.ads.util.OnBackPressListener
import com.close.hook.ads.util.OnCLearCLickContainer
import com.close.hook.ads.util.OnClearClickListener
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayout.OnTabSelectedListener
import com.google.android.material.tabs.TabLayoutMediator
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.TimeUnit


class RequestFragment : BaseFragment<FragmentHostsBinding>(), OnCLearCLickContainer,
    OnBackPressListener,
    IOnTabClickContainer {

    private var imm: InputMethodManager? = null
    private var lastSearchQuery = ""
    override var controller: OnClearClickListener? = null
    override var tabController: IOnTabClickListener? = null
    private var searchDisposable: Disposable? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.apply {
            setTitle(R.string.bottom_item_2)
            inflateMenu(R.menu.menu_hosts)
            setOnMenuItemClickListener { item: MenuItem ->
                if (item.itemId == R.id.clear) {
                    controller!!.onClearAll()
                }
                true
            }
        }
        val adapter: FragmentStateAdapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int {
                return 3
            }

            override fun createFragment(position: Int): Fragment {
                return when (position) {
                    0 -> newInstance("all")
                    1 -> newInstance("block")
                    else -> newInstance("pass")
                }
            }
        }
        binding.viewPager.adapter = adapter
        binding.viewPager.offscreenPageLimit = 3
        TabLayoutMediator(
            binding.tabLayout,
            binding.viewPager
        ) { tab: TabLayout.Tab, position: Int ->
            when (position) {
                0 -> tab.setText(R.string.tab_domain_list)
                1 -> tab.setText(R.string.tab_host_list)
                2 -> tab.setText(R.string.tab_host_whitelist)
            }
        }.attach()
        binding.tabLayout.addOnTabSelectedListener(object : OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                binding.searchEditText.setText("")
                binding.searchEditText.clearFocus()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {
                tabController!!.onReturnTop()
            }
        })
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                binding.searchEditText.setText("")
                binding.searchEditText.clearFocus()
            }
        })
        initEditText()
    }

    private fun initEditText() {
        binding.searchEditText.onFocusChangeListener =
            OnFocusChangeListener { _: View?, hasFocus: Boolean ->
                setIcon(
                    if (hasFocus) R.drawable.ic_back else R.drawable.ic_search,
                    hasFocus
                )
            }
        binding.searchClear.setOnClickListener { resetSearch() }
        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (searchDisposable != null && !searchDisposable!!.isDisposed) {
                    searchDisposable!!.dispose()
                }
                lastSearchQuery = s.toString()
                if (s.isNotEmpty()) {
                    searchDisposable = Observable.just(s.toString())
                        .debounce(300, TimeUnit.MILLISECONDS)
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe { query: String -> performSearch(query, lastSearchQuery) }
                }
            }

            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable) {
                binding.searchClear.visibility =
                    if (s.toString().isEmpty()) View.GONE else View.VISIBLE
            }
        })
    }

    private fun performSearch(query: String, lastQuery: String) {
        if (controller != null && query == lastQuery) {
            controller!!.search(query)
        }
    }

    private fun resetSearch() {
        binding.searchEditText.setText("")
        binding.searchClear.visibility = View.GONE
        if (searchDisposable != null && !searchDisposable!!.isDisposed) {
            searchDisposable!!.dispose()
        }
    }

    private fun setIcon(drawableId: Int, focus: Boolean) {
        imm = requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        binding.searchIcon.setImageDrawable(requireContext().getDrawable(drawableId))
        if (focus) {
            binding.searchEditText.requestFocus()
            imm!!.showSoftInput(binding.searchEditText, 0)
        } else {
            binding.searchEditText.clearFocus()
            imm!!.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (searchDisposable != null && !searchDisposable!!.isDisposed) {
            searchDisposable!!.dispose()
        }
    }

    override fun onBackPressed(): Boolean {
        if (binding.searchEditText.isFocused) {
            binding.searchEditText.setText("")
            setIcon(R.drawable.ic_search, false)
            return true
        }
        return false
    }

    override fun onStop() {
        super.onStop()
        (requireContext() as OnBackPressContainer).controller = null
    }

    override fun onResume() {
        super.onResume()
        (requireContext() as OnBackPressContainer).controller = this
    }
}
