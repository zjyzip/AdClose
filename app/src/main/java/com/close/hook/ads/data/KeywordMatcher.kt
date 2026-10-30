package com.close.hook.ads.data

/**
 * Aho-Corasick matcher for keywords normalized by RuleSnapshot.
 * Both the rules and input text must already be lowercase using Locale.ROOT.
 * The returned value follows the input rule priority, not the position in the text.
 */
class KeywordMatcher private constructor(
    private val transitionOffsets: IntArray,
    private val transitionLabels: CharArray,
    private val transitionTargets: IntArray,
    private val failure: IntArray,
    private val outputPriority: IntArray,
    private val values: Array<String>
) {

    fun firstMatch(normalizedText: String): String? {
        if (normalizedText.isEmpty() || values.isEmpty()) return null

        var state = 0
        var bestPriority = Int.MAX_VALUE
        for (char in normalizedText) {
            var next = transition(state, char)
            while (next < 0 && state != 0) {
                state = failure[state]
                next = transition(state, char)
            }
            state = if (next >= 0) next else 0

            val priority = outputPriority[state]
            if (priority >= 0 && priority < bestPriority) {
                bestPriority = priority
                if (bestPriority == 0) return values[0]
            }
        }
        return if (bestPriority == Int.MAX_VALUE) null else values[bestPriority]
    }

    private fun transition(state: Int, label: Char): Int {
        var low = transitionOffsets[state]
        var high = transitionOffsets[state + 1] - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                transitionLabels[middle] < label -> low = middle + 1
                transitionLabels[middle] > label -> high = middle - 1
                else -> return transitionTargets[middle]
            }
        }
        return -1
    }

    companion object {
        val EMPTY = KeywordMatcher(
            transitionOffsets = intArrayOf(0),
            transitionLabels = charArrayOf(),
            transitionTargets = intArrayOf(),
            failure = intArrayOf(0),
            outputPriority = intArrayOf(-1),
            values = emptyArray()
        )

        fun fromRules(rules: List<RuleSnapshot.KeywordRule>): KeywordMatcher {
            if (rules.isEmpty()) return EMPTY

            val nodes = ArrayList<MutableMap<Char, Int>>()
            val outputs = ArrayList<Int>()
            nodes += LinkedHashMap()
            outputs += -1

            rules.forEachIndexed { priority, rule ->
                var node = 0
                rule.normalized.forEach { char ->
                    val next = nodes[node][char] ?: run {
                        val created = nodes.size
                        nodes[node][char] = created
                        nodes += LinkedHashMap()
                        outputs += -1
                        created
                    }
                    node = next
                }
                if (outputs[node] == -1) outputs[node] = priority
            }

            val failure = IntArray(nodes.size)
            val queue = IntArray(nodes.size)
            var head = 0
            var tail = 0

            nodes[0].values.forEach { child ->
                queue[tail++] = child
            }

            while (head < tail) {
                val node = queue[head++]
                nodes[node].forEach { (label, child) ->
                    var fallback = failure[node]
                    var candidate = nodes[fallback][label]
                    while (candidate == null && fallback != 0) {
                        fallback = failure[fallback]
                        candidate = nodes[fallback][label]
                    }
                    failure[child] = candidate ?: 0

                    val inherited = outputs[failure[child]]
                    if (inherited >= 0 && (outputs[child] < 0 || inherited < outputs[child])) {
                        outputs[child] = inherited
                    }
                    queue[tail++] = child
                }
            }

            val offsets = IntArray(nodes.size + 1)
            nodes.forEachIndexed { index, transitions ->
                offsets[index + 1] = offsets[index] + transitions.size
            }
            val labels = CharArray(offsets.last())
            val targets = IntArray(offsets.last())
            nodes.forEachIndexed { index, transitions ->
                transitions.entries.sortedBy { it.key }.forEachIndexed { offset, (label, target) ->
                    val position = offsets[index] + offset
                    labels[position] = label
                    targets[position] = target
                }
            }

            return KeywordMatcher(
                transitionOffsets = offsets,
                transitionLabels = labels,
                transitionTargets = targets,
                failure = failure,
                outputPriority = outputs.toIntArray(),
                values = rules.map { it.value }.toTypedArray()
            )
        }
    }
}
