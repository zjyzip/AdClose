package com.close.hook.ads.ui.fragment.request

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.view.ActionMode
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.ItemKeyProvider
import androidx.recyclerview.selection.Selection
import androidx.recyclerview.selection.SelectionPredicates
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.R
import com.close.hook.ads.data.model.RequestInfo
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.databinding.FragmentRequestListBinding
import com.close.hook.ads.ui.activity.MainActivity
import com.close.hook.ads.ui.adapter.RequestListAdapter
import com.close.hook.ads.ui.fragment.base.BaseFragment
import com.close.hook.ads.ui.viewmodel.AppsViewModel
import com.close.hook.ads.ui.viewmodel.BlockListViewModel
import com.close.hook.ads.ui.viewmodel.RequestViewModel
import com.close.hook.ads.util.INavContainer
import com.close.hook.ads.util.IOnFabClickContainer
import com.close.hook.ads.util.IOnFabClickListener
import com.close.hook.ads.util.IOnTabClickContainer
import com.close.hook.ads.util.IOnTabClickListener
import com.close.hook.ads.util.OnBackPressListener
import com.close.hook.ads.util.OnCLearCLickContainer
import com.close.hook.ads.util.OnClearClickListener
import com.close.hook.ads.util.dp
import com.close.hook.ads.util.FooterSpaceItemDecoration
import com.google.android.material.snackbar.Snackbar
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import java.io.File
import java.io.IOException

