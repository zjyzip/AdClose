package com.close.hook.ads.debug.fragment

import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.close.hook.ads.R
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.data.model.AppFilterState
import com.close.hook.ads.data.repository.AppRepository
import com.close.hook.ads.databinding.FragmentAppRepoPerformanceBinding
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis

@RequiresApi(Build.VERSION_CODES.N)
class AppRepoPerformanceFragment : Fragment() {

    private var _binding: FragmentAppRepoPerformanceBinding? = null
    private val binding get() = _binding!!

    private lateinit var appRepository: AppRepository
    private lateinit var activityManager: ActivityManager

    private val chartDataEntries = mutableMapOf<ChartMetric, MutableList<Entry>>()
    private val chartLabels = mutableListOf<String>()

    private val TEST_REPEAT_TIMES = 3

    private var fpsFrameTimes = mutableListOf<Long>()
    private var isFpsMeasurementActive = false
    private var lastFrameNanos: Long = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (isFpsMeasurementActive) {
                if (lastFrameNanos != 0L) {
                    val elapsedNanos = frameTimeNanos - lastFrameNanos
                    fpsFrameTimes.add(elapsedNanos)
                }
                lastFrameNanos = frameTimeNanos
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    enum class ChartMetric(val label: String, val color: Int) {
        JAVA_HEAP("Java 堆 (MB)", Color.parseColor("#1E88E5")),
        NATIVE_HEAP("Native 堆 (MB)", Color.parseColor("#6D4C41")),
        PSS_USAGE("PSS (物理内存) (MB)", Color.parseColor("#00897B")),
        THREAD_COUNT("线程数量", Color.parseColor("#43A047")),
        GC_COUNT("GC 次数", Color.parseColor("#FB8C00")),
        FPS("平均 UI 帧率 (FPS)", Color.parseColor("#9C27B0"))
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppRepoPerformanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        appRepository = AppRepository(requireContext().packageManager)
        activityManager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        ChartMetric.values().forEach { metric ->
            chartDataEntries[metric] = mutableListOf()
        }

        setupPerformanceChart()
        setupListeners()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        _binding = null
    }

    private fun setupListeners() {
        binding.runTestButton.setOnClickListener {
            it.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    runAllPerformanceTests()
                } finally {
                    it.isEnabled = true
                }
            }
        }
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
                horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
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

        var totalGetAllAppsTimeMs = 0L
        var totalFilterConfiguredAppsTimeMs = 0L

        log("🚀 开始应用数据获取与过滤性能测试...\n")
        binding.timeSummary.text = ""

        repeat(TEST_REPEAT_TIMES) { index ->
            val testRunId = index + 1
            log("--- ▶️ 第 $testRunId 次测试开始 ---\n")

            System.gc()
            delay(300)

            startFpsMeasurement()

            val allApps: List<AppInfo>
            val getAllAppsTime = measureTimeMillis {
                allApps = appRepository.getAllAppsFlow().first()
            }
            totalGetAllAppsTimeMs += getAllAppsTime
            log("  📦 获取所有应用耗时: ${getAllAppsTime}ms (数量: ${allApps.size})")

            val filteredApps: List<AppInfo>
            val filterConfiguredAppsTime = measureTimeMillis {
                filteredApps = appRepository.filterAndSortApps(
                    allApps,
                    AppFilterState(
                        appType = "all",
                        filterOrder = R.string.sort_by_app_name,
                        isReverse = false,
                        keyword = "",
                        showConfigured = true,
                        showUpdated = false,
                        showDisabled = false
                    )
                )
            }
            totalFilterConfiguredAppsTimeMs += filterConfiguredAppsTime
            log("  📑 过滤已配置应用耗时: ${filterConfiguredAppsTime}ms (数量: ${filteredApps.size})")

            val averageFps = stopFpsMeasurementAndCalculate()

            val currentThreadCount = withContext(Dispatchers.Default) { getCurrentThreadCount() }
            val memoryMetrics = withContext(Dispatchers.Default) { getMemoryMetrics() }
            val gcCollectionCount = withContext(Dispatchers.Default) { getGcCollectionCount() }

            chartLabels.add("运行 $testRunId")
            chartDataEntries[ChartMetric.JAVA_HEAP]?.add(Entry(index.toFloat(), memoryMetrics.javaHeapMb.toFloat()))
            chartDataEntries[ChartMetric.NATIVE_HEAP]?.add(Entry(index.toFloat(), memoryMetrics.nativeHeapMb.toFloat()))
            chartDataEntries[ChartMetric.PSS_USAGE]?.add(Entry(index.toFloat(), memoryMetrics.pssMb.toFloat()))
            chartDataEntries[ChartMetric.THREAD_COUNT]?.add(Entry(index.toFloat(), currentThreadCount.toFloat()))
            chartDataEntries[ChartMetric.GC_COUNT]?.add(Entry(index.toFloat(), gcCollectionCount.toFloat()))
            chartDataEntries[ChartMetric.FPS]?.add(Entry(index.toFloat(), averageFps))

            log("""
                --- ✅ 第 $testRunId 次测试结果 ---
                🧠 Java 堆: ${memoryMetrics.javaHeapMb}MB
                🧠 Native 堆: ${memoryMetrics.nativeHeapMb}MB
                🧠 PSS (物理内存): ${memoryMetrics.pssMb}MB
                🧵 线程数量: $currentThreadCount
                ♻️ GC 次数: $gcCollectionCount
                ⚡️ 平均 UI 帧率: ${averageFps.roundToInt()} FPS
            """.trimIndent())

            delay(1000)
            log("--- 第 $testRunId 次测试结束 ---\n")
        }

