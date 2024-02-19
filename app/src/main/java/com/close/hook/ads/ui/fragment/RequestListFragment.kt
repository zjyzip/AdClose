package com.close.hook.ads.ui.fragment

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.ItemKeyProvider
import androidx.recyclerview.selection.Selection
import androidx.recyclerview.selection.SelectionPredicates
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.data.database.UrlDatabase
import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.data.model.FilterBean
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.databinding.FragmentHostsListBinding
import com.close.hook.ads.ui.adapter.BlockedRequestsAdapter
import com.close.hook.ads.ui.viewmodel.AppsViewModel
import com.close.hook.ads.util.IFabContainer
import com.close.hook.ads.util.INavContainer
import com.close.hook.ads.util.IOnFabClickContainer
import com.close.hook.ads.util.IOnFabClickListener
import com.close.hook.ads.util.IOnTabClickContainer
import com.close.hook.ads.util.IOnTabClickListener
import com.close.hook.ads.util.OnBackPressFragmentContainer
import com.close.hook.ads.util.OnBackPressFragmentListener
import com.close.hook.ads.util.OnCLearCLickContainer
import com.close.hook.ads.util.OnClearClickListener
import com.google.gson.Gson
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Optional

class RequestListFragment : BaseFragment<FragmentHostsListBinding>(), OnClearClickListener,
    IOnTabClickListener, IOnFabClickListener, OnBackPressFragmentListener {

    private val viewModel by lazy { ViewModelProvider(this)[AppsViewModel::class.java] }
    private lateinit var adapter: BlockedRequestsAdapter
    private lateinit var type: String
    private lateinit var filter: IntentFilter
    private val disposables = CompositeDisposable()
    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val request = intent.getParcelableExtra<BlockedRequest>("request")
            request?.let { item ->
                val checkItem = viewModel.requestList.find {
                    it.request == item.request
                }
                if (checkItem == null) {
                    viewModel.requestList.add(0, item)
                    adapter.submitList(viewModel.requestList.toList())
                }
            }
        }
    }
    private var tracker: SelectionTracker<String>? = null
    private var selectedItems: Selection<String>? = null
    private val urlDao by lazy {
        UrlDatabase.getDatabase(requireContext()).urlDao
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
        setUpTracker()
        addObserverToTracker()
    }

    private fun addObserverToTracker() {
        tracker?.addObserver(
            object : SelectionTracker.SelectionObserver<String>() {
                override fun onSelectionChanged() {
                    super.onSelectionChanged()
                    selectedItems = tracker?.selection
                    val items = tracker?.selection?.size()
                    if (items != null && items > 0) {
                        (requireParentFragment() as? IFabContainer)?.showBlock()
                    } else {
                        (requireParentFragment() as? IFabContainer)?.hideBlock()
                    }
                }
            }
        )
    }

    private fun setUpTracker() {
        tracker = SelectionTracker.Builder(
            "selection_id",
            binding.recyclerView,
            CategoryItemKeyProvider(adapter),
            CategoryItemDetailsLookup(binding.recyclerView),
            StorageStrategy.createStringStorage()
        ).withSelectionPredicate(
            SelectionPredicates.createSelectAnything()
        ).build()
        adapter.tracker = tracker
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
        (requireParentFragment() as? IOnFabClickContainer)?.fabController = null
        (requireParentFragment() as? OnBackPressFragmentContainer)?.backController = null
    }

    override fun onResume() {
        super.onResume()
        (requireParentFragment() as? OnCLearCLickContainer)?.controller = this
        (requireParentFragment() as? IOnTabClickContainer)?.tabController = this
        (requireParentFragment() as? IOnFabClickContainer)?.fabController = this
        (requireParentFragment() as? OnBackPressFragmentContainer)?.backController = this
    }

    private fun saveFile(content: String): Boolean {
        return try {
            val dir = File(requireContext().cacheDir.toString())
            if (!dir.exists())
                dir.mkdir()
            val file = File("${requireContext().cacheDir}/request_list.json")
            if (!file.exists())
                file.createNewFile()
            else {
                file.delete()
                file.createNewFile()
            }
            val fileOutputStream = FileOutputStream(file)
            fileOutputStream.write(content.toByteArray())
            fileOutputStream.flush()
            fileOutputStream.close()
            true
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }

    override fun onExport() {
        if (viewModel.requestList.isEmpty())
            return
        if (saveFile(Gson().toJson(viewModel.requestList))) {
            try {
                backupSAFLauncher.launch("${type}_request_list.json")
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(
                    requireContext(),
                    "无法导出文件，未找到合适的应用来创建文件",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            Toast.makeText(requireContext(), "导出失败", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBlock() {
        selectedItems?.let {
            if (it.size() != 0) {
                CoroutineScope(Dispatchers.IO).launch {
                    it.forEach { url ->
                        if (!url.isNullOrEmpty() && url != "null") {
                            if (!urlDao.isExist(url))
                                urlDao.insert(Url("URL", url))
                        }
                    }
                    withContext(Dispatchers.Main) {
                        tracker?.clearSelection()
                    }
                }
            }
        }
    }

    private val backupSAFLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) backup@{ uri ->
            if (uri == null) return@backup
            try {
                File("${requireContext().cacheDir}/request_list.json").inputStream().use { input ->
                    requireContext().contentResolver.openOutputStream(uri).use { output ->
                        if (output == null)
                            Toast.makeText(requireContext(), "导出失败", Toast.LENGTH_SHORT).show()
                        else input.copyTo(output)
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

    class CategoryItemDetailsLookup(private val recyclerView: RecyclerView) :
        ItemDetailsLookup<String>() {
        override fun getItemDetails(e: MotionEvent): ItemDetails<String>? {
            val view = recyclerView.findChildViewUnder(e.x, e.y)
            if (view != null) {
                return (recyclerView.getChildViewHolder(view) as BlockedRequestsAdapter.ViewHolder).getItemDetails()
            }
            return null
        }
    }

    class CategoryItemKeyProvider(private val adapter: BlockedRequestsAdapter) :
        ItemKeyProvider<String>(SCOPE_CACHED) {
        override fun getKey(position: Int): String? = adapter.currentList[position].request

        override fun getPosition(key: String): Int =
            adapter.currentList.indexOfFirst { it.request == key }
    }

    override fun onBackPressed(): Boolean {
        selectedItems?.let {
            if (it.size() > 0) {
                tracker?.clearSelection()
                return true
            }
        }
        return false
    }

}
