package com.close.hook.ads.ui.fragment

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.close.hook.ads.R
import com.close.hook.ads.data.model.ItemType
import com.close.hook.ads.data.model.ManagedItem
import com.close.hook.ads.databinding.FragmentDataManagerBinding
import com.close.hook.ads.ui.adapter.DataManagerAdapter
import com.close.hook.ads.ui.viewmodel.DataManagerViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class DataManagerFragment : Fragment() {

    private var _binding: FragmentDataManagerBinding? = null
    private val binding get() = _binding!!
    private val viewModel by viewModels<DataManagerViewModel>()

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            uri?.let { onFileSelectedForExport(it) }
        }
    
    private var contentToExport: ByteArray? = null
    
    private val filesAdapter by lazy { 
        DataManagerAdapter(
            onExportClick = { item -> handleExportClick(item) },
            onDeleteClick = { item -> showDeleteConfirmationDialog(item) }
        ) 
    }
    private val prefsAdapter by lazy { 
        DataManagerAdapter(
            onExportClick = { item -> handleExportClick(item) },
            onDeleteClick = { item -> showDeleteConfirmationDialog(item) }
        ) 
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDataManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

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
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
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
            }
        }
    }

    private fun handleExportClick(item: ManagedItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.progressBar.isVisible = true
            val content = viewModel.getItemContent(item)
            binding.progressBar.isVisible = false

            if (content != null) {
                contentToExport = content
                val exportFileName = if (item.type == ItemType.PREFERENCE) "${item.name}.xml" else item.name
                createDocumentLauncher.launch(exportFileName)
            } else {
                Toast.makeText(requireContext(), "Failed to read content for export", Toast.LENGTH_SHORT).show()
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
        val isFile = item.type == ItemType.FILE
        val title = if (isFile) R.string.delete_file_title else R.string.delete_prefs_title
        val message = getString(if (isFile) R.string.delete_file_message else R.string.delete_prefs_message, item.name)
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                if (isFile) {
                    viewModel.deleteFile(item.name)
                } else {
                    viewModel.deletePreferenceGroup(item.name)
                }
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
