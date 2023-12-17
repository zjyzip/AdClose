package com.close.hook.ads.ui.fragment

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.close.hook.ads.data.module.BlockedRequest
import com.close.hook.ads.databinding.FragmentHostsListBinding
import com.close.hook.ads.ui.adapter.BlockedRequestsAdapter
import com.close.hook.ads.util.OnCLearCLickContainer
import com.close.hook.ads.util.OnClearClickListener
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import java.util.Locale
import java.util.Optional


class HostsListFragment : Fragment(), OnClearClickListener {

    private lateinit var binding: FragmentHostsListBinding
    private val requestList: ArrayList<BlockedRequest> = ArrayList()
    private lateinit var adapter: BlockedRequestsAdapter
    private lateinit var type: String
    private lateinit var filter: IntentFilter
    private val disposables = CompositeDisposable()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            type = it.getString("type")!!
        }
    }

    companion object {
        @JvmStatic
        fun newInstance(type: String) =
            HostsListFragment().apply {
                arguments = Bundle().apply {
                    putString("type", type)
                }
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentHostsListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = BlockedRequestsAdapter(requireContext())
        binding.recyclerView.adapter = adapter

        // 注册广播接收器
        filter = when (type) {
            "all" -> IntentFilter("com.rikkati.ALL_REQUEST")
            "block" -> IntentFilter("com.rikkati.BLOCKED_REQUEST")
            "pass" -> IntentFilter("com.rikkati.PASS_REQUEST")
            else -> throw IllegalArgumentException("type error: $type")
        }
        requireContext().registerReceiver(receiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        requireContext().unregisterReceiver(receiver)
    }

    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val request = intent.getSerializableExtra("request") as BlockedRequest?
            requestList.add(0, request!!)
            val newList = ArrayList<BlockedRequest>()
            newList.addAll(requestList)
            adapter.submitList(newList)
            //adapter.notifyItemInserted(0)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onClearAll() {
        requestList.clear()
        val newList = ArrayList<BlockedRequest>()
        newList.addAll(requestList)
        adapter.submitList(newList)
        //adapter.notifyDataSetChanged()
    }

    override fun search(keyWord: String?) {
        val safeAppInfoList: List<BlockedRequest> =
            Optional.ofNullable<List<BlockedRequest>>(requestList).orElseGet { emptyList() }
        disposables.add(Observable.fromIterable(safeAppInfoList)
            .filter { blockRequest: BlockedRequest ->
                (blockRequest.request.contains(keyWord.toString()))
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


    override fun onResume() {
        super.onResume()

        (requireParentFragment() as OnCLearCLickContainer).controller = this

    }

}