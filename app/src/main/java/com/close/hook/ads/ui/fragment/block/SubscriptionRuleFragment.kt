@file:Suppress("DEPRECATION")
package com.close.hook.ads.ui.fragment.block

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.close.hook.ads.R
import com.close.hook.ads.data.model.SubscriptionSource
import com.close.hook.ads.databinding.DialogSubscriptionSourceBinding
import com.close.hook.ads.databinding.FragmentSubscriptionRuleBinding
import com.close.hook.ads.ui.adapter.SubscriptionAdapter
import com.close.hook.ads.ui.fragment.base.BaseFragment
import com.close.hook.ads.ui.viewmodel.SubscriptionViewModel
import com.close.hook.ads.util.dp
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch

class SubscriptionRuleFragment : BaseFragment<FragmentSubscriptionRuleBinding>() {

    private val viewModel: SubscriptionViewModel by viewModels()
    private lateinit var adapter: SubscriptionAdapter

    private val intervalValues = longArrayOf(15, 60, 360, 720, 1440, 4320, 10080, 43200)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupFab()
        observeData()
        observeEvents()
    }

    private fun setupRecyclerView() {
        adapter = SubscriptionAdapter(
            onToggle = viewModel::toggleEnabled,
            onRefresh = { viewModel.refresh(it.id) },
            onEdit = ::showEditDialog,
            onDelete = ::confirmDelete
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.recyclerView.attachNavScrollListener()
    }

    private fun setupFab() {
        binding.fabAdd.layoutParams = CoordinatorLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            behavior = HideBottomViewOnScrollBehavior<FloatingActionButton>()
        }
        binding.fabAdd.setOnClickListener { showAddDialog() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.fabAdd.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                rightMargin = 25.dp
                bottomMargin = navigationBars.bottom + 105.dp
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.sources.collect { sources ->
                adapter.submitList(sources)
                binding.emptyView.isVisible = sources.isEmpty()
            }
        }
    }

    private fun observeEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    is SubscriptionViewModel.Event.RefreshStarted ->
                        Toast.makeText(requireContext(), R.string.subscription_refresh_started, Toast.LENGTH_SHORT).show()
                    is SubscriptionViewModel.Event.Error ->
                        Toast.makeText(requireContext(), event.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshDue()
    }

    private fun showAddDialog() = showSourceDialog(null)

    private fun showEditDialog(source: SubscriptionSource) = showSourceDialog(source)

    private fun showSourceDialog(source: SubscriptionSource?) {
        val isEdit = source != null
        val dialogBinding = DialogSubscriptionSourceBinding.inflate(layoutInflater)

        val intervalLabels = resources.getStringArray(R.array.subscription_intervals)
        var selectedInterval = source?.refreshIntervalMinutes ?: SubscriptionSource.DEFAULT_REFRESH_INTERVAL_MINUTES
        var selectedIntervalIndex = intervalValues.indexOfFirst { it == selectedInterval }.coerceAtLeast(0)

        dialogBinding.intervalInput.setText(intervalLabels[selectedIntervalIndex])
        dialogBinding.urlInput.setText(source?.url.orEmpty())
        dialogBinding.nameInput.setText(source?.name.orEmpty())

        val intervalClickListener = View.OnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.subscription_interval_hint)
                .setSingleChoiceItems(intervalLabels, selectedIntervalIndex) { dialog, which ->
                    selectedIntervalIndex = which
                    selectedInterval = intervalValues[which]
                    dialogBinding.intervalInput.setText(intervalLabels[which])
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        dialogBinding.intervalInput.setOnClickListener(intervalClickListener)
        dialogBinding.intervalLayout.setEndIconOnClickListener(intervalClickListener)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isEdit) R.string.subscription_edit_title else R.string.subscription_add_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val url = dialogBinding.urlInput.text?.toString()?.trim().orEmpty()
                val name = dialogBinding.nameInput.text?.toString()?.trim().orEmpty()
                if (url.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.subscription_url_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (source != null) {
                    viewModel.updateSource(source.copy(url = url, name = name, refreshIntervalMinutes = selectedInterval))
                } else {
                    viewModel.addSource(url, name, selectedInterval)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(source: SubscriptionSource) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.subscription_delete_title)
            .setMessage(getString(R.string.subscription_delete_message, source.name))
            .setPositiveButton(R.string.action_delete) { _, _ -> viewModel.deleteSource(source) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
