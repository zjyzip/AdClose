package com.close.hook.ads.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import kotlinx.coroutines.launch

class DataManagerFragment : Fragment() {

    private var _binding: FragmentDataManagerBinding? = null
    private val binding get() = _binding!!
    private val viewModel by viewModels<DataManagerViewModel>()
    
    private val filesAdapter by lazy { 
        DataManagerAdapter { item -> showDeleteConfirmationDialog(item) } 
    }
    private val prefsAdapter by lazy { 
        DataManagerAdapter { item -> showDeleteConfirmationDialog(item) } 
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
