package com.close.hook.ads.ui.fragment

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.data.model.FilterBean
import com.close.hook.ads.databinding.FragmentHostsListBinding
import com.close.hook.ads.ui.adapter.BlockedRequestsAdapter
import com.close.hook.ads.ui.viewmodel.AppsViewModel
import com.close.hook.ads.util.INavContainer
import com.close.hook.ads.util.IOnTabClickContainer
import com.close.hook.ads.util.IOnTabClickListener
import com.close.hook.ads.util.OnBackPressContainer
import com.close.hook.ads.util.OnCLearCLickContainer
import com.close.hook.ads.util.OnClearClickListener
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import java.util.Optional
import java.util.concurrent.TimeUnit

class RequestListFragment : BaseFragment<FragmentHostsListBinding>(), OnClearClickListener, IOnTabClickListener {

    private val viewModel by lazy { ViewModelProvider(this)[AppsViewModel::class.java] }
    private lateinit var adapter: BlockedRequestsAdapter
    private lateinit var type: String
    private lateinit var filter: IntentFilter
    private val disposables = CompositeDisposable()
    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val request = intent.getParcelableExtra<BlockedRequest>("request")
            request?.let {
                viewModel.requestList.add(0, it)
                adapter.submitList(viewModel.requestList.toList())
            }
        }
    }

    companion object {
        @JvmStatic
        fun newInstance(type: String) =
            RequestListFragment().apply {
                arguments = Bundle().apply {
                    putString("type", type)
                }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        type = arguments?.getString("type") ?: throw IllegalArgumentException("type is required")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        FastScrollerBuilder(binding.recyclerView).useMd2Style().build()

        adapter = BlockedRequestsAdapter(requireContext())
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@RequestListFragment.adapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    val navContainer = activity as? INavContainer
                    if (dy > 0) navContainer?.hideNavigation() else if (dy < 0) navContainer?.showNavigation()
                }
            })
        }

        setupBroadcastReceiver()
    }


    override fun updateSortList(filterBean: FilterBean, keyWord: String, isReverse: Boolean) {}


    private fun setupBroadcastReceiver() {
        filter = when (type) {
            "all" -> IntentFilter("com.rikkati.ALL_REQUEST")
            "block" -> IntentFilter("com.rikkati.BLOCKED_REQUEST")
            "pass" -> IntentFilter("com.rikkati.PASS_REQUEST")
            else -> throw IllegalArgumentException("Invalid type: $type")
        }

        requireContext().registerReceiver(receiver, filter, getReceiverOptions())
    }

    private fun getReceiverOptions(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_EXPORTED else 0
    }

    override fun search(keyWord: String?) {
        val safeAppInfoList: List<BlockedRequest> =
            Optional.ofNullable<List<BlockedRequest>>(viewModel.requestList)
                .orElseGet { emptyList() }
        disposables.add(Observable.fromIterable(safeAppInfoList)
            .filter { blockRequest: BlockedRequest ->
                (blockRequest.request.contains(keyWord.toString())
                        || blockRequest.packageName.contains(keyWord.toString())
                        || blockRequest.appName.contains(keyWord.toString()))
            }
            .toList().observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { filteredList: List<BlockedRequest?>? ->
                    adapter.submitList(
                        filteredList
                    )
                },
                { throwable: Throwable? ->
                    Log.e(
                        "AppsFragment",
                        "Error in searchKeyWorld",
                        throwable
                    )
                })
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        requireContext().unregisterReceiver(receiver)
        disposables.dispose()
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onClearAll() {
        viewModel.requestList.clear()
        adapter.submitList(emptyList())
        (activity as? INavContainer)?.showNavigation()
    }

    override fun onReturnTop() {
        binding.recyclerView.scrollToPosition(0)
        (activity as? INavContainer)?.showNavigation()
    }

    override fun onStop() {
        super.onStop()
        (requireParentFragment() as? OnCLearCLickContainer)?.controller = null
        (requireParentFragment() as? IOnTabClickContainer)?.tabController = null
    }

    override fun onResume() {
        super.onResume()
        (requireParentFragment() as? OnCLearCLickContainer)?.controller = this
        (requireParentFragment() as? IOnTabClickContainer)?.tabController = this
    }
}
