package com.close.hook.ads.ui.debug

import android.os.Bundle
import android.os.Debug
import android.os.Process
import androidx.appcompat.app.AppCompatActivity
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis
import java.io.File

class PerformanceActivity : BaseActivity() {

    private lateinit var binding: ActivityPerformanceBinding
    private lateinit var repo: AppRepository
    private val heapUsageList = mutableListOf<Entry>()
    private val nativeUsageList = mutableListOf<Entry>()
    private val cpuUsageList = mutableListOf<Entry>()
    private val threadCountList = mutableListOf<Entry>()
    private val labels = mutableListOf<String>()

    private val testTimes = 3
    private var installedTotal = 0L
    private var filteredTotal = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPerformanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = AppRepository(packageManager, this)
        setupChart()

        binding.runTestButton.setOnClickListener {
            lifecycleScope.launch { runTests() }
        }
    }

    private fun setupChart() {
        binding.memoryChart.apply {
            setTouchEnabled(true)
            setScaleEnabled(true)
            isDragEnabled = true
            setPinchZoom(true)
            description.isEnabled = false
            setExtraOffsets(10f, 10f, 10f, 10f)

            legend.apply {
                isWordWrapEnabled = true
                form = Legend.LegendForm.LINE
                textSize = 12f
            }

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                granularity = 1f
                valueFormatter = IndexAxisValueFormatter(labels)
                setDrawGridLines(false)
                textSize = 12f
            }

            axisLeft.apply {
                axisMinimum = 0f
                textSize = 12f
            }

            axisRight.isEnabled = false
        }
    }

    private suspend fun runTests() {
        heapUsageList.clear()
        nativeUsageList.clear()
        cpuUsageList.clear()
        threadCountList.clear()
        labels.clear()
        installedTotal = 0
        filteredTotal = 0
        binding.logView.text = ""

        repeat(testTimes) { index ->
            log("▶️ 开始第 ${index + 1} 次测试...")
            System.gc()
            delay(200)

            val beforeCpu = readCpuUsage()
            val beforeThreads = getThreadCount()
            val apps: List<AppInfo>
            val timeInstalled = measureTimeMillis {
                apps = repo.getInstalledApps()
            }
            installedTotal += timeInstalled
            log("📦 应用数量: ${apps.size}")

            val filteredApps: List<AppInfo>
            val timeFiltered = measureTimeMillis {
                filteredApps = repo.getFilteredAndSortedApps(
                    apps,
                    Pair(getString(R.string.filter_configured), listOf(getString(R.string.filter_configured))),
                    "",
                    false
                )
            }
            filteredTotal += timeFiltered
            log("📑 过滤结果: ${filteredApps.size}")

            val afterCpu = readCpuUsage()
            val afterThreads = getThreadCount()

            val heapAfter = usedHeapMB()
            val nativeAfter = nativeHeapMB()
            val cpuDelta = ((afterCpu - beforeCpu) * 100f).coerceIn(0f, 100f)
            val threadDelta = (afterThreads - beforeThreads).coerceAtLeast(0)

            labels += "Run ${index + 1}"
            heapUsageList += Entry(index.toFloat(), heapAfter.toFloat())
            nativeUsageList += Entry(index.toFloat(), nativeAfter.toFloat())
            cpuUsageList += Entry(index.toFloat(), cpuDelta)
            threadCountList += Entry(index.toFloat(), threadDelta.toFloat())

            log("""
                ✅ 获取应用耗时: ${timeInstalled}ms, 过滤用时: ${timeFiltered}ms
                🧠 Heap: ${heapAfter}MB, Native: ${nativeAfter}MB
                🖥 CPU增量: ${cpuDelta.roundToInt()}%
                🧵 线程增量: $threadDelta
            """.trimIndent())
        }

        updateChart()
        val summary = """
            🎯 平均 getInstalledApps: ${installedTotal / testTimes}ms
            🎯 平均 getFilteredApps: ${filteredTotal / testTimes}ms
        """.trimIndent()

        binding.timeSummary.text = summary
        log(summary)
    }

    private fun updateChart() {
        fun makeDataSet(entries: List<Entry>, label: String, color: Int): LineDataSet {
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

        val dataSets = listOf(
            makeDataSet(heapUsageList, "Java Heap MB", 0xFF1E88E5.toInt()),
            makeDataSet(nativeUsageList, "Native Heap MB", 0xFF6D4C41.toInt()),
            makeDataSet(cpuUsageList, "CPU 使用率增量 %", 0xFF8E24AA.toInt()),
            makeDataSet(threadCountList, "线程数量增量", 0xFF43A047.toInt())
        )

        binding.memoryChart.data = LineData(dataSets)
        binding.memoryChart.invalidate()
    }

    private fun usedHeapMB(): Int {
        val r = Runtime.getRuntime()
        return ((r.totalMemory() - r.freeMemory()) / (1024 * 1024f)).roundToInt()
    }

    private fun nativeHeapMB(): Int {
        return (Debug.getNativeHeapAllocatedSize() / (1024 * 1024f)).roundToInt()
    }

    private fun readCpuUsage(): Float {
        return try {
            val pid = Process.myPid()
            val stat1 = readProcStat()
            val proc1 = readProcPidStat(pid)
            Thread.sleep(400)
            val stat2 = readProcStat()
            val proc2 = readProcPidStat(pid)

            val total = (stat2.total - stat1.total).toFloat()
            val used = (proc2.total - proc1.total).toFloat()
            if (total > 0) used / total else 0f
        } catch (e: Exception) {
            0f
        }
    }

    private data class CpuStat(val total: Long)
    private fun readProcStat(): CpuStat {
        val line = java.io.RandomAccessFile("/proc/stat", "r").use { it.readLine() }
        val toks = line.split("\\s+".toRegex()).drop(1).mapNotNull { it.toLongOrNull() }
        return CpuStat(toks.take(7).sum())
    }

    private data class ProcStat(val total: Long)
    private fun readProcPidStat(pid: Int): ProcStat {
        val toks = java.io.RandomAccessFile("/proc/$pid/stat", "r").use {
            it.readLine().split(" ")
        }
        return ProcStat(
            toks[13].toLong() + toks[14].toLong() + toks[15].toLong() + toks[16].toLong()
        )
    }

    private fun getThreadCount(): Int {
        val pid = Process.myPid()
        val taskDir = File("/proc/$pid/task")
        return try {
            taskDir.list()?.size ?: Thread.getAllStackTraces().size
        } catch (e: Exception) {
            Thread.getAllStackTraces().size
        }
    }

    private fun log(msg: String) {
        binding.logView.append("$msg\n")
        binding.scrollView.post {
            binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }
}
