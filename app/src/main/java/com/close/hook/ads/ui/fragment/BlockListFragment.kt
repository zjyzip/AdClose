package com.close.hook.ads.ui.fragment

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.view.ActionMode
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.viewModels
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.ItemKeyProvider
import androidx.recyclerview.selection.Selection
import androidx.recyclerview.selection.SelectionPredicates
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.R
import com.close.hook.ads.data.database.UrlDatabase
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.databinding.FragmentBlockListBinding
import com.close.hook.ads.ui.activity.MainActivity
import com.close.hook.ads.ui.adapter.BlockListAdapter
import com.close.hook.ads.ui.viewmodel.BlockListViewModel
import com.close.hook.ads.ui.viewmodel.UrlViewModelFactory
import com.close.hook.ads.util.DensityTool
import com.close.hook.ads.util.INavContainer
import com.close.hook.ads.util.OnBackPressContainer
import com.close.hook.ads.util.OnBackPressListener
import com.close.hook.ads.util.dp
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.fastscroll.FastScrollerBuilder

class BlockListFragment : BaseFragment<FragmentBlockListBinding>(), OnBackPressListener {

    private val viewModel by viewModels<BlockListViewModel> {
        UrlViewModelFactory(requireContext())
    }
    private lateinit var mAdapter: BlockListAdapter
    private lateinit var mLayoutManager: RecyclerView.LayoutManager
    private var searchJob: Job? = null
    private val urlDao by lazy {
        UrlDatabase.getDatabase(requireContext()).urlDao
    }
    private var tracker: SelectionTracker<String>? = null
    private var selectedItems: Selection<String>? = null
    private var mActionMode: ActionMode? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initEditText()
        initButton()
        initFab()

        setUpTracker()
        addObserverToTracker()

