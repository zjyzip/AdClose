package com.close.hook.ads.data.repository

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.close.hook.ads.data.model.RuleMatch
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.data.RuleSnapshot
import com.close.hook.ads.provider.UrlContentProvider
import java.util.concurrent.atomic.AtomicBoolean

object RuleRepository {

    private const val LOG_PREFIX = "[RuleRepository]"
    private const val MIN_REFRESH_INTERVAL_MS = 5_000L

    private val contentUri: Uri = Uri.Builder()
        .scheme("content")
        .authority(UrlContentProvider.AUTHORITY)
        .appendPath(UrlContentProvider.URL_TABLE_NAME)
        .build()

    @Volatile private var appContext: Context? = null
    @Volatile private var snapshot: RuleSnapshot = RuleSnapshot.EMPTY
    @Volatile private var lastRefreshAt: Long = 0L

    private val initialized = AtomicBoolean(false)
    private val dirty = AtomicBoolean(true)
    private val refreshLock = Any()

    private val observer by lazy {
        object : ContentObserver(
            null
        ) {
            override fun onChange(selfChange: Boolean) {
                dirty.set(true)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                dirty.set(true)
            }
        }
    }

    fun init(context: Context) {
        appContext = context
    }

    fun shouldBlock(requestValue: String, host: String?): RuleMatch {
        ensureFreshSnapshot(force = false)
        return snapshot.match(requestValue = requestValue, host = host)
    }

    private fun ensureFreshSnapshot(force: Boolean) {
        val rawContext = appContext ?: return

        val now = System.currentTimeMillis()
        val shouldRefresh = force || dirty.get() || (now - lastRefreshAt >= MIN_REFRESH_INTERVAL_MS)
        if (!shouldRefresh) return

        synchronized(refreshLock) {
            val freshNow = System.currentTimeMillis()
            val stillNeedRefresh = force || dirty.get() || (freshNow - lastRefreshAt >= MIN_REFRESH_INTERVAL_MS)
            if (!stillNeedRefresh) return

            if (initialized.compareAndSet(false, true)) {
                try {
                    val safeContext = rawContext.applicationContext ?: rawContext
                    safeContext.contentResolver.registerContentObserver(contentUri, true, observer)
                } catch (e: Throwable) {
                    Log.w(LOG_PREFIX, "Failed to register observer: ${e.message}")
                    initialized.set(false)
                }
            }

            runCatching {
                val safeContext = rawContext.applicationContext ?: rawContext
                val rules = loadAllRules(safeContext)
                snapshot = RuleSnapshot.fromUrls(rules)
                dirty.set(false)
            }.onFailure { error ->
                Log.w(LOG_PREFIX, "Failed to refresh rule snapshot: ${error.message}")
            }
            lastRefreshAt = freshNow
        }
    }

    private fun loadAllRules(context: Context): List<Url> {
        val result = ArrayList<Url>()
        context.contentResolver.query(
            contentUri,
            arrayOf(Url.URL_TYPE, Url.URL_ADDRESS),
            null, null, null
        )?.use { cursor ->
            val typeIndex = cursor.getColumnIndex(Url.URL_TYPE)
            val urlIndex = cursor.getColumnIndex(Url.URL_ADDRESS)
            if (typeIndex == -1 || urlIndex == -1) return emptyList()
            while (cursor.moveToNext()) {
                val type = cursor.getString(typeIndex).orEmpty()
                val url = cursor.getString(urlIndex).orEmpty()
                result += Url(type = type, url = url)
            }
        }
        return result
    }
}