class RequestListFragment : BaseFragment<FragmentRequestListBinding>(), OnClearClickListener,
    IOnTabClickListener, IOnFabClickListener, OnBackPressListener {

    private val requestViewModel: RequestViewModel by viewModels({ requireActivity() })
    private val blockListViewModel: BlockListViewModel by viewModels({ requireActivity() })

    private val appsViewModel by viewModels<AppsViewModel>({ requireActivity() })
    private lateinit var mAdapter: RequestListAdapter
    private lateinit var footerSpaceDecoration: FooterSpaceItemDecoration
    private lateinit var type: String
    private var tracker: SelectionTracker<RequestInfo>? = null
    private var selectedItems: Selection<RequestInfo>? = null
    private var mActionMode: ActionMode? = null

    private val snackbarLayoutParams: CoordinatorLayout.LayoutParams by lazy {
        CoordinatorLayout.LayoutParams(
            CoordinatorLayout.LayoutParams.MATCH_PARENT,
            CoordinatorLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            setMargins(10.dp, 0, 10.dp, 90.dp)
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

        initView()
        setUpTracker()
        addObserverToTracker()
        initObserve()
    }

    private fun initObserve() {
        viewLifecycleOwner.lifecycleScope.launch {
            requestViewModel.getFilteredRequestList(type).collectLatest { filteredList ->
                mAdapter.submitList(filteredList) {
                    val targetChild = if (filteredList.isEmpty()) 0 else 1
                    if (binding.vfContainer.displayedChild != targetChild) {
                        binding.vfContainer.displayedChild = targetChild
                    }
                }
            }
        }
    }

    private fun initView() {
        mAdapter = RequestListAdapter(requestViewModel.dataSource)
        footerSpaceDecoration = FooterSpaceItemDecoration(footerHeight = 96.dp)

        binding.recyclerView.apply {
            adapter = mAdapter
            layoutManager = LinearLayoutManager(requireContext())
            addItemDecoration(footerSpaceDecoration)

            (activity as? MainActivity)?.getBottomNavigationView()?.post {
                val bottomNavHeight = (activity as? MainActivity)?.getBottomNavigationView()?.height ?: 0
                setPadding(paddingLeft, paddingTop, paddingRight, bottomNavHeight)
                FastScrollerBuilder(this).useMd2Style().build()
            }

            clipToPadding = false

            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                private var totalDy = 0
                private val scrollThreshold = 20.dp

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    val navContainer = activity as? INavContainer

                    if (dy > 0) {
                        totalDy += dy
                        if (totalDy > scrollThreshold) {
                            navContainer?.hideNavigation()
                            totalDy = 0
                        }
                    } else if (dy < 0) {
                        totalDy += dy
                        if (totalDy < -scrollThreshold) {
                            navContainer?.showNavigation()
                            totalDy = 0
                        }
                    }
                }
            })
        }
    }

    private fun addObserverToTracker() {
        tracker?.addObserver(object : SelectionTracker.SelectionObserver<RequestInfo>() {
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
            mode?.menuInflater?.inflate(R.menu.menu_request, menu)
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
            StorageStrategy.createParcelableStorage(RequestInfo::class.java)
        ).withSelectionPredicate(
            SelectionPredicates.createSelectAnything()
        ).build()
        mAdapter.tracker = tracker
    }

    override fun search(keyword: String) {
        requestViewModel.setRequestSearchQuery(keyword)
    }

    override fun onClearAll() {
        requestViewModel.onClearAllRequests()
        (activity as? INavContainer)?.showNavigation()
    }

    override fun onReturnTop() {
        binding.recyclerView.scrollToPosition(0)
        (activity as? INavContainer)?.showNavigation()
    }

    override fun onPause() {
        super.onPause()
        tracker?.clearSelection()
    }

    override fun onResume() {
        super.onResume()
        (parentFragment as? OnCLearCLickContainer)?.controller = this
        (parentFragment as? IOnTabClickContainer)?.tabController = this
        (parentFragment as? IOnFabClickContainer)?.fabController = this
    }

    private fun saveFile(content: String): Boolean {
        return try {
            val dir = File(requireContext().cacheDir, "temp_exports")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(dir, "request_list.json")
            file.writeText(content)
            true
        } catch (e: IOException) {
            false
        }
    }

    override fun onExport() {
        if (requestViewModel.requestList.value.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.export_empty_request_list), Toast.LENGTH_SHORT)
                .show()
            return
        }

        try {
            val content =
                GsonBuilder().setPrettyPrinting().create().toJson(requestViewModel.requestList.value)
            if (saveFile(content)) {
                backupSAFLauncher.launch("${type}_request_list.json")
            } else {
                Toast.makeText(requireContext(), getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
            }
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                requireContext(),
                getString(R.string.export_no_app_found),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onBlock() {
        selectedItems?.let { selection ->
            if (selection.size() != 0) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val updateList = selection.toList().map {
                        val type = if (it.appName.trim().endsWith("DNS")) "Domain" else "URL"
                        Url(type, it.request)
                    }.filterNot {
                        blockListViewModel.dataSource.isExist(it.type, it.url)
                    }
                    blockListViewModel.addListUrl(updateList)

                    withContext(Dispatchers.Main) {
                        tracker?.clearSelection()
                        showSnackbar(getString(R.string.add_to_blocklist_success))
                    }
                }
            }
        }
    }

    private fun showSnackbar(message: String) {
        val snackBar = Snackbar.make(
            requireParentFragment().requireView(),
            message,
            Snackbar.LENGTH_SHORT
        )
        snackBar.view.layoutParams = snackbarLayoutParams
        snackBar.show()
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
            showSnackbar(getString(R.string.copied_to_clipboard_batch))
        }
    }

    private val backupSAFLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) backup@{ uri ->
            if (uri == null) return@backup
            try {
                val cachedFile = File(requireContext().cacheDir, "temp_exports/request_list.json")
                if (!cachedFile.exists()) {
                    Toast.makeText(requireContext(), getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
                    return@backup
                }

                requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                    cachedFile.inputStream().copyTo(outputStream)
                    Toast.makeText(requireContext(), getString(R.string.export_success), Toast.LENGTH_SHORT).show()
                } ?: run {
                    Toast.makeText(requireContext(), getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
                }

            } catch (e: IOException) {
                Toast.makeText(requireContext(), getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
            }
        }

    class CategoryItemDetailsLookup(private val recyclerView: RecyclerView) :
        ItemDetailsLookup<RequestInfo>() {
        override fun getItemDetails(e: MotionEvent): ItemDetails<RequestInfo>? {
            val view = recyclerView.findChildViewUnder(e.x, e.y)
            if (view != null) {
                return (recyclerView.getChildViewHolder(view) as? RequestListAdapter.ViewHolder)?.getItemDetails()
            }
            return null
        }
    }

    class CategoryItemKeyProvider(private val adapter: RequestListAdapter) :
        ItemKeyProvider<RequestInfo>(SCOPE_CACHED) {
        override fun getKey(position: Int): RequestInfo? {
            return adapter.currentList.getOrNull(position)
        }

        override fun getPosition(key: RequestInfo): Int {
            val index = adapter.currentList.indexOfFirst { it == key }
            return if (index >= 0) index else RecyclerView.NO_POSITION
        }
    }

    override fun onBackPressed(): Boolean {
        if (tracker?.hasSelection() == true) {
            tracker?.clearSelection()
            return true
        }
        return false
    }
}
