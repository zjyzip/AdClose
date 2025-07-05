package com.close.hook.ads.debug.fragment

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.close.hook.ads.data.DataSource
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.databinding.FragmentRoomPerformanceBinding
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
import kotlin.system.measureTimeMillis

@RequiresApi(Build.VERSION_CODES.N)
class RoomPerformanceFragment : Fragment() {
    private var _binding: FragmentRoomPerformanceBinding? = null
    private val binding get() = _binding!!
    private lateinit var dataSource: DataSource
    private val chartDataEntries = mutableMapOf<ChartMetric, MutableList<Entry>>()
    private val chartLabels = mutableListOf<String>()
    private val TEST_REPEAT_TIMES = 3
    private val TEST_DATA_SIZE = 1000

    enum class ChartMetric(val label: String, val color: Int) {
        INSERT("插入 (ms)", Color.parseColor("#1E88E5")),
        QUERY_ALL("查询全部 (ms)", Color.parseColor("#6D4C41")),
        QUERY_EXACT("精准URL前缀 (ms)", Color.parseColor("#00897B")),
        QUERY_LIKE("任意包含 (ms)", Color.parseColor("#43A047")),
        PREFIX("URL前缀查找 (ms)", Color.parseColor("#D81B60")),
        HOST("Domain包含查找 (ms)", Color.parseColor("#8E24AA")),
        KEYWORD("Keyword查找 (ms)", Color.parseColor("#3949AB")),
        DELETE("删除 (ms)", Color.parseColor("#FB8C00"))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRoomPerformanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dataSource = DataSource(requireContext())
        ChartMetric.values().forEach { metric -> chartDataEntries[metric] = mutableListOf() }
        setupPerformanceChart()
        setupListeners()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupListeners() {
        binding.runTestButton.setOnClickListener {
            it.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try { runAllPerformanceTests() } finally { it.isEnabled = true }
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

    private suspend fun runAllPerformanceTests() = withContext(Dispatchers.IO) {
        clearAllPerformanceData()
        var totalInsertTime = 0L
        var totalQueryAllTime = 0L
        var totalQueryExactTime = 0L
        var totalQueryLikeTime = 0L
        var totalPrefixTime = 0L
        var totalDomainTime = 0L
        var totalKeywordTime = 0L
        var totalDeleteTime = 0L
        postText { binding.timeSummary.text = "" }
        repeat(TEST_REPEAT_TIMES) { index ->
            val testRunId = index + 1
            postLog("--- ▶️ 第 $testRunId 次Room测试开始 ---")
            val urlList = List(TEST_DATA_SIZE) { i ->
                Url(if (i % 2 == 0) "url" else "domain", "https://test.com/$i", 0L)
            }
            System.gc()
            val insertTime = measureTimeMillis { dataSource.insertAll(urlList) }
            totalInsertTime += insertTime
            postLog("📦 批量插入$TEST_DATA_SIZE 条: ${insertTime}ms")
            delay(400)
            val queryAllTime = measureTimeMillis { dataSource.getUrlListOnce() }
            totalQueryAllTime += queryAllTime
            postLog("🔍 查询所有: ${queryAllTime}ms")
            delay(400)

            val queryExactTime = measureTimeMillis { dataSource.findUrlMatch("https://test.com/1").use { it.count } }
            totalQueryExactTime += queryExactTime
            postLog("🟢 精准URL前缀: ${queryExactTime}ms")

            val queryLikeTime = measureTimeMillis { dataSource.findKeywordMatch("test.com/9").use { it.count } }
            totalQueryLikeTime += queryLikeTime
            postLog("🟡 任意包含: ${queryLikeTime}ms")

            val prefixTime = measureTimeMillis { dataSource.findUrlMatch("https://test.com/1000").use { it.count } }
            totalPrefixTime += prefixTime
            postLog("🔷 URL前缀查找: ${prefixTime}ms")

            val domainTime = measureTimeMillis { dataSource.findDomainMatch("https://test.com/1000").use { it.count } }
            totalDomainTime += domainTime
            postLog("🔶 Domain包含查找: ${domainTime}ms")

            val keywordTime = measureTimeMillis { dataSource.findKeywordMatch("1000").use { it.count } }
            totalKeywordTime += keywordTime
            postLog("🔸 Keyword查找: ${keywordTime}ms")

            val deleteTime = measureTimeMillis { dataSource.deleteAll() }
            totalDeleteTime += deleteTime
            postLog("❌ 删除全部: ${deleteTime}ms")
            chartLabels.add("运行 $testRunId")
            chartDataEntries[ChartMetric.INSERT]?.add(Entry(index.toFloat(), insertTime.toFloat()))
            chartDataEntries[ChartMetric.QUERY_ALL]?.add(Entry(index.toFloat(), queryAllTime.toFloat()))
            chartDataEntries[ChartMetric.QUERY_EXACT]?.add(Entry(index.toFloat(), queryExactTime.toFloat()))
            chartDataEntries[ChartMetric.QUERY_LIKE]?.add(Entry(index.toFloat(), queryLikeTime.toFloat()))
            chartDataEntries[ChartMetric.PREFIX]?.add(Entry(index.toFloat(), prefixTime.toFloat()))
            chartDataEntries[ChartMetric.HOST]?.add(Entry(index.toFloat(), domainTime.toFloat()))
            chartDataEntries[ChartMetric.KEYWORD]?.add(Entry(index.toFloat(), keywordTime.toFloat()))
            chartDataEntries[ChartMetric.DELETE]?.add(Entry(index.toFloat(), deleteTime.toFloat()))
            postLog("--- 第 $testRunId 次Room测试结束 ---\n")
            delay(500)
            System.gc()
        }
        withContext(Dispatchers.Main) { updatePerformanceChart() }
        val summary = """
            --- 🎯 Room性能测试总结 ---
            平均批量插入: ${totalInsertTime / TEST_REPEAT_TIMES}ms
            平均查询所有: ${totalQueryAllTime / TEST_REPEAT_TIMES}ms
            平均精准URL前缀: ${totalQueryExactTime / TEST_REPEAT_TIMES}ms
            平均任意包含: ${totalQueryLikeTime / TEST_REPEAT_TIMES}ms
            平均URL前缀查找: ${totalPrefixTime / TEST_REPEAT_TIMES}ms
            平均Domain包含查找: ${totalDomainTime / TEST_REPEAT_TIMES}ms
            平均Keyword查找: ${totalKeywordTime / TEST_REPEAT_TIMES}ms
            平均删除: ${totalDeleteTime / TEST_REPEAT_TIMES}ms
            -----------------------
        """.trimIndent()
        postText { binding.timeSummary.text = summary }
        postLog(summary)
        postLog("\n🚀 Room性能测试完成。")
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

    private fun postLog(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            binding.logView.append("$message\n")
            binding.scrollView.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun postText(action: () -> Unit) {
        lifecycleScope.launch(Dispatchers.Main) { action() }
    }
}
