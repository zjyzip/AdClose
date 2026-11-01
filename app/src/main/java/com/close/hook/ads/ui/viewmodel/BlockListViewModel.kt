package com.close.hook.ads.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.data.DataSource
import com.close.hook.ads.data.model.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BlockListViewModel(application: Application) : AndroidViewModel(application) {

    private val dataSource: DataSource = DataSource.getDataSource(application)

    sealed interface Operation {
        data class Removed(val rules: List<Url>) : Operation
        data class Restored(val count: Int) : Operation
        data object Failed : Operation
    }
    private val operations = Channel<Operation>(Channel.BUFFERED)
    val operationEvents = operations.receiveAsFlow()

    fun removeWithUndo(items: List<Url>? = null) = viewModelScope.launch {
        try {
            val removed = dataSource.removeRulesForUndo(items)
            if (removed.isNotEmpty()) operations.send(Operation.Removed(removed))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            operations.send(Operation.Failed)
        }
    }

    fun restoreRemoved(items: List<Url>) = viewModelScope.launch {
        try {
            operations.send(Operation.Restored(dataSource.restoreRules(items)))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            operations.send(Operation.Failed)
        }
    }

    private val _searchQuery = MutableStateFlow("")

    val blackList: StateFlow<List<Url>> = _searchQuery
        .debounce(300L)
        .flatMapLatest { dataSource.searchUrls(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /** Returns true if added, false if the rule already exists. */
    suspend fun addUrlIfAbsent(url: Url): Boolean {
        return dataSource.addUrl(url)
    }

    fun updateUrl(url: Url) = viewModelScope.launch { dataSource.updateUrl(url) }

    fun removeUrl(url: Url) = viewModelScope.launch { dataSource.removeUrl(url) }

    fun removeList(list: List<Url>) = viewModelScope.launch { dataSource.removeList(list) }

    fun removeAll() = viewModelScope.launch { dataSource.removeAll() }

    suspend fun isExist(type: String, url: String): Boolean = dataSource.isExist(type, url)

    fun removeUrlString(type: String, url: String) =
        viewModelScope.launch { dataSource.removeUrlString(type, url) }

    suspend fun addListUrl(list: List<Url>) = dataSource.addListUrl(list)

    suspend fun getAllUrls(): List<Url> = dataSource.getAllUrls()
}