        viewModel.blackListLiveData.observe(viewLifecycleOwner) {
            mAdapter.submitList(it)
            binding.progressBar.visibility = View.GONE
        }
    }

    private val fabMarginBottom
        get() = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            DensityTool.getNavigationBarHeight(requireContext()) + 105.dp
        } else 25.dp

    private fun initFab() {
        with(binding.delete) {
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 25.dp, fabMarginBottom)
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                behavior = HideBottomViewOnScrollBehavior<FloatingActionButton>()
            }
            visibility = View.VISIBLE
            setOnClickListener { clearHistory() }
        }

        with(binding.add) {
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 25.dp, fabMarginBottom + 81.dp)
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                behavior = HideBottomViewOnScrollBehavior<FloatingActionButton>()
            }
            visibility = View.VISIBLE
            setOnClickListener { addRule() }
        }

    }

    private fun addObserverToTracker() {
        tracker?.addObserver(
            object : SelectionTracker.SelectionObserver<String>() {
                override fun onSelectionChanged() {
                    super.onSelectionChanged()
                    selectedItems = tracker?.selection
                    val items = tracker?.selection?.size()
                    if (items != null) {
                        if (items > 0) {
                            mActionMode?.title = "Selected $items"
                            if (mActionMode != null) {
                                return
                            }
                            mActionMode =
                                (activity as MainActivity).startSupportActionMode(
                                    mActionModeCallback
                                )
                        } else {
                            if (mActionMode != null) {
                                mActionMode?.finish()
                            }
                            mActionMode = null
                        }
                    }
                }
            }
        )
    }

    private val mActionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            mode?.menuInflater?.inflate(R.menu.menu_hosts, menu)
            mode?.title = "Choose option"
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            return false
        }

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            when (item?.itemId) {
                R.id.clear -> deleteSelectedItem()

            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            mActionMode = null
            tracker?.clearSelection()
        }
    }

    private fun deleteSelectedItem() {
        selectedItems?.let {
            if (it.size() != 0) {
                viewModel.removeList(it.toList())
                tracker?.clearSelection()
            }
        }
    }

    private fun setUpTracker() {
        tracker = SelectionTracker.Builder(
            "selection_id",
            binding.recyclerView,
            CategoryItemKeyProvider(mAdapter),
            CategoryItemDetailsLookup(binding.recyclerView),
            StorageStrategy.createStringStorage()
        ).withSelectionPredicate(
            SelectionPredicates.createSelectAnything()
        ).build()
        mAdapter.tracker = tracker
    }

    private fun initView() {
        mLayoutManager = LinearLayoutManager(requireContext())
        mAdapter = BlockListAdapter(requireContext(),
            onRemoveUrl = { position ->
                onRemoveUrl(position)
            },
            onEditUrl = { position ->
                onEditUrl(position)
            }
        )
        FastScrollerBuilder(binding.recyclerView).useMd2Style().build()
        binding.recyclerView.apply {
            adapter = mAdapter
            layoutManager = mLayoutManager
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    val navContainer = activity as? INavContainer
                    if (dy > 0) navContainer?.hideNavigation() else if (dy < 0) navContainer?.showNavigation()
                }
            })
        }
    }

    private fun onRemoveUrl(position: Int) {
        viewModel.removeUrl(viewModel.blackListLiveData.value!![position])
    }

    private fun onEditUrl(position: Int) {
        val item = viewModel.blackListLiveData.value!![position]
        val dialogView =
            LayoutInflater.from(requireContext()).inflate(R.layout.item_block_list_add, null)
        val editText: TextInputEditText = dialogView.findViewById(R.id.editText)
        editText.setText(item.url)
        val type: MaterialAutoCompleteTextView = dialogView.findViewById(R.id.type)
        type.setText(item.type)
        type.setAdapter(
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Domain", "URL", "KeyWord")
            )
        )
        MaterialAlertDialogBuilder(requireContext()).setTitle("Edit Rule")
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newType = type.text.toString()
                val newUrl = editText.text.toString()
                viewModel.editUrl(Pair(item, Url(newType, newUrl)))
            }
            .create().apply {
                window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
                editText.requestFocus()
            }.show()
    }

    @SuppressLint("InflateParams", "SetTextI18n")
    private fun initButton() {
        binding.export.setOnClickListener {
            backupSAFLauncher.launch("block_list.rule")
        }
        binding.restore.setOnClickListener {
            restoreSAFLauncher.launch(arrayOf("application/octet-stream"))
        }
        binding.clear.setOnClickListener {
            binding.editText.text = null
        }
    }

    private fun addRule() {
        val dialogView =
            LayoutInflater.from(requireContext())
                .inflate(R.layout.item_block_list_add, null, false)
        val editText: TextInputEditText = dialogView.findViewById(R.id.editText)
        val type: MaterialAutoCompleteTextView = dialogView.findViewById(R.id.type)
        type.setText("URL")
        type.setAdapter(
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Domain", "URL", "KeyWord")
            )
        )
        MaterialAlertDialogBuilder(requireContext()).setTitle("Add Rule")
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newType = type.text.toString()
                val newUrl = editText.text.toString().trim()

                if (newUrl.isEmpty()) {
                    Toast.makeText(requireContext(), "Value不能为空", Toast.LENGTH_SHORT)
                        .show()
                    return@setPositiveButton
                }
                val currentList = viewModel.blackListLiveData.value ?: emptyList()
                val isExist = currentList.indexOf(Url(newType, newUrl)) != -1
                if (!isExist) {
                    viewModel.addUrl(Url(newType, newUrl))
                } else {
                    Toast.makeText(requireContext(), "规则已存在", Toast.LENGTH_SHORT).show()
                }
            }
            .create().apply {
                window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
                editText.requestFocus()
            }.show()
    }

    private fun clearHistory() {
        MaterialAlertDialogBuilder(requireContext()).setTitle("确定清除全部黑名单？")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.removeAll()
            }
            .show()
    }

    private fun search() {
        val searchText = binding.editText.text.toString()
        searchJob?.cancel()
        searchJob = CoroutineScope(Dispatchers.Main).launch {
            val filteredList = withContext(Dispatchers.IO) {
                urlDao.searchUrls("%$searchText%")
            }
            mAdapter.submitList(filteredList)
        }
    }

    private fun initEditText() {
        binding.editText.apply {
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                    binding.clear.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
                    searchJob?.cancel()
                    searchJob = CoroutineScope(Dispatchers.Main).launch {
                        delay(300)
                        search()
                    }
                }

                override fun afterTextChanged(s: Editable) {}
            })

            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    searchJob?.cancel()
                    search()
                    (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                        ?.hideSoftInputFromWindow(windowToken, 0)
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun prepareDataForExport(): List<String> {
        return viewModel.blackListLiveData.value
            ?.map { "${it.type}, ${it.url}" } // 转换为 "Type, Url" 格式
            ?.distinct() // 去重
            ?.filter { it.contains(",") } // 确保每项至少包含一个逗号
            ?.sorted() ?: emptyList() // 字母排序
    }

    private val restoreSAFLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { uri ->
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        val existingUrls = urlDao.getAllUrls().toSet() // 获取所有现有的URLs并转换为集合
                        val inputStream = requireContext().contentResolver.openInputStream(uri)
                        val newItems = inputStream?.bufferedReader()?.useLines { lines ->
                            lines.mapNotNull { line ->
                                val parts = line.split(",\\s*".toRegex()).map { it.trim() }
                                if (parts.size == 2 && parts[1] !in existingUrls) Url(
                                    parts[0],
                                    parts[1]
                                ) else null
                            }.toList()
                        } ?: listOf()

                        if (newItems.isNotEmpty()) {
                            urlDao.insertAll(newItems)
                        }

                        val newBlockItems = newItems.map { Url(it.type, it.url) }
                        viewModel.addListUrl(newBlockItems)

                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "导入成功", Toast.LENGTH_SHORT)
                                .show()
                        }
                    }.onFailure {
                        withContext(Dispatchers.Main) {
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle("导入失败")
                                .setMessage(it.message)
                                .setPositiveButton(android.R.string.ok, null)
                                .setNegativeButton("Crash Log") { _, _ ->
                                    MaterialAlertDialogBuilder(requireContext())
                                        .setTitle("Crash Log")
                                        .setMessage(it.stackTraceToString())
                                        .setPositiveButton(android.R.string.ok, null)
                                        .show()
                                }
                                .show()
                        }
                    }
                }
            }
        }

    private val backupSAFLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { uri: Uri? ->
            uri?.let {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        requireContext().contentResolver.openOutputStream(uri)?.bufferedWriter()
                            .use { writer ->
                                prepareDataForExport().forEach { line ->
                                    writer?.write("$line\n")
                                }
                            }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "导出成功", Toast.LENGTH_SHORT)
                                .show()
                        }
                    }.onFailure {
                        withContext(Dispatchers.Main) {
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle("导出失败")
                                .setMessage(it.message)
                                .setPositiveButton(android.R.string.ok, null)
                                .setNegativeButton("Crash Log") { _, _ ->
                                    MaterialAlertDialogBuilder(requireContext())
                                        .setTitle("Crash Log")
                                        .setMessage(it.stackTraceToString())
                                        .setPositiveButton(android.R.string.ok, null)
                                        .show()
                                }
                                .show()
                        }
                    }
                }
            }
        }

    class CategoryItemDetailsLookup(private val recyclerView: RecyclerView) :
        ItemDetailsLookup<String>() {
        override fun getItemDetails(e: MotionEvent): ItemDetails<String>? {
            val view = recyclerView.findChildViewUnder(e.x, e.y)
            if (view != null) {
                return (recyclerView.getChildViewHolder(view) as BlockListAdapter.ViewHolder).getItemDetails()
            }
            return null
        }
    }

    class CategoryItemKeyProvider(private val adapter: BlockListAdapter) :
        ItemKeyProvider<String>(SCOPE_CACHED) {
        override fun getKey(position: Int): String = adapter.currentList[position].url

        override fun getPosition(key: String): Int =
            adapter.currentList.indexOfFirst { it.url == key }
    }

    override fun onBackPressed(): Boolean {
        return if (binding.editText.isFocused) {
            binding.editText.setText("")
            binding.editText.clearFocus()
            true
        } else if (selectedItems?.isEmpty == false) {
            tracker?.clearSelection()
            true
        } else binding.recyclerView.closeMenus()
    }

    override fun onStop() {
        super.onStop()
        (requireContext() as? OnBackPressContainer)?.controller = null
    }

    override fun onResume() {
        super.onResume()
        (requireContext() as? OnBackPressContainer)?.controller = this
    }

}