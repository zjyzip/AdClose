package com.close.hook.ads.debug.fragment

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Choreographer
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.R
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.data.repository.AppRepository
import com.close.hook.ads.databinding.FragmentAdapterPerformanceBinding
import com.close.hook.ads.ui.adapter.AppsAdapter
import com.close.hook.ads.util.CacheDataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.system.measureTimeMillis

class AdapterPerformanceFragment : Fragment() {

    private var _binding: FragmentAdapterPerformanceBinding? = null
    private val binding get() = _binding!!

    private lateinit var appRepository: AppRepository
    private lateinit var appsAdapter: AppsAdapter
    private lateinit var layoutManager: LinearLayoutManager

    private var currentTestJob: Job? = null

    private companion object {
        const val TEST_REPEAT_TIMES = 3
        const val SCROLL_DURATION_MS = 1000
        const val SCROLL_SPEED_FACTOR = 100f
        const val DELAY_AFTER_SUBMIT_MS = 200L
        const val DELAY_BETWEEN_SCROLLS_MS = 300L
        const val DELAY_AFTER_DIFF_UTIL_MS = 500L
        const val HEAP_MB_FACTOR = 1024 * 1024f
        const val KB_FACTOR = 1024f
        const val NS_TO_MS_FACTOR = 1_000_000f
        const val NS_TO_S_FACTOR = 1_000_000_000f
    }

    private val totalSubmitListTimeMs = AtomicLong(0)
    private val totalScrollFps = AtomicLong(0)
    private val totalDiffUtilTimeMs = AtomicLong(0)
    private val totalIconLoadTimeMs = AtomicLong(0)
    private val totalIconDataSizeBytes = AtomicLong(0)
    private val totalIconMemoryBytes = AtomicLong(0)
    private val iconCount = AtomicLong(0)
    private val totalInitialHeapMb = AtomicLong(0)
    private val totalFinalHeapMb = AtomicLong(0)
    private var initialAppListSize = 0

