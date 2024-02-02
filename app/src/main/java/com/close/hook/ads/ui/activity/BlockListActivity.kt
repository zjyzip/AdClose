package com.close.hook.ads.ui.activity

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.close.hook.ads.R
import com.close.hook.ads.data.database.UrlDatabase
import com.close.hook.ads.data.model.Item
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.databinding.ActivityBlockListBinding
import com.close.hook.ads.ui.adapter.BlockListAdapter
import com.close.hook.ads.ui.viewmodel.BlockListViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BlockListActivity : BaseActivity(), BlockListAdapter.CallBack {

    private lateinit var binding: ActivityBlockListBinding
    private val viewModel by lazy { ViewModelProvider(this)[BlockListViewModel::class.java] }
    private lateinit var mAdapter: BlockListAdapter
    private lateinit var mLayoutManager: LinearLayoutManager
    private val urlDao by lazy {
        UrlDatabase.getDatabase(this).urlDao
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initView()
        if (viewModel.blockList.isEmpty()) {
            viewModel.getBlackList(this)
        } else {
            submitList()
        }

        initToolBar()
        initClearHistory()
        initEditText()
        initButton()

        viewModel.blackListLiveData.observe(this) {
            if (viewModel.blockList.isEmpty()) {
                it.forEach { url ->
                    viewModel.blockList.add(Item(url))
                }
                if (viewModel.blockList.isEmpty())
                    binding.clearAll.visibility = View.GONE
                else
                    binding.clearAll.visibility = View.VISIBLE
                submitList()
            }

        }

    }

    private fun initButton() {
        binding.clear.setOnClickListener {
            binding.editText.text = null
            submitList()
        }
        binding.add.setOnClickListener {
            val dialogView =
                LayoutInflater.from(this).inflate(R.layout.item_block_list_add, null, false)
            val editText: EditText = dialogView.findViewById(R.id.editText)
            MaterialAlertDialogBuilder(this).apply {
                setTitle("Add Rule")
                setView(dialogView)
                setNegativeButton(android.R.string.cancel, null)
                setPositiveButton(android.R.string.ok) { _, _ ->
                    CoroutineScope(Dispatchers.IO).launch {
                        with(
                            editText.text.toString()
                                .replace(" ", "")
                                .replace("\n", "")
                        ) {
                            if (urlDao.isExist(this)) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        this@BlockListActivity,
                                        "已存在",
                                        Toast.LENGTH_SHORT
                                    )
                                        .show()
                                }
                            } else {
                                urlDao.insert(Url(System.currentTimeMillis(), this))
                                withContext(Dispatchers.Main) {
                                    viewModel.blockList.add(0, Item(this@with))
                                    submitList()
                                }
                            }
                        }
                    }
                }
            }.create().apply {
                window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
                editText.requestFocus()
            }.show()
        }
    }

    private fun search() {
        val newList = ArrayList<Item>()
        viewModel.blockList.forEach {
            if (it.url.contains(binding.editText.text.toString()))
                newList.add(Item(it.url))
        }
        mAdapter.submitList(newList)
    }

    private fun initEditText() {
        binding.editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                binding.clear.visibility = if (binding.editText.text.isNullOrEmpty())
                    View.GONE
                else
                    View.VISIBLE
                search()
            }

            override fun afterTextChanged(s: Editable) {}
        })
    }

    private fun initToolBar() {
        binding.toolBar.apply {
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener {
                finish()
            }
        }
    }

    private fun initClearHistory() {
        binding.clearAll.setOnClickListener {
            MaterialAlertDialogBuilder(this).apply {
                setTitle("确定清除全部黑名单？")
                setNegativeButton(android.R.string.cancel, null)
                setPositiveButton(android.R.string.ok) { _, _ ->
                    CoroutineScope(Dispatchers.IO).launch {
                        urlDao.deleteAll()
                    }
                    viewModel.blockList.clear()
                    binding.clearAll.visibility = View.GONE
                    val newList = ArrayList<Item>()
                    mAdapter.submitList(newList)
                }
                show()
            }
        }
    }

    private fun initView() {
        mLayoutManager = LinearLayoutManager(this)
        mAdapter = BlockListAdapter(this)
        mAdapter.setCallBack(this)
        binding.recyclerView.apply {
            adapter = mAdapter
            layoutManager = mLayoutManager
        }
    }

    override fun onRemoveUrl(position: Int) {
        val url = viewModel.blockList[position].url
        CoroutineScope(Dispatchers.IO).launch {
            urlDao.delete(url)
        }
        viewModel.blockList.removeAt(position)
        submitList()
    }

    private fun submitList() {
        val newList = ArrayList<Item>()
        newList.addAll(viewModel.blockList)
        mAdapter.submitList(newList)
    }

    override fun onEditUrl(position: Int) {
        val dialogView =
            LayoutInflater.from(this).inflate(R.layout.item_block_list_add, null, false)
        val editText: EditText = dialogView.findViewById(R.id.editText)
        editText.setText(viewModel.blockList[position].url)
        MaterialAlertDialogBuilder(this).apply {
            setTitle("Edit Rule")
            setView(dialogView)
            setNegativeButton(android.R.string.cancel, null)
            setPositiveButton(android.R.string.ok) { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    with(
                        editText.text.toString()
                            .replace(" ", "")
                            .replace("\n", "")
                    ) {
                        if (urlDao.isExist(this)) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@BlockListActivity,
                                    "已存在",
                                    Toast.LENGTH_SHORT
                                )
                                    .show()
                            }
                        } else {
                            urlDao.delete(viewModel.blockList[position].url)
                            urlDao.insert(Url(System.currentTimeMillis(), this))
                            withContext(Dispatchers.Main) {
                                viewModel.blockList.removeAt(position)
                                viewModel.blockList.add(0, Item(this@with))
                                submitList()
                            }
                        }
                    }
                }
            }
        }.create().apply {
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            editText.requestFocus()
        }.show()
    }

}