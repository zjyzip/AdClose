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
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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

    @SuppressLint("InflateParams")
    private fun initButton() {
        binding.export.setOnClickListener {
            val newList = ArrayList<String>()
            viewModel.blockList.forEach {
                newList.add(it.url)
            }
            if (saveFile(Gson().toJson(newList)))
                backupSAFLauncher.launch("block_list.json")
            else
                Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show()
        }
        binding.restore.setOnClickListener {
            restoreSAFLauncher.launch("application/json")
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
            type.setAdapter(
                ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_dropdown_item,
                    arrayOf("host", "url", "keyword")
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

    private fun saveFile(content: String): Boolean {
        return try {
            val dir = File(this.cacheDir.toString())
            if (!dir.exists())
                dir.mkdir()
            val file = File("${this.cacheDir}/block_list.json")
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
        type.setText(viewModel.blockList[position].type)
        type.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("host", "url", "keyword")
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

    private val restoreSAFLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) restore@{ uri ->
            if (uri == null) return@restore
            runCatching {
                val string = this.contentResolver
                    .openInputStream(uri)?.reader().use { it?.readText() }
                    ?: throw IOException("Backup file was damaged")
                val json: Array<Item> = Gson().fromJson(
                    string,
                    Array<Item>::class.java
                )
                json.forEach {
                    if (viewModel.blockList.indexOf(Item(it.type, it.url)) == -1) {
                        urlDao.insert(Url(it.type, it.url))
                        viewModel.blockList.add(0, Item(it.type, it.url))
                        submitList()
                    }
                }
            }.onFailure {
                MaterialAlertDialogBuilder(this)
                    .setTitle("导入失败")
                    .setMessage(it.message)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton("Crash Log") { _, _ ->
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Crash Log")
                            .setMessage(it.stackTraceToString())
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                    .show()
            }
        }

    private val backupSAFLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) backup@{ uri ->
            if (uri == null) return@backup
            try {
                File("${this.cacheDir}/block_list.json").inputStream().use { input ->
                    this.contentResolver.openOutputStream(uri).use { output ->
                        if (output == null) Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT)
                            .show()
                        else input.copyTo(output)
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

}