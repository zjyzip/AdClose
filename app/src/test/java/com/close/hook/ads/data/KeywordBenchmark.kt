package com.close.hook.ads.data

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.function.LongSupplier
import java.util.function.ToLongFunction
import kotlin.random.Random
import kotlin.system.measureNanoTime

/** Opt-in desktop JVM benchmark. See benchmarks/README.md for interpretation. */
class KeywordBenchmark {
    companion object {
        @Volatile private var sink = 0L
        private val counts = listOf(100, 1_000, 10_000, 100_000)
    }

    @Test
    fun compareSequentialAndAutomaton() {
        assumeTrue("Run with -PkeywordBenchmark", System.getProperty("adclose.keywordBenchmark") == "true")
        @Suppress("UNCHECKED_CAST")
        val objectSize = System.getProperties()["adclose.benchmark.objectSize"] as ToLongFunction<Any>
        val allocation = System.getProperties()["adclose.benchmark.allocatedBytes"] as LongSupplier
        val output = File(requireNotNull(System.getProperty("adclose.benchmark.output"))).apply { mkdirs() }
        val matching = mutableListOf("distribution,count,scenario,sequential_ns_per_request,ac_ns_per_request,speedup")
        val building = mutableListOf("distribution,count,ac_build_ms,ac_build_allocated_bytes,rules_retained_bytes,ac_extra_retained_bytes")

        println("Environment: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}; " +
            "${System.getProperty("os.name")} ${System.getProperty("os.arch")}; CPUs=${Runtime.getRuntime().availableProcessors()}")
        println("Seed=20261029; 128 pre-normalized URLs/scenario; 5 samples; independent warm-up per method")
        println("Memory: agent object graph sizes, shared rule objects excluded from AC extra; build allocation != peak heap")

        // Initialize relevant classes/JIT paths before build measurements.
        repeat(30) { KeywordMatcher.fromRules(generateRules(100, "shared")) }
        for (distribution in listOf("shared", "dispersed")) {
            for (count in counts) {
                val rules = generateRules(count, distribution)
                val buildTimes = ArrayList<Long>()
                val buildAllocations = ArrayList<Long>()
                var matcher = KeywordMatcher.EMPTY
                repeat(3) {
                    val before = allocation.asLong
                    buildTimes += measureNanoTime { matcher = KeywordMatcher.fromRules(rules) }
                    buildAllocations += allocation.asLong - before
                }
                val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
                val ruleBytes = graphBytes(rules, objectSize, seen)
                val extraBytes = graphBytes(matcher, objectSize, seen)
                val buildMs = median(buildTimes).toDouble() / 1_000_000
                building += "$distribution,$count,${decimal(buildMs)},${median(buildAllocations)},$ruleBytes,$extraBytes"
                println("BUILD $distribution n=$count ${decimal(buildMs)} ms; rules=$ruleBytes B; AC extra=$extraBytes B; " +
                    "allocated=${median(buildAllocations)} B")

                for (scenario in listOf("miss", "first", "last", "mixed")) {
                    val inputs = requests(rules, scenario)
                    val sequential: (String) -> String? = { text ->
                        rules.firstOrNull { text.contains(it.normalized) }?.value
                    }
                    val ac: (String) -> String? = matcher::firstMatch
                    inputs.forEach { assertEquals(sequential(it), ac(it)) }
                    warmUp(inputs, sequential)
                    warmUp(inputs, ac)
                    val sequentialSamples = ArrayList<Double>()
                    val acSamples = ArrayList<Double>()
                    repeat(5) { sampleIndex ->
                        if (sampleIndex % 2 == 0) {
                            sequentialSamples += sample(inputs, sequential)
                            acSamples += sample(inputs, ac)
                        } else {
                            acSamples += sample(inputs, ac)
                            sequentialSamples += sample(inputs, sequential)
                        }
                    }
                    val oldNs = sequentialSamples.sorted()[2]
                    val acNs = acSamples.sorted()[2]
                    matching += "$distribution,$count,$scenario,${decimal(oldNs)},${decimal(acNs)},${decimal(oldNs / acNs)}"
                    println("MATCH $distribution n=$count $scenario: sequential=${decimal(oldNs)} ns AC=${decimal(acNs)} ns " +
                        "speedup=${decimal(oldNs / acNs)}x")
                }
                // Save incrementally, so a stopped long run still leaves completed cases.
                File(output, "matching.csv").writeText(matching.joinToString("\n", postfix = "\n"))
                File(output, "building.csv").writeText(building.joinToString("\n", postfix = "\n"))
            }
        }
        println("CSV results: ${output.absolutePath}; sink=$sink")
    }

    private fun generateRules(count: Int, distribution: String): List<RuleSnapshot.KeywordRule> {
        val random = Random(20261029)
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return List(count) { index ->
            val unique = index.toString(36).padStart(5, '0')
            val randomPart = buildString { repeat(12) { append(alphabet.random(random)) } }
            val value = if (distribution == "shared") "Adservice/$unique/$randomPart/collect"
                        else "$randomPart/$unique/Event"
            RuleSnapshot.KeywordRule(value, value.lowercase(Locale.ROOT))
        }
    }

    private fun requests(rules: List<RuleSnapshot.KeywordRule>, scenario: String): List<String> {
        val random = Random(20261029)
        return List(128) { index ->
            val keyword = when (scenario) {
                "first" -> rules.first().normalized
                "last" -> rules.last().normalized
                "mixed" -> when (index % 4) {
                    0 -> rules.first().normalized
                    1 -> rules.last().normalized
                    2 -> rules[random.nextInt(rules.size)].normalized
                    else -> "ordinary/content/article"
                }
                else -> "ordinary/content/article"
            }
            "https://api.example.net/v2/$keyword/config?device=android&session=$index&lang=en&version=437"
        }
    }

    private fun warmUp(inputs: List<String>, match: (String) -> String?) {
        val deadline = System.nanoTime() + 100_000_000L
        do { batch(inputs, match) } while (System.nanoTime() < deadline)
    }

    private fun sample(inputs: List<String>, match: (String) -> String?): Double {
        var batches = 0L
        val started = System.nanoTime()
        do {
            batch(inputs, match)
            batches++
        } while (System.nanoTime() - started < 50_000_000L)
        return (System.nanoTime() - started).toDouble() / (batches * inputs.size)
    }

    private fun batch(inputs: List<String>, match: (String) -> String?) {
        var checksum = 0L
        for (input in inputs) checksum += match(input)?.length ?: -1
        sink = checksum
    }

    /** Actual shallow sizes from Instrumentation; traverse references once by identity. */
    private fun graphBytes(root: Any, objectSize: ToLongFunction<Any>, seen: MutableSet<Any>): Long {
        val pending = ArrayDeque<Any>()
        pending.addLast(root)
        var bytes = 0L
        while (pending.isNotEmpty()) {
            val value = pending.removeLast()
            if (!seen.add(value)) continue
            bytes += objectSize.applyAsLong(value)
            val type = value.javaClass
            if (type.isArray) {
                if (!type.componentType.isPrimitive) {
                    repeat(ReflectArray.getLength(value)) { index ->
                        ReflectArray.get(value, index)?.let(pending::addLast)
                    }
                }
            } else {
                var current: Class<*>? = type
                while (current != null) {
                    for (field in current.declaredFields) {
                        if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                        field.isAccessible = true
                        field.get(value)?.let(pending::addLast)
                    }
                    current = current.superclass
                }
            }
        }
        return bytes
    }

    private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]
    private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
}
