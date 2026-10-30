package com.close.hook.ads.data.repository

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.util.Log
import com.close.hook.ads.data.model.RuleMatch
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.data.RuleSnapshot
import com.close.hook.ads.provider.UrlContentProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object RuleRepository {

    private const val LOG_PREFIX = "[RuleRepository]"
    private const val MIN_REFRESH_INTERVAL_MS = 5_000L
    private const val INITIAL_LOAD_WAIT_MS = 250L
    private const val FAILURE_RETRY_DELAY_MS = 1_000L

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
    // Guards only the background DB load — not the hot shouldBlock() path
    private val refreshing = AtomicBoolean(false)
    private val initialLoadComplete = CountDownLatch(1)
    private val retryNotBefore = AtomicLong(0L)

    private val backgroundExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AdClose-RuleRefresh").apply { isDaemon = true }
    }

    private val observer by lazy {
        object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                dirty.set(true)
                scheduleRefresh()
            }
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                dirty.set(true)
                scheduleRefresh()
            }
        }
    }

    fun init(context: Context) {
        appContext = context
        if (initialized.compareAndSet(false, true)) {
            try {
                val safeContext = context.applicationContext ?: context
                safeContext.contentResolver.registerContentObserver(contentUri, true, observer)
            } catch (e: Throwable) {
                Log.w(LOG_PREFIX, "Failed to register content observer: ${e.message}")
                initialized.set(false)
            }
        }
        scheduleRefresh()
    }

    // HOT PATH: pure volatile read — no lock, no IPC, no allocation
    fun shouldBlock(requestValue: String, host: String?): RuleMatch {
        if (initialLoadComplete.count > 0L) {
            scheduleRefresh()
            try {
                initialLoadComplete.await(INITIAL_LOAD_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        val now = System.currentTimeMillis()
        if (dirty.get() || now - lastRefreshAt >= MIN_REFRESH_INTERVAL_MS) {
            scheduleRefresh()
        }
        return snapshot.match(requestValue = requestValue, host = host)
    }

    fun forceRefresh() {
        dirty.set(true)
        retryNotBefore.set(0L)
        scheduleRefresh()
    }

    private fun scheduleRefresh() {
        if (System.currentTimeMillis() < retryNotBefore.get()) return
        if (refreshing.compareAndSet(false, true)) {
            backgroundExecutor.execute {
                try {
                    doRefresh()
                } finally {
                    refreshing.set(false)
                    initialLoadComplete.countDown()
                }
            }
        }
    }

    private fun doRefresh() {
        val rawContext = appContext ?: return
        dirty.set(false)
        runCatching {
            val safeContext = rawContext.applicationContext ?: rawContext
            val rules = loadAllRules(safeContext)
            snapshot = RuleSnapshot.fromUrls(rules)
            lastRefreshAt = System.currentTimeMillis()
            retryNotBefore.set(0L)
        }.onFailure { error ->
            dirty.set(true)
            retryNotBefore.set(System.currentTimeMillis() + FAILURE_RETRY_DELAY_MS)
            Log.w(LOG_PREFIX, "Failed to refresh rule snapshot: ${error.message}")
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