        updatePerformanceChart()

        val summary = """
            --- 🎯 性能测试总结 ---
            平均获取所有应用耗时: ${totalGetAllAppsTimeMs / TEST_REPEAT_TIMES}ms
            平均过滤已配置应用耗时: ${totalFilterConfiguredAppsTimeMs / TEST_REPEAT_TIMES}ms
            -----------------------
        """.trimIndent()
        binding.timeSummary.text = summary
        log(summary)
        log("\n🚀 应用数据获取与过滤性能测试完成。")
    }

    private fun clearAllPerformanceData() {
        chartDataEntries.values.forEach { it.clear() }
        chartLabels.clear()
        binding.memoryChart.data = null
        binding.memoryChart.notifyDataSetChanged()
        binding.memoryChart.invalidate()
        binding.logView.text = ""
    }

    private fun updatePerformanceChart() {
        val dataSets = mutableListOf<ILineDataSet>()
        ChartMetric.values().forEach { metric ->
            val entries = chartDataEntries[metric]
            if (!entries.isNullOrEmpty()) {
                val dataSet = LineDataSet(entries, metric.label).apply {
                    setDrawCircles(true)
                    setDrawValues(true)
                    circleRadius = 5f
                    valueTextSize = 11f
                    color = metric.color
                    lineWidth = 2.5f
                    setDrawCircleHole(false)
                    mode = LineDataSet.Mode.CUBIC_BEZIER
                }
                dataSets.add(dataSet)
            }
        }
        binding.memoryChart.data = LineData(dataSets)
        binding.memoryChart.xAxis.valueFormatter = IndexAxisValueFormatter(chartLabels)
        binding.memoryChart.notifyDataSetChanged()
        binding.memoryChart.invalidate()
    }

    data class MemoryMetrics(val javaHeapMb: Int, val nativeHeapMb: Int, val pssMb: Int)

    private fun getMemoryMetrics(): MemoryMetrics {
        val pid = Process.myPid()
        val processMemoryInfo = activityManager.getProcessMemoryInfo(intArrayOf(pid))

        if (processMemoryInfo.isNotEmpty()) {
            val debugMemoryInfo = processMemoryInfo[0]
            val javaHeapMb = ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024f)).roundToInt()
            val nativeHeapMb = debugMemoryInfo.nativePss / 1024
            val pssMb = debugMemoryInfo.totalPss / 1024

            return MemoryMetrics(javaHeapMb, nativeHeapMb, pssMb)
        }

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
            Thread.getAllStackTraces().size
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
        isFpsMeasurementActive = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopFpsMeasurementAndCalculate(): Float {
        isFpsMeasurementActive = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)

        if (fpsFrameTimes.isEmpty()) {
            return 0f
        }

        val validFrameTimes = if (fpsFrameTimes.size > 1) fpsFrameTimes.drop(1) else fpsFrameTimes

        val totalDurationNanos = validFrameTimes.sum()
        return if (validFrameTimes.size >= 1 && totalDurationNanos > 0) {
            val totalDurationSeconds = totalDurationNanos / 1_000_000_000f
            validFrameTimes.size / totalDurationSeconds
        } else {
            0f
        }
    }

    private fun log(message: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            binding.logView.append("$message\n")
            binding.scrollView.post {
                binding.scrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }
}