    private val fpsMeter = FpsMeter()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAdapterPerformanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        appRepository = AppRepository(requireContext().packageManager)
        setupRecyclerView()
        setupListeners()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        currentTestJob?.cancel()
        lifecycleScope.launch(Dispatchers.IO) {
            CacheDataManager.clearAllCache(requireContext())
        }
        _binding = null
        fpsMeter.stop()
    }

    private fun setupRecyclerView() {
        appsAdapter = AppsAdapter(
            onItemClickListener = object : AppsAdapter.OnItemClickListener {
                override fun onItemClick(appInfo: AppInfo, icon: Drawable?) {}
                override fun onItemLongClick(appInfo: AppInfo, icon: Drawable?) = Unit
            },
            onIconLoadListener = object : AppsAdapter.OnIconLoadListener {
                override fun onIconLoaded(loadTimeMs: Long, sizeBytes: Int, width: Int, height: Int, memoryBytes: Int) {
                    totalIconLoadTimeMs.addAndGet(loadTimeMs)
                    totalIconDataSizeBytes.addAndGet(sizeBytes.toLong())
                    totalIconMemoryBytes.addAndGet(memoryBytes.toLong())
                    iconCount.incrementAndGet()
                }
            }
        )

        layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.apply {
            adapter = appsAdapter
            layoutManager = this@AdapterPerformanceFragment.layoutManager
            setHasFixedSize(true)
            setItemViewCacheSize(20)
            isNestedScrollingEnabled = false
        }
    }

    private fun setupListeners() {
        binding.runAdapterTestButton.setOnClickListener { button ->
            button.isEnabled = false
            currentTestJob = viewLifecycleOwner.lifecycleScope.launch {
                try {
                    runAllAdapterPerformanceTests()
                } finally {
                    button.isEnabled = true
                    currentTestJob = null
                }
            }
        }
    }

    private suspend fun runAllAdapterPerformanceTests() {
        resetPerformanceMetrics()
        binding.logViewAdapter.text = ""
        binding.timeSummaryAdapter.text = ""

        log("🚀 开始 AppsAdapter 性能测试...\n")
        log("""
            --- 测试配置 ---
              重复次数: $TEST_REPEAT_TIMES
              每次滚动持续时间: ${SCROLL_DURATION_MS}ms
            -----------------
        """.trimIndent())

        repeat(TEST_REPEAT_TIMES) { index ->
            val testRunId = index + 1
            log("--- ▶️ 第 $testRunId 次测试开始 ---")

            performPreTestSetup()

            val allApps = fetchAppsForTest()

            val submitTime = measureSubmitListTime(allApps)
            totalSubmitListTimeMs.addAndGet(submitTime)
            log("  ⏱ 初始 submitList 耗时: ${submitTime}ms")

            delay(DELAY_AFTER_SUBMIT_MS)

            measureAndLogScrollingFps()

            measureAndLogDiffUtilPerformance(allApps)

            performPostTestCleanup(testRunId)

            log("--- 第 $testRunId 次测试结束 ---\n")
        }

        displayOverallSummary()
        appsAdapter.submitList(emptyList())
        log("\n🚀 AppsAdapter 性能测试完成。")
    }

    private suspend fun performPreTestSetup() {
        log("  🧹 清理所有应用缓存并尝试触发 GC...")
        withContext(Dispatchers.IO) { CacheDataManager.clearAllCache(requireContext()) }
        System.gc()
        delay(100)
        val initialHeapMb = getUsedMemoryMb()
        totalInitialHeapMb.addAndGet(initialHeapMb.toLong())
        log("  📋 测试前 Java 堆内存: ${"%.2f".format(initialHeapMb)}MB")
    }

    private suspend fun fetchAppsForTest(): List<AppInfo> {
        log("  📋 正在获取应用列表用于 Adapter 加载...")
        return withContext(Dispatchers.IO) {
            val allApps = appRepository.getAllAppsFlow().first()
            initialAppListSize = allApps.size
            log("  📦 获取到 ${initialAppListSize} 个应用。")
            allApps
        }
    }

    private suspend fun measureSubmitListTime(apps: List<AppInfo>): Long =
        measureTimeMillis {
            withContext(Dispatchers.Main) {
                appsAdapter.submitList(apps)
            }
        }

    private suspend fun measureAndLogScrollingFps() {
        log("  🔄 正在模拟上下间接滚动并测量 FPS...")
        val scrollFpsDown = measureScrollFps(true)
        totalScrollFps.addAndGet(scrollFpsDown.roundToInt().toLong())
        log("  ⚡️ 向下滚动平均 FPS: ${scrollFpsDown.roundToInt()}")
        delay(DELAY_BETWEEN_SCROLLS_MS)
        val scrollFpsUp = measureScrollFps(false)
        totalScrollFps.addAndGet(scrollFpsUp.roundToInt().toLong())
        log("  ⚡️ 向上滚动平均 FPS: ${scrollFpsUp.roundToInt()}")
    }

    private suspend fun measureAndLogDiffUtilPerformance(allApps: List<AppInfo>) {
        log("  📊 正在模拟数据更新并测试 DiffUtil 性能...")
        val modifiedApps = createModifiedAppList(allApps)
        val diffUtilTime = measureSubmitListTime(modifiedApps)
        totalDiffUtilTimeMs.addAndGet(diffUtilTime)
        log("  ⏱ DiffUtil submitList 耗时: ${diffUtilTime}ms (原列表: $initialAppListSize, 新列表: ${modifiedApps.size})")
        delay(DELAY_AFTER_DIFF_UTIL_MS)
    }

    private suspend fun performPostTestCleanup(testRunId: Int) {
        val finalHeapMb = getUsedMemoryMb()
        totalFinalHeapMb.addAndGet(finalHeapMb.toLong())
        log("  📋 测试后 Java 堆内存: ${"%.2f".format(finalHeapMb)}MB")

        val currentAvgIconLoadTime = totalIconLoadTimeMs.get().toFloat() / (iconCount.get().takeIf { it > 0 } ?: 1)
        val currentAvgIconDataSizeKb = totalIconDataSizeBytes.get().toFloat() / ((iconCount.get().takeIf { it > 0 } ?: 1) * KB_FACTOR)
        val currentAvgIconMemUsageKb = totalIconMemoryBytes.get().toFloat() / ((iconCount.get().takeIf { it > 0 } ?: 1) * KB_FACTOR)
        val currentHeapChangeMb = finalHeapMb - (totalInitialHeapMb.get().toFloat() / testRunId)

        log("""
            --- ✅ 第 $testRunId 次测试结果 ---
            ⏱ 初始 submitList 耗时: ${"%.2f".format(totalSubmitListTimeMs.get().toFloat() / testRunId)}ms
            ⚡️ 平均滚动 FPS: ${ (totalScrollFps.get().toFloat() / (testRunId * 2)).roundToInt()}
            🖼 平均图标加载耗时: ${"%.2f".format(currentAvgIconLoadTime)}ms
            💾 平均图标原始数据大小: ${"%.2f".format(currentAvgIconDataSizeKb)}KB
            💡 平均图标内存占用: ${"%.2f".format(currentAvgIconMemUsageKb)}KB
            📈 Java 堆内存变化: ${"%.2f".format(currentHeapChangeMb)}MB
        """.trimIndent())
        delay(500)
    }

    private fun createModifiedAppList(originalApps: List<AppInfo>): List<AppInfo> {
        if (originalApps.isEmpty()) {
            return emptyList()
        }

        val modifiedApps = originalApps.toMutableList()
        val originalSize = modifiedApps.size

        val numToRemove = minOf(Random.nextInt(1, 11), (originalSize * 0.3).roundToInt().coerceAtLeast(1))
        repeat(numToRemove) {
            if (modifiedApps.isNotEmpty()) {
                modifiedApps.removeAt(Random.nextInt(modifiedApps.size))
            }
        }

        val numToModify = minOf(Random.nextInt(1, 6), modifiedApps.size.coerceAtLeast(1))
        repeat(numToModify) {
            if (modifiedApps.isNotEmpty()) {
                val indexToChange = Random.nextInt(modifiedApps.size)
                modifiedApps[indexToChange] = modifiedApps[indexToChange].let { originalApp ->
                    originalApp.copy(
                        appName = "${originalApp.appName} (mod-${Random.nextInt(100)})",
                        versionName = "${originalApp.versionName}.${Random.nextInt(10)}"
                    )
                }
            }
        }

        val numToAdd = Random.nextInt(1, 6)
        repeat(numToAdd) {
            val dummyPackageName = "com.example.dummyapp.${System.currentTimeMillis()}_${Random.nextInt(1000)}"
            modifiedApps.add(
                AppInfo(
                    appName = "Dummy App ${Random.nextInt(1000)}",
                    packageName = dummyPackageName,
                    versionName = "1.0",
                    versionCode = 1,
                    firstInstallTime = System.currentTimeMillis(),
                    lastUpdateTime = System.currentTimeMillis(),
                    size = 100000L,
                    targetSdk = 30,
                    minSdk = 21,
                    isAppEnable = 1,
                    isEnable = 0,
                    isSystem = false
                )
            )
        }

        modifiedApps.shuffle(Random(System.currentTimeMillis()))

        return modifiedApps
    }

    private fun resetPerformanceMetrics() {
        totalSubmitListTimeMs.set(0)
        totalScrollFps.set(0)
        totalDiffUtilTimeMs.set(0)
        totalIconLoadTimeMs.set(0)
        totalIconDataSizeBytes.set(0)
        totalIconMemoryBytes.set(0)
        iconCount.set(0)
        totalInitialHeapMb.set(0)
        totalFinalHeapMb.set(0)
        initialAppListSize = 0
    }

    private fun displayOverallSummary() {
        val avgSubmitListTime = totalSubmitListTimeMs.get().toFloat() / TEST_REPEAT_TIMES
        val avgScrollFps = totalScrollFps.get().toFloat() / (TEST_REPEAT_TIMES * 2)
        val avgDiffUtilTime = totalDiffUtilTimeMs.get().toFloat() / TEST_REPEAT_TIMES

        val actualIconCount = iconCount.get().takeIf { it > 0 } ?: 1
        val avgIconLoadTime = totalIconLoadTimeMs.get().toFloat() / actualIconCount
        val avgIconDataSizeKb = totalIconDataSizeBytes.get().toFloat() / (actualIconCount * KB_FACTOR)
        val avgIconMemUsageKb = totalIconMemoryBytes.get().toFloat() / (actualIconCount * KB_FACTOR)
        val avgHeapChangeMb = (totalFinalHeapMb.get().toFloat() - totalInitialHeapMb.get().toFloat()) / TEST_REPEAT_TIMES

        val summary = """
            --- 🎯 AppsAdapter 性能测试总结 (平均 $TEST_REPEAT_TIMES 次运行) ---
            ⏱ 平均初始 submitList 耗时: ${"%.2f".format(avgSubmitListTime)}ms
            ⚡️ 平均滚动 FPS: ${avgScrollFps.roundToInt()}
            ⏱ 平均 DiffUtil submitList 耗时: ${"%.2f".format(avgDiffUtilTime)}ms
            🖼 平均图标加载耗时: ${"%.2f".format(avgIconLoadTime)}ms
            💾 平均图标原始数据大小: ${"%.2f".format(avgIconDataSizeKb)}KB
            💡 平均图标内存占用: ${"%.2f".format(avgIconMemUsageKb)}KB
            📈 平均 Java 堆内存变化: ${"%.2f".format(avgHeapChangeMb)}MB
            ------------------------------------------
        """.trimIndent()
        binding.timeSummaryAdapter.text = summary
        log(summary)
    }

    private fun getUsedMemoryMb(): Float {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / HEAP_MB_FACTOR
    }

    private suspend fun measureScrollFps(scrollDown: Boolean): Float = suspendCancellableCoroutine { continuation ->
        val smoothScroller = object : LinearSmoothScroller(requireContext()) {
            override fun getVerticalSnapPreference(): Int =
                if (scrollDown) SNAP_TO_START else SNAP_TO_END

            override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics?): Float =
                SCROLL_SPEED_FACTOR / (displayMetrics?.densityDpi?.toFloat() ?: 1f)

            override fun calculateTimeForScrolling(dx: Int): Int = SCROLL_DURATION_MS
        }

        val scrollListener = object : RecyclerView.OnScrollListener() {
            private var isSettlingStarted = false

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                when (newState) {
                    RecyclerView.SCROLL_STATE_SETTLING -> {
                        if (!isSettlingStarted) {
                            fpsMeter.start()
                            isSettlingStarted = true
                        }
                    }
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        if (isSettlingStarted) {
                            fpsMeter.stop()
                            recyclerView.removeOnScrollListener(this)
                            if (continuation.isActive) {
                                continuation.resume(fpsMeter.getAverageFps())
                            }
                            isSettlingStarted = false
                        }
                    }
                }
            }
        }

        binding.recyclerView.post {
            if (appsAdapter.itemCount > 0) {
                binding.recyclerView.addOnScrollListener(scrollListener)
                val targetPosition = if (scrollDown) appsAdapter.itemCount - 1 else 0
                smoothScroller.targetPosition = targetPosition
                layoutManager.startSmoothScroll(smoothScroller)
            } else {
                if (continuation.isActive) {
                    continuation.resume(0f)
                }
            }
        }

        continuation.invokeOnCancellation {
            binding.recyclerView.removeOnScrollListener(scrollListener)
            fpsMeter.stop()
        }
    }

    private class FpsMeter {
        private val frameTimes = mutableListOf<Long>()
        private var lastFrameNanos: Long = 0L
        @Volatile private var isActive = false

        private val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (isActive) {
                    if (lastFrameNanos != 0L) {
                        frameTimes.add(frameTimeNanos - lastFrameNanos)
                    }
                    lastFrameNanos = frameTimeNanos
                    Choreographer.getInstance().postFrameCallback(this)
                } else {
                    Choreographer.getInstance().removeFrameCallback(this)
                }
            }
        }

        fun start() {
            synchronized(this) {
                frameTimes.clear()
                lastFrameNanos = 0L
                if (!isActive) {
                    isActive = true
                    Choreographer.getInstance().postFrameCallback(frameCallback)
                }
            }
        }

        fun stop() {
            synchronized(this) {
                if (isActive) {
                    isActive = false
                    Choreographer.getInstance().removeFrameCallback(frameCallback)
                }
            }
        }

        fun getAverageFps(): Float {
            synchronized(this) {
                if (frameTimes.size < 5) return 0f

                val validFrameTimes = if (frameTimes.size > 4) {
                    frameTimes.subList(2, frameTimes.size - 2)
                } else {
                    frameTimes
                }

                val totalDurationNanos = validFrameTimes.sum()
                return if (totalDurationNanos > 0) {
                    validFrameTimes.size / (totalDurationNanos / NS_TO_S_FACTOR)
                } else {
                    0f
                }
            }
        }
    }

    private fun log(message: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            binding.logViewAdapter.append("$message\n")
            binding.scrollViewAdapter.post {
                binding.scrollViewAdapter.fullScroll(View.FOCUS_DOWN)
            }
        }
    }
}
