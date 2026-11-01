package com.close.hook.ads.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.data.database.UrlDatabase
import com.close.hook.ads.manager.ConnectionState
import com.close.hook.ads.manager.ServiceManager
import com.close.hook.ads.preference.HookPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    data class Overview(
        val localRules: Int? = null,
        val subscriptionCount: Int = 0,
        val enabledSubscriptions: Int = 0,
        val subscriptionsWithErrors: Int = 0,
        val configuredApps: Int? = null
    )

    private val db = UrlDatabase.getDatabase(application)
    private val configuredPrefixes = listOf(
        "switch_one_", "switch_two_", "switch_three_", "switch_four_", "switch_five_",
        "switch_six_", "switch_seven_", "switch_eight_", "switch_nine_", "overall_hook_enabled_"
    )

    private val configuredApps = combine(
        ServiceManager.connectionState, HookPrefs.generalSettingsFlow
    ) { connection, _ -> connection }
        .mapLatest { connection ->
            if (connection !is ConnectionState.Connected) null
            else HookPrefs.getAll().entries.mapNotNull { (key, value) ->
                if (value != true) return@mapNotNull null
                val prefix = configuredPrefixes.firstOrNull(key::startsWith) ?: return@mapNotNull null
                key.removePrefix(prefix).takeIf { it.isNotBlank() && it != "global" }
            }.toSet().size
        }
        .flowOn(Dispatchers.IO)
        .catch { emit(null) }

    val overview = combine(db.urlDao.observeCount(), db.subscriptionSourceDao.observeAll(), configuredApps) {
        rules, subscriptions, apps ->
        Overview(rules, subscriptions.size, subscriptions.count { it.enabled },
            subscriptions.count { it.enabled && !it.lastError.isNullOrBlank() }, apps)
    }.catch { emit(Overview()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Overview())
}
