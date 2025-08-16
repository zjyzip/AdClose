package com.close.hook.ads.ui.fragment.hook

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.R
import com.close.hook.ads.data.model.LogEntry
import com.close.hook.ads.databinding.FragmentCustomHookLogBinding
import com.close.hook.ads.preference.HookPrefs
import com.close.hook.ads.ui.activity.CustomHookActivity
import com.close.hook.ads.ui.adapter.LogAdapter
import com.close.hook.ads.ui.fragment.base.BaseFragment
import com.close.hook.ads.ui.viewmodel.LogViewModel
import com.close.hook.ads.util.INavContainer
import com.close.hook.ads.util.dp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CustomHookLogFragment : BaseFragment<FragmentCustomHookLogBinding>() {

    private val viewModel by viewModels<LogViewModel> {
        LogViewModel.Factory(packageName)
    }
    private lateinit var logAdapter: LogAdapter
    private val packageName by lazy { arguments?.getString(ARG_PACKAGE_NAME) }
    private lateinit var hookPrefs: HookPrefs

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        hookPrefs = HookPrefs.getInstance(requireContext())

        setupToolbar()
        setupSwitch()
        setupRecyclerView()
        observeLogs()
    }

    private fun setupToolbar() {
        (requireActivity() as AppCompatActivity).apply {
            setSupportActionBar(binding.toolbar)
            supportActionBar?.apply {
                setDisplayShowTitleEnabled(false)
                setDisplayHomeAsUpEnabled(true)
            }
        }
        binding.toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_log, menu)
            }

            override fun onPrepareMenu(menu: Menu) {
                menu.findItem(R.id.action_clear_logs).isVisible = hookPrefs.getEnableLogging(packageName)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return if (menuItem.itemId == R.id.action_clear_logs) {
                    viewModel.clearLogs()
                    true
                } else false
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun setupSwitch() {
        val logSwitch = binding.toolbar.findViewById<MaterialSwitch>(R.id.log_switch)
        logSwitch.isChecked = hookPrefs.getEnableLogging(packageName)

        binding.recyclerViewLogs.isVisible = logSwitch.isChecked
        binding.emptyView.isVisible = !logSwitch.isChecked
        if (!logSwitch.isChecked) {
            binding.emptyView.text = "Logging is disabled"
        } else {
            binding.emptyView.text = "No Log Record"
        }

        logSwitch.setOnCheckedChangeListener { _, isChecked ->
            hookPrefs.setEnableLogging(packageName, isChecked)
            binding.recyclerViewLogs.isVisible = isChecked
            binding.emptyView.isVisible = !isChecked
            if (isChecked) {
                binding.emptyView.text = "No Log Record"
                viewModel.loadLogs()
            } else {
                binding.emptyView.text = "Logging is disabled"
                logAdapter.submitList(emptyList())
            }
            requireActivity().invalidateOptionsMenu()
        }
    }

    private fun setupRecyclerView() {
        logAdapter = LogAdapter { logEntry ->
            showLogDetailDialog(logEntry)
        }
        binding.recyclerViewLogs.apply {
            setHasFixedSize(true)
            layoutManager = LinearLayoutManager(context)
            adapter = logAdapter
            FastScrollerBuilder(this).useMd2Style().build()

            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val bottomNavHeight = (activity as? CustomHookActivity)?.bottomNavigationView?.height ?: 0
                setPadding(paddingLeft, paddingTop, paddingRight, bottomNavHeight)
                clipToPadding = false
            }

            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                private var totalDy = 0
                private val scrollThreshold = 20.dp
                private val navContainer = activity as? INavContainer

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    navContainer?.let {
                        if (dy > 0) {
                            totalDy += dy
                            if (totalDy > scrollThreshold) {
                                it.hideNavigation()
                                totalDy = 0
                            }
                        } else if (dy < 0) {
                            totalDy += dy
                            if (totalDy < -scrollThreshold) {
                                it.showNavigation()
                                totalDy = 0
                            }
                        }
                    }
                }
            })
        }
    }

    private fun observeLogs() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.logs.collect { logs ->
                    if (hookPrefs.getEnableLogging(packageName)) {
                        binding.emptyView.isVisible = logs.isEmpty()

                        val isAtTop = (binding.recyclerViewLogs.layoutManager as LinearLayoutManager)
                            .findFirstCompletelyVisibleItemPosition() <= 0

                        logAdapter.submitList(logs) {
                            if (isAtTop && logs.isNotEmpty()) {
                                binding.recyclerViewLogs.scrollToPosition(0)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showLogDetailDialog(logEntry: LogEntry) {
        val stackTrace = logEntry.stackTrace
        if (stackTrace.isNullOrBlank()) {
            Toast.makeText(requireContext(), "No stack trace available.", Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Stack Trace")
            .setMessage(stackTrace)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton("Copy Stack") { _, _ ->
                copyToClipboard("stack_trace", stackTrace)
            }
            .setNegativeButton("Copy Log") { _, _ ->
                copyToClipboard("log_entry", formatLogEntry(logEntry))
            }
            .show()
    }

    private fun formatLogEntry(logEntry: LogEntry): String {
        val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        return "[${dateFormat.format(Date(logEntry.timestamp))}] [${logEntry.tag}] ${logEntry.message}"
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val ARG_PACKAGE_NAME = "packageName"
        fun newInstance(packageName: String?) = CustomHookLogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_PACKAGE_NAME, packageName)
            }
        }
    }
}
