package com.close.hook.ads.ui.fragment.hook

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
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
import com.close.hook.ads.ui.adapter.LogAdapter
import com.close.hook.ads.ui.fragment.base.BaseFragment
import com.close.hook.ads.ui.viewmodel.LogViewModel
import com.close.hook.ads.util.INavContainer
import com.close.hook.ads.util.OnBackPressContainer
import com.close.hook.ads.util.OnBackPressListener
import com.close.hook.ads.util.dp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CustomHookLogFragment : BaseFragment<FragmentCustomHookLogBinding>(), OnBackPressListener {

    private val viewModel by viewModels<LogViewModel> { LogViewModel.Factory(packageName) }
    private val logAdapter by lazy {
        LogAdapter(
            onItemClick = { logEntry -> showLogDetailDialog(logEntry) },
            onItemLongClick = { logEntry ->
                val formattedLog = formatLogEntry(logEntry)
                copyToClipboard("log_entry", formattedLog)
            }
        )
    }
    private val packageName by lazy { arguments?.getString(ARG_PACKAGE_NAME) }
    private val hookPrefs by lazy { HookPrefs.getInstance(requireContext()) }
    private val inputMethodManager by lazy { requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupToolbar()
        setupRecyclerView()
        setupSearch()
        observeLogs()
        
        updateUIForLogState(hookPrefs.getEnableLogging(packageName))
    }

    override fun onResume() {
        super.onResume()
        (activity as? OnBackPressContainer)?.backController = this
    }

    override fun onPause() {
        super.onPause()
        (activity as? OnBackPressContainer)?.backController = null
    }



    override fun onBackPressed(): Boolean {
        if (binding.editTextSearch.isFocused) {
            setIconAndFocus(isFocused = false)
            return true
        }
        return false
    }

    private fun setupToolbar() {
        (activity as? AppCompatActivity)?.setSupportActionBar(binding.toolbar)
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_log, menu)
            }

            override fun onPrepareMenu(menu: Menu) {
                val isLogEnabled = hookPrefs.getEnableLogging(packageName)
                menu.findItem(R.id.action_clear_logs).isVisible = isLogEnabled
                
                val enableLogItem = menu.findItem(R.id.action_enable_log)
                enableLogItem?.isChecked = isLogEnabled
                enableLogItem?.title = "Enable Log"
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_clear_logs -> {
                        viewModel.clearLogs()
                        true
                    }
                    R.id.action_enable_log -> {
                        val newState = !menuItem.isChecked
                        onLogSwitchChanged(newState)
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun onLogSwitchChanged(isChecked: Boolean) {
        hookPrefs.setEnableLogging(packageName, isChecked)
        updateUIForLogState(isChecked)
        if (isChecked) viewModel.loadLogs()
        
        requireActivity().invalidateOptionsMenu()
    }
    
    private fun updateUIForLogState(isEnabled: Boolean) {
        binding.cardViewSearch.isVisible = isEnabled
        binding.recyclerViewLogs.isVisible = isEnabled
        binding.emptyView.isVisible = !isEnabled
        
        if (isEnabled) {
            binding.emptyView.text = "No Log Record"
        } else {
            binding.emptyView.text = "Logging is disabled"
            logAdapter.submitList(emptyList())
            if (binding.editTextSearch.isFocused) {
                setIconAndFocus(isFocused = false)
            }
        }
    }

    private fun setupSearch() {
        with(binding) {
            editTextSearch.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                setIconAndFocus(isFocused = hasFocus)
            }
            editTextSearch.doOnTextChanged { text, _, _, _ ->
                viewModel.setSearchQuery(text.toString())
                clearSearch.isVisible = !text.isNullOrEmpty()
            }
            searchIcon.setOnClickListener { onSearchIconClicked() }
            clearSearch.setOnClickListener { editTextSearch.text = null }
        }
    }

    private fun onSearchIconClicked() {
        with(binding.editTextSearch) {
            if (isFocused) {
                setIconAndFocus(isFocused = false)
            } else {
                setIconAndFocus(isFocused = true)
            }
        }
    }

    private fun setIconAndFocus(isFocused: Boolean) {
        val drawableId = if (isFocused) R.drawable.ic_magnifier_to_back else R.drawable.ic_back_to_magnifier
        binding.searchIcon.apply {
            setImageDrawable(ContextCompat.getDrawable(requireContext(), drawableId))
            (drawable as? AnimatedVectorDrawable)?.start()
        }

        if (isFocused) {
            binding.editTextSearch.showKeyboard()
        } else {
            binding.editTextSearch.hideKeyboard()
            binding.editTextSearch.setText("")
        }
    }

    private fun setupRecyclerView() {
        binding.recyclerViewLogs.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = logAdapter
            FastScrollerBuilder(this).useMd2Style().build()
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                private var totalDy = 0
                private val scrollThreshold = 20.dp
                private val navContainer = activity as? INavContainer
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    navContainer?.let {
                        if (dy > 0) {
                            totalDy += dy
                            if (totalDy > scrollThreshold) it.hideNavigation().also { totalDy = 0 }
                        } else if (dy < 0) {
                            totalDy += dy
                            if (totalDy < -scrollThreshold) it.showNavigation().also { totalDy = 0 }
                        }
                    }
                }
            })
        }
    }

    private fun observeLogs() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.logs.collectLatest { logs ->
                    if (hookPrefs.getEnableLogging(packageName)) {
                        logAdapter.submitList(logs)
                        updateEmptyView(logs.isEmpty())
                    }
                }
            }
        }
    }
    
    private fun updateEmptyView(isEmpty: Boolean) {
        binding.emptyView.isVisible = isEmpty
        if (isEmpty) {
            val currentQuery = viewModel.searchQuery.value
            binding.emptyView.text = if (currentQuery.isNotBlank()) {
                "No results for \"$currentQuery\""
            } else {
                "No Log Record"
            }
        }
    }

    private fun showLogDetailDialog(logEntry: LogEntry) {
        val stackTrace = logEntry.stackTrace.takeIf { !it.isNullOrBlank() } ?: run {
            Toast.makeText(requireContext(), "No stack trace available.", Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Stack Trace")
            .setMessage(stackTrace)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton("Copy Stack") { _, _ -> copyToClipboard("stack_trace", stackTrace) }
            .setNegativeButton("Copy Log") { _, _ -> copyToClipboard("log_entry", formatLogEntry(logEntry)) }
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
    
    private fun EditText.showKeyboard() {
        requestFocus()
        inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun EditText.hideKeyboard() {
        clearFocus()
        inputMethodManager.hideSoftInputFromWindow(this.windowToken, 0)
    }

    companion object {
        private const val ARG_PACKAGE_NAME = "packageName"
        fun newInstance(packageName: String?): CustomHookLogFragment =
            CustomHookLogFragment().apply {
                arguments = Bundle().apply { putString(ARG_PACKAGE_NAME, packageName) }
            }
    }
}
