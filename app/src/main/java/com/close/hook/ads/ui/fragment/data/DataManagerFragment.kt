package com.close.hook.ads.ui.fragment.data

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.close.hook.ads.R
import com.close.hook.ads.data.model.ItemType
import com.close.hook.ads.data.model.ManagedItem
import com.close.hook.ads.databinding.FragmentDataManagerBinding
import com.close.hook.ads.ui.adapter.DataManagerAdapter
import com.close.hook.ads.ui.fragment.base.BaseFragment
import com.close.hook.ads.ui.viewmodel.DataManagerViewModel
import com.close.hook.ads.ui.viewmodel.ExportEvent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class DataManagerFragment : BaseFragment<FragmentDataManagerBinding>() {

    private val viewModel by viewModels<DataManagerViewModel>()

    private var contentToExport: ByteArray? = null

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            uri?.let { onFileSelectedForExport(it) }
        }

    private val filesAdapter by lazy { createAdapter() }
    private val prefsAdapter by lazy { createAdapter() }
    private val dbsAdapter by lazy { createAdapter() }

    private fun createAdapter() = DataManagerAdapter(
        onExportClick = { item -> viewModel.prepareExport(item) },
        onDeleteClick = { item -> showDeleteConfirmationDialog(item) }
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerViews()
        observeViewModel()
    }

    private fun setupRecyclerViews() {
        binding.filesRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = filesAdapter
            isNestedScrollingEnabled = false
        }
        binding.prefsRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = prefsAdapter
            isNestedScrollingEnabled = false
        }
        binding.dbsRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = dbsAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        binding.progressBar.isVisible = state.isLoading
                        
                        state.frameworkInfo?.let {
                            binding.frameworkName.text = "Framework: ${it.frameworkName}"
                            binding.frameworkVersion.text = "Version: ${it.frameworkVersion}"
                            binding.apiVersion.text = "API: ${it.apiVersion}"
                        }
                        
                        binding.filesCard.isVisible = state.managedFiles.isNotEmpty()
                        filesAdapter.submitList(state.managedFiles)

                        binding.prefsCard.isVisible = state.preferenceGroups.isNotEmpty()
                        prefsAdapter.submitList(state.preferenceGroups)
                        
                        binding.dbsCard.isVisible = state.databases.isNotEmpty()
                        dbsAdapter.submitList(state.databases)
                    }
                }

                launch {
                    viewModel.exportEvent.collect { event ->
                        when(event) {
                            is ExportEvent.Success -> {
                                contentToExport = event.content
                                createDocumentLauncher.launch(event.fileName)
                            }
                            is ExportEvent.Failed -> {
                                Toast.makeText(requireContext(), "Failed to read content for export", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }
    
    private fun onFileSelectedForExport(uri: Uri) {
        val content = contentToExport ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(content)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Export successful", Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Export failed", Toast.LENGTH_SHORT).show()
                }
            } finally {
                contentToExport = null
            }
        }
    }

    private fun showDeleteConfirmationDialog(item: ManagedItem) {
        val titleRes: Int
        val messageRes: Int
        when(item.type) {
            ItemType.FILE -> {
                titleRes = R.string.delete_file_title
                messageRes = R.string.delete_file_message
            }
            ItemType.PREFERENCE -> {
                titleRes = R.string.delete_prefs_title
                messageRes = R.string.delete_prefs_message
            }
            ItemType.DATABASE -> {
                titleRes = R.string.delete_db_title
                messageRes = R.string.delete_db_message
            }
        }
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setMessage(getString(messageRes, item.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewModel.deleteItem(item)
            }
            .show()
    }
}
