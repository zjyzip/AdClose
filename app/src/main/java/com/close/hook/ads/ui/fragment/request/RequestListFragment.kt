package com.close.hook.ads.ui.fragment.request

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.view.ActionMode
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.ItemKeyProvider
import androidx.recyclerview.selection.Selection
import androidx.recyclerview.selection.SelectionPredicates
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.R
import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.databinding.FragmentHostsListBinding
import com.close.hook.ads.ui.activity.MainActivity
import com.close.hook.ads.ui.adapter.BlockedRequestsAdapter
import com.close.hook.ads.ui.adapter.FooterAdapter
import com.close.hook.ads.ui.fragment.base.BaseFragment
import com.close.hook.ads.ui.viewmodel.BlockListViewModel
import com.close.hook.ads.ui.viewmodel.UrlViewModelFactory
import com.close.hook.ads.util.INavContainer
import com.close.hook.ads.util.IOnFabClickContainer
import com.close.hook.ads.util.IOnFabClickListener
import com.close.hook.ads.util.IOnTabClickContainer
import com.close.hook.ads.util.IOnTabClickListener
import com.close.hook.ads.util.OnBackPressContainer
import com.close.hook.ads.util.OnBackPressListener
import com.close.hook.ads.util.OnCLearCLickContainer
import com.close.hook.ads.util.OnClearClickListener
import com.close.hook.ads.util.dp
import com.close.hook.ads.util.setSpaceFooterView
import com.google.android.material.snackbar.Snackbar
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException


