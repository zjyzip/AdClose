package com.close.hook.ads.ui.debug

import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import com.close.hook.ads.R
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.data.repository.AppRepository
import com.close.hook.ads.databinding.ActivityPerformanceBinding
import com.close.hook.ads.ui.activity.BaseActivity
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis

@RequiresApi(Build.VERSION_CODES.N)
class PerformanceActivity : BaseActivity() {

    private lateinit var binding: ActivityPerformanceBinding
    private lateinit var appRepository: AppRepository

    private val javaHeapEntries = mutableListOf<Entry>()
    private val nativeHeapEntries = mutableListOf<Entry>()
    private val pssUsageEntries = mutableListOf<Entry>()
    private val threadCountEntries = mutableListOf<Entry>()
    private val gcCountEntries = mutableListOf<Entry>()
    private val fpsEntries = mutableListOf<Entry>()
    private val chartLabels = mutableListOf<String>()

    private val testRepeatTimes = 3

    private var totalInstalledAppsTimeMs = 0L
    private var totalFilteredAppsTimeMs = 0L

    private var fpsFrameTimes = mutableListOf<Long>()
    private var fpsMeasurementActive = false
    private var lastFrameNanos: Long = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (fpsMeasurementActive) {
                if (lastFrameNanos != 0L) {
                    val elapsedNanos = frameTimeNanos - lastFrameNanos
                    fpsFrameTimes.add(elapsedNanos)
                }
                lastFrameNanos = frameTimeNanos
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPerformanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appRepository = AppRepository(packageManager, this)
        setupPerformanceChart()

        binding.runTestButton.setOnClickListener {
            lifecycleScope.launch {
                runAllPerformanceTests()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun setupPerformanceChart() {
        with(binding.memoryChart) {
            setTouchEnabled(true)
            setScaleEnabled(true)
            isDragEnabled = true
            setPinchZoom(true)
            description.isEnabled = false
            setExtraOffsets(5f, 10f, 10f, 25f)

            legend.apply {
                isWordWrapEnabled = true
                form = Legend.LegendForm.LINE
                textSize = 10f
                textColor = Color.BLACK
                orientation = Legend.LegendOrientation.HORIZONTAL
                verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
                horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                setDrawInside(false)
            }

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                granularity = 1f
                valueFormatter = IndexAxisValueFormatter(chartLabels)
                setDrawGridLines(false)
                textSize = 12f
                textColor = Color.BLACK
            }

            axisLeft.apply {
                axisMinimum = 0f
                textSize = 12f
                textColor = Color.BLACK
            }

            axisRight.isEnabled = false
        }
    }

    private suspend fun runAllPerformanceTests() {
        clearAllPerformanceData()

        totalInstalledAppsTimeMs = 0L
        totalFilteredAppsTimeMs = 0L

        binding.logView.text = ""
        log("🚀 开始性能测试...\n")

        repeat(testRepeatTimes) { index ->
            val testRunId = index + 1
            log("--- ▶️ 第 $testRunId 次测试开始 ---\n")
            System.gc()
            delay(300)

            startFpsMeasurement()

            val apps: List<AppInfo>
            val installedTime = measureTimeMillis {
                apps = appRepository.getInstalledApps()
            }
            totalInstalledAppsTimeMs += installedTime
            log("  📦 获取应用数量: ${apps.size}")

            val filteredApps: List<AppInfo>
            val filteredTime = measureTimeMillis {
                filteredApps = appRepository.getFilteredAndSortedApps(
                    apps,
                    Pair(getString(R.string.filter_configured), listOf(getString(R.string.filter_configured))),
                    "",
                    false
                )
            }
            totalFilteredAppsTimeMs += filteredTime
            log("  📑 过滤后应用数量: ${filteredApps.size}")

            val averageFps = stopFpsMeasurementAndCalculate()

            val currentThreadCount = getCurrentThreadCount()
            val memoryMetrics = getMemoryMetrics()
            val gcCollectionCount = getGcCollectionCount()

            chartLabels.add("Run $testRunId")
            javaHeapEntries.add(Entry(index.toFloat(), memoryMetrics.javaHeapMb.toFloat()))
            nativeHeapEntries.add(Entry(index.toFloat(), memoryMetrics.nativeHeapMb.toFloat()))
            pssUsageEntries.add(Entry(index.toFloat(), memoryMetrics.pssMb.toFloat()))
            threadCountEntries.add(Entry(index.toFloat(), currentThreadCount.toFloat()))
            gcCountEntries.add(Entry(index.toFloat(), gcCollectionCount.toFloat()))
            fpsEntries.add(Entry(index.toFloat(), averageFps))

            log("""
                --- ✅ 第 $testRunId 次测试结果 ---
                ⏱ 获取应用耗时: ${installedTime}ms
                ⏱ 过滤用时: ${filteredTime}ms
                🧠 Java Heap: ${memoryMetrics.javaHeapMb}MB
                🧠 Native Heap: ${memoryMetrics.nativeHeapMb}MB
                🧠 PSS (Physical): ${memoryMetrics.pssMb}MB
                🧵 线程数量: $currentThreadCount
                ♻️ GC 次数: $gcCollectionCount
                ⚡️ 平均 FPS: ${averageFps.roundToInt()}
            """.trimIndent())

            delay(1000)
            log("--- 第 $testRunId 次测试结束 ---\n")
        }

        updatePerformanceChart()
        val summary = """
            --- 🎯 性能测试总结 ---
            平均获取已安装应用耗时: ${totalInstalledAppsTimeMs / testRepeatTimes}ms
            平均过滤和排序应用耗时: ${totalFilteredAppsTimeMs / testRepeatTimes}ms
            -----------------------
        """.trimIndent()
        binding.timeSummary.text = summary
        log(summary)
        log("\n🚀 性能测试完成。")
    }

    private fun clearAllPerformanceData() {
        javaHeapEntries.clear()
        nativeHeapEntries.clear()
        pssUsageEntries.clear()
        threadCountEntries.clear()
        gcCountEntries.clear()
        fpsEntries.clear()
        chartLabels.clear()
        binding.memoryChart.clear()
        binding.memoryChart.invalidate()
    }

    private fun updatePerformanceChart() {
        fun createDataSet(entries: List<Entry>, label: String, color: Int): LineDataSet {
            return LineDataSet(entries, label).apply {
                setDrawCircles(true)
                setDrawValues(true)
                circleRadius = 5f
                valueTextSize = 11f
                this.color = color
                lineWidth = 2.5f
                setDrawCircleHole(false)
                mode = LineDataSet.Mode.CUBIC_BEZIER
            }
        }

        val dataSets = mutableListOf<ILineDataSet>(
            createDataSet(javaHeapEntries, "Java Heap (MB)", Color.parseColor("#1E88E5")),
            createDataSet(nativeHeapEntries, "Native Heap (MB)", Color.parseColor("#6D4C41")),
            createDataSet(pssUsageEntries, "PSS (Physical) (MB)", Color.parseColor("#00897B")),
            createDataSet(threadCountEntries, "线程数量", Color.parseColor("#43A047")),
            createDataSet(gcCountEntries, "GC 次数", Color.parseColor("#FB8C00")),
            createDataSet(fpsEntries, "平均 FPS", Color.parseColor("#9C27B0"))
        )

        binding.memoryChart.data = LineData(dataSets)
        binding.memoryChart.invalidate()
    }

    data class MemoryMetrics(val javaHeapMb: Int, val nativeHeapMb: Int, val pssMb: Int)

    private fun getMemoryMetrics(): MemoryMetrics {
        val pid = Process.myPid()
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val processMemoryInfo = am.getProcessMemoryInfo(intArrayOf(pid))

        if (processMemoryInfo != null && processMemoryInfo.isNotEmpty()) {
            val debugMemoryInfo = processMemoryInfo[0]
            val javaHeapMb = ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024f))
            val nativeHeapMb = debugMemoryInfo.nativePss / 1024f
            val pssMb = debugMemoryInfo.totalPss / 1024f

            return MemoryMetrics(javaHeapMb.roundToInt(), nativeHeapMb.roundToInt(), pssMb.roundToInt())
        }

        Log.w("PerformanceActivity", "Failed to get process memory info, using Debug.getNativeHeapAllocatedSize() as fallback.")
        val javaHeapMb = ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024f)).roundToInt()
        val nativeHeapMb = (Debug.getNativeHeapAllocatedSize() / (1024 * 1024f)).roundToInt()
        return MemoryMetrics(javaHeapMb, nativeHeapMb, 0)
    }

    private fun getCurrentThreadCount(): Int {
        val pid = Process.myPid()
        val taskDir = File("/proc/$pid/task")
        return try {
            taskDir.list()?.size ?: Thread.getAllStackTraces().size
        } catch (e: Exception) {
            Log.e("PerformanceActivity", "Error reading thread count from /proc: ${e.message}", e)
            Thread.getAllStackTraces().size
        }
    }

    private fun log(message: String) {
        Handler(Looper.getMainLooper()).post {
            binding.logView.append("$message\n")
            binding.scrollView.post {
                binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    private fun getGcCollectionCount(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val stats = Debug.getRuntimeStats()
            val count = stats["art.gc.gc-count"] ?: "0"
            return count.toIntOrNull() ?: 0
        }
        return 0
    }

    private fun startFpsMeasurement() {
        fpsFrameTimes.clear()
        lastFrameNanos = 0L
        fpsMeasurementActive = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopFpsMeasurementAndCalculate(): Float {
        fpsMeasurementActive = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)

        if (fpsFrameTimes.isEmpty()) {
            return 0f
        }

        val validFrameTimes = if (fpsFrameTimes.size > 1) fpsFrameTimes.drop(1) else fpsFrameTimes

        val totalDurationNanos = validFrameTimes.sum()
        return if (totalDurationNanos > 0) {
            val totalDurationSeconds = totalDurationNanos / 1_000_000_000f
            validFrameTimes.size / totalDurationSeconds
        } else {
            0f
        }
    }
}
