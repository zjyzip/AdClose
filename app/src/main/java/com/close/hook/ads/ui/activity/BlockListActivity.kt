package com.close.hook.ads.ui.activity

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.result.contract.ActivityResultContracts
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
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import android.net.Uri
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import java.io.IOException

class BlockListActivity : BaseActivity() {

    private lateinit var binding: ActivityBlockListBinding
    private val viewModel by lazy { ViewModelProvider(this)[BlockListViewModel::class.java] }
    private lateinit var mAdapter: BlockListAdapter
    private lateinit var mLayoutManager: RecyclerView.LayoutManager
    private var searchJob: Job? = null
    private val urlDao by lazy {
        UrlDatabase.getDatabase(this).urlDao
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initView()
        if (viewModel.blockList.isEmpty()) {
            viewModel.getBlackList()
        } else {
            submitList()
        }

        initToolBar()
        initClearHistory()
        initEditText()
        initButton()

        viewModel.blackListLiveData.observe(this) { newList ->
            viewModel.blockList.clear()
            viewModel.blockList.addAll(newList)
            submitList()
        }
    }

    private fun initView() {
        mLayoutManager = LinearLayoutManager(this)
        mAdapter = BlockListAdapter(this,
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
        }
    }

    private fun submitList() {
        val newList = ArrayList<Item>().apply {
            addAll(viewModel.blockList)
        }
        mAdapter.submitList(newList)
    }

    private fun onRemoveUrl(position: Int) {
        val url = viewModel.blockList[position].url
        CoroutineScope(Dispatchers.IO).launch {
            urlDao.delete(url)
            viewModel.blockList.removeAt(position)
            withContext(Dispatchers.Main) {
                submitList()
            }
        }
    }

    private fun onEditUrl(position: Int) {
        val item = viewModel.blockList[position]
        val dialogView = LayoutInflater.from(this).inflate(R.layout.item_block_list_add, null)
        val editText: TextInputEditText = dialogView.findViewById(R.id.editText)
        editText.setText(item.url)
        val type = dialogView.findViewById<EditText>(R.id.type)
        type.setText(item.type)

        MaterialAlertDialogBuilder(this).setTitle("Edit Rule")
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newType = type.text.toString()
                val newUrl = editText.text.toString()
                CoroutineScope(Dispatchers.IO).launch {
                    urlDao.delete(item.url)
                    urlDao.insert(Url(newType, newUrl))
                    withContext(Dispatchers.Main) {
                        viewModel.blockList[position] = Item(newType, newUrl)
                        submitList()
                    }
                }
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
            submitList()
        }
        binding.add.setOnClickListener {
            val dialogView = LayoutInflater.from(this).inflate(R.layout.item_block_list_add, null, false)
            val editText: TextInputEditText = dialogView.findViewById(R.id.editText)
            val type: MaterialAutoCompleteTextView = dialogView.findViewById(R.id.type)
            type.setText("URL")
            type.setAdapter(
                ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_dropdown_item,
                    arrayOf("Host", "URL", "KeyWord")
                )
            )
            MaterialAlertDialogBuilder(this).setTitle("Add Rule")
                .setView(dialogView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val newType = type.text.toString()
                    val newUrl = editText.text.toString()
                    CoroutineScope(Dispatchers.IO).launch {
                        val isExist = urlDao.isExist(newUrl)
                        if (!isExist) {
                            urlDao.insert(Url(newType, newUrl))
                            withContext(Dispatchers.Main) {
                                viewModel.blockList.add(0, Item(newType, newUrl))
                                submitList()
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@BlockListActivity, "规则已存在", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                .create().apply {
                    window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
                    editText.requestFocus()
                }.show()
        }
    }
    
    private fun initToolBar() {
        binding.backbtn.setOnClickListener {
            finish()
        }
    }
    
    private fun initClearHistory() {
        binding.clearAll.setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle("确定清除全部黑名单？")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    CoroutineScope(Dispatchers.IO).launch {
                        urlDao.deleteAll()
                        viewModel.blockList.clear()
                        withContext(Dispatchers.Main) {
                            submitList()
                        }
                    }
                }
                .show()
        }
    }

    private fun search() {
        val searchText = binding.editText.text.toString()
        searchJob?.cancel()
        searchJob = CoroutineScope(Dispatchers.Main).launch {
            val filteredList = withContext(Dispatchers.IO) {
                urlDao.searchUrls("%$searchText%").map { Item(it.type, it.url) }
            }
            mAdapter.submitList(filteredList)
        }
    }

    private fun initEditText() {
        binding.editText.apply {
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
    
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
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                        ?.hideSoftInputFromWindow(windowToken, 0)
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun prepareDataForExport(): List<String> {
        return viewModel.blockList
            .map { "${it.type}, ${it.url}" } // 转换为 "Type, Url" 格式
            .distinct() // 去重
            .filter { it.contains(",") } // 确保每项至少包含一个逗号
            .sorted() // 字母排序
    }

    private val restoreSAFLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { uri ->
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val existingUrls = urlDao.getAllUrls().toSet() // 获取所有现有的URLs并转换为集合
                        val inputStream = contentResolver.openInputStream(uri)
                        val newItems = inputStream?.bufferedReader()?.useLines { lines ->
                            lines.mapNotNull { line ->
                                val parts = line.split(",\\s*".toRegex()).map { it.trim() }
                                if (parts.size == 2 && parts[1] !in existingUrls) Url(parts[0], parts[1]) else null
                            }.toList()
                        } ?: listOf()
    
                        if (newItems.isNotEmpty()) {
                            urlDao.insertAll(newItems)
                        }
    
                        val newBlockItems = newItems.map { Item(it.type, it.url) }
                        viewModel.blockList.addAll(0, newBlockItems)
    
                        withContext(Dispatchers.Main) {
                            submitList()
                            Toast.makeText(this@BlockListActivity, "导入成功", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: IOException) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@BlockListActivity, "导入失败", Toast.LENGTH_SHORT).show()
                        }
                        e.printStackTrace()
                    }
                }
            }
        }

    private val backupSAFLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { uri: Uri? ->
            uri?.let {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        contentResolver.openOutputStream(uri)?.bufferedWriter().use { writer ->
                            prepareDataForExport().forEach { line ->
                                writer?.write("$line\n")
                            }
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@BlockListActivity, "导出成功", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: IOException) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@BlockListActivity, "导出失败", Toast.LENGTH_SHORT).show()
                        }
                        e.printStackTrace()
                    }
                }
            }
        }

}