class RequestListFragment : BaseFragment<FragmentHostsListBinding>(), OnClearClickListener,
    IOnTabClickListener, IOnFabClickListener, OnBackPressListener {

    private val viewModel by lazy {
        ViewModelProvider(
            owner = requireParentFragment(),
            factory = UrlViewModelFactory(requireContext())
        )[BlockListViewModel::class.java]
    }
    private lateinit var mAdapter: BlockedRequestsAdapter
    private val footerAdapter = FooterAdapter()
    private lateinit var type: String
    private var tracker: SelectionTracker<BlockedRequest>? = null
    private var selectedItems: Selection<BlockedRequest>? = null
    private var mActionMode: ActionMode? = null

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

        initView()
        setUpTracker()
        addObserverToTracker()
        initObserve()

    }

    private fun initObserve() {
        viewModel.requestList.observe(viewLifecycleOwner) {
            it?.let {
                when (type) {
                    "all" -> mAdapter.submitList(it)

                    "block" -> mAdapter.submitList(it.filter { it.isBlocked == true })

                    "pass" -> mAdapter.submitList(it.filter { it.isBlocked == false })
                }
                if (binding.vfContainer.displayedChild != it.size)
                    binding.vfContainer.displayedChild = it.size
            }
        }
    }

    private fun initView() {
        mAdapter = BlockedRequestsAdapter(viewModel.dataSource)
        binding.recyclerView.apply {
            adapter = ConcatAdapter(mAdapter)
            layoutManager = LinearLayoutManager(requireContext())
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    val navContainer = activity as? INavContainer
                    if (dy > 0) {
                        navContainer?.hideNavigation()
                    } else if (dy < 0) {
                        navContainer?.showNavigation()
                    }
                }
            })
            FastScrollerBuilder(this).useMd2Style().build()
        }

        binding.vfContainer.setOnDisplayedChildChangedListener {
            binding.recyclerView.setSpaceFooterView(footerAdapter)
        }
    }

    private fun addObserverToTracker() {
        tracker?.addObserver(object : SelectionTracker.SelectionObserver<BlockedRequest>() {
            override fun onSelectionChanged() {
                super.onSelectionChanged()
                selectedItems = tracker?.selection
                val size = tracker?.selection?.size() ?: 0

                if (size > 0) {
                    if (mActionMode == null) {
                        mActionMode =
                            (activity as? MainActivity)?.startSupportActionMode(mActionModeCallback)
                    }
                    mActionMode?.title = "Selected $size"
                } else {
                    mActionMode?.finish()
                    mActionMode = null
                }
            }
        })
    }

    private val mActionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            mode?.menuInflater?.inflate(R.menu.menu_requset, menu)
            mode?.title = "Choose option"
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            when (item?.itemId) {
                R.id.action_copy -> {
                    onCopy()
                    return true
                }

                R.id.action_block -> {
                    onBlock()
                    return true
                }
            }
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            mActionMode = null
            tracker?.clearSelection()
        }
    }

    private fun setUpTracker() {
        tracker = SelectionTracker.Builder(
            "selection_id",
            binding.recyclerView,
            CategoryItemKeyProvider(mAdapter),
            CategoryItemDetailsLookup(binding.recyclerView),
            StorageStrategy.createParcelableStorage(BlockedRequest::class.java)
        ).withSelectionPredicate(
            SelectionPredicates.createSelectAnything()
        ).build()
        mAdapter.tracker = tracker
    }

    override fun search(keyword: String) {
        viewModel.searchQuery.value = keyword

        CoroutineScope(Dispatchers.Main).launch {
            viewModel.searchQuery
                .debounce(300)
                .distinctUntilChanged()
                .flatMapLatest { query ->
                    flow {
                        val safeAppInfoList = viewModel.requestList.value?.ifEmpty { emptyList() }
                        emit(safeAppInfoList?.filter { blockedRequest ->
                            blockedRequest.request.contains(query, ignoreCase = true) ||
                                    blockedRequest.packageName.contains(query, ignoreCase = true) ||
                                    blockedRequest.appName.contains(query, ignoreCase = true)
                        })
                    }
                }
                .catch { throwable ->
                    Log.e("AppsFragment", "Error in searchKeyword", throwable)
                }
                .collect { filteredList ->
                    mAdapter.submitList(filteredList)
                }
        }
    }

    override fun onClearAll() {
        viewModel.onClearAll()
        (activity as? INavContainer)?.showNavigation()
    }

    override fun onReturnTop() {
        binding.recyclerView.scrollToPosition(0)
        (activity as? INavContainer)?.showNavigation()
    }

    override fun onPause() {
        super.onPause()
        (requireParentFragment() as? OnCLearCLickContainer)?.controller = null
        (requireParentFragment() as? IOnTabClickContainer)?.tabController = null
        (requireParentFragment() as? IOnFabClickContainer)?.fabController = null
        (requireParentFragment() as? OnBackPressContainer)?.backController = null
        tracker?.clearSelection()
    }

    override fun onResume() {
        super.onResume()
        (requireParentFragment() as? OnCLearCLickContainer)?.controller = this
        (requireParentFragment() as? IOnTabClickContainer)?.tabController = this
        (requireParentFragment() as? IOnFabClickContainer)?.fabController = this
        (requireParentFragment() as? OnBackPressContainer)?.backController = this
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
        if (viewModel.requestList.value.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "请求列表为空，无法导出", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val content = GsonBuilder().setPrettyPrinting().create().toJson(viewModel.requestList.value)
            if (saveFile(content)) {
                backupSAFLauncher.launch("${type}_request_list.json")
            } else {
                Toast.makeText(requireContext(), "导出失败", Toast.LENGTH_SHORT).show()
            }
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                requireContext(),
                "无法导出文件，未找到合适的应用来创建文件",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onBlock() {
        selectedItems?.let { selection ->
            if (selection.size() != 0) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val updateList = selection.toList().map {
                        Url(
                            if (it.appName.trim().endsWith("DNS")) "Domain" else "URL",
                            it.request
                        )
                    }.filterNot {
                        viewModel.dataSource.isExist(it.type, it.url)
                    }
                    viewModel.addListUrl(updateList)

                    withContext(Dispatchers.Main) {
                        tracker?.clearSelection()
                        val snackBar = Snackbar.make(
                            requireParentFragment().requireView(),
                            "已批量加入黑名单",
                            Snackbar.LENGTH_SHORT
                        )
                        val lp = CoordinatorLayout.LayoutParams(
                            CoordinatorLayout.LayoutParams.MATCH_PARENT,
                            CoordinatorLayout.LayoutParams.WRAP_CONTENT
                        )
                        lp.gravity = Gravity.BOTTOM
                        lp.setMargins(10.dp, 0, 10.dp, 90.dp)
                        snackBar.view.layoutParams = lp
                        snackBar.show()
                    }
                }
            }
        }
    }

    private fun onCopy() {
        selectedItems?.let { selection ->
            val selectedRequests = selection.map { item ->
                val type =
                    if (item.request.startsWith("http://") || item.request.startsWith("https://")) "URL" else item.blockType
                "$type, ${item.request}"
            }
            val combinedText = selectedRequests.joinToString(separator = "\n")
            val clipboard =
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("copied_requests", combinedText)
            clipboard.setPrimaryClip(clip)
            tracker?.clearSelection()
            val snackBar = Snackbar.make(
                requireParentFragment().requireView(),
                "已批量复制到剪贴板",
                Snackbar.LENGTH_SHORT
            )
            val lp = CoordinatorLayout.LayoutParams(
                CoordinatorLayout.LayoutParams.MATCH_PARENT,
                CoordinatorLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.BOTTOM
            lp.setMargins(10.dp, 0, 10.dp, 90.dp)
            snackBar.view.layoutParams = lp
            snackBar.show()
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
        ItemDetailsLookup<BlockedRequest>() {
        override fun getItemDetails(e: MotionEvent): ItemDetails<BlockedRequest>? {
            val view = recyclerView.findChildViewUnder(e.x, e.y)
            if (view != null) {
                return (recyclerView.getChildViewHolder(view) as? BlockedRequestsAdapter.ViewHolder)?.getItemDetails()
            }
            return null
        }
    }

    class CategoryItemKeyProvider(private val adapter: BlockedRequestsAdapter) :
        ItemKeyProvider<BlockedRequest>(SCOPE_CACHED) {
        override fun getKey(position: Int): BlockedRequest? {
            return adapter.currentList.getOrNull(position)
        }

        override fun getPosition(key: BlockedRequest): Int {
            val index = adapter.currentList.indexOfFirst { it == key }
            return if (index >= 0) index else RecyclerView.NO_POSITION
        }
    }

    override fun onBackPressed(): Boolean {
        selectedItems?.let {
            if (it.size() > 0) {
                tracker?.clearSelection()
                return true
            }
        }
        return binding.recyclerView.closeMenus()
    }

}
