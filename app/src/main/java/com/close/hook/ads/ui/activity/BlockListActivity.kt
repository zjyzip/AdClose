package com.close.hook.ads.ui.activity

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import java.io.IOException

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
                viewModel.blockList.addAll(it)
                submitList()
            }

        }

    }

    @SuppressLint("InflateParams", "SetTextI18n")
    private fun initButton() {
        binding.export.setOnClickListener {
            backupSAFLauncher.launch("block_list.rule")
        }
        binding.restore.setOnClickListener {
            restoreSAFLauncher.launch("*/*")
        }
        binding.clear.setOnClickListener {
            binding.editText.text = null
            submitList()
        }
        binding.add.setOnClickListener {
            val dialogView =
                LayoutInflater.from(this).inflate(R.layout.item_block_list_add, null, false)
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
                            if (this.isEmpty()) return@launch
                            else if (viewModel.blockList.indexOf(
                                    Item(type.text.toString(), this)
                                ) != -1
                            ) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        this@BlockListActivity,
                                        "已存在",
                                        Toast.LENGTH_SHORT
                                    )
                                        .show()
                                }
                            } else {
                                urlDao.insert(Url(type.text.toString(), this))
                                withContext(Dispatchers.Main) {
                                    viewModel.blockList.add(
                                        0,
                                        Item(type.text.toString(), this@with)
                                    )
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
                newList.add(it)
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
            if (viewModel.blockList.isEmpty())
                return@setOnClickListener
            MaterialAlertDialogBuilder(this).apply {
                setTitle("确定清除全部黑名单？")
                setNegativeButton(android.R.string.cancel, null)
                setPositiveButton(android.R.string.ok) { _, _ ->
                    CoroutineScope(Dispatchers.IO).launch {
                        urlDao.deleteAll()
                    }
                    viewModel.blockList.clear()
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
        FastScrollerBuilder(binding.recyclerView).useMd2Style().build()
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

    @SuppressLint("InflateParams")
    override fun onEditUrl(position: Int) {
        val dialogView =
            LayoutInflater.from(this).inflate(R.layout.item_block_list_add, null, false)
        val editText: EditText = dialogView.findViewById(R.id.editText)
        editText.setText(viewModel.blockList[position].url)
        val type: MaterialAutoCompleteTextView = dialogView.findViewById(R.id.type)
        type.setText(
            viewModel.blockList[position].type.replace("host", "Host").replace("url", "URL")
                .replace("keyword", "KeyWord")
        )
        type.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Host", "URL", "KeyWord")
            )
        )
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
                        if (this.isEmpty()) return@launch
                        else if (viewModel.blockList.indexOf(
                                Item(type.text.toString(), this)
                            ) != -1
                        ) {
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
                            urlDao.insert(Url(type.text.toString(), this))
                            withContext(Dispatchers.Main) {
                                viewModel.blockList.removeAt(position)
                                viewModel.blockList.add(0, Item(type.text.toString(), this@with))
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

    private fun prepareDataForExport(): List<String> {
        return viewModel.blockList
            .map { "${it.type}, ${it.url}" } // 转换为 "Type, Url" 格式
            .distinct() // 去重
            .filter { it.contains(",") } // 确保每项至少包含一个逗号
            .sorted() // 字母排序
    }

    private val restoreSAFLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { uri ->
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val newItems = contentResolver.openInputStream(uri)?.bufferedReader()?.useLines { lines ->
                            lines.mapNotNull { line ->
                                val parts = line.split(",\\s*".toRegex())
                                if (parts.size == 2) Item(parts[0].trim(), parts[1].trim()) else null
                            }
                            .distinct()
                            .sortedBy { it.url }
                            .toList()
                        } ?: listOf()

                        newItems.forEach { item ->
                            if (viewModel.blockList.indexOf(Item(item.type, item.url)) == -1) {
                                urlDao.insert(Url(item.type, item.url))
                                viewModel.blockList.add(0, Item(item.type, item.url))
                            }
                        }

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
                try {
                    contentResolver.openOutputStream(it)?.bufferedWriter().use { writer ->
                        prepareDataForExport().forEach { line ->
                            writer?.write("$line\n")
                        }
                    }
                    Toast.makeText(this, "导出成功", Toast.LENGTH_SHORT).show()
                } catch (e: IOException) {
                    Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show()
                    e.printStackTrace()
                }
            }
        }

}