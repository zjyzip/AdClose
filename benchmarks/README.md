# Keyword matching benchmark

This opt-in desktop JVM benchmark compares the production `KeywordMatcher` with the sequential `firstOrNull { text.contains(rule.normalized) }` implementation used immediately before AC was introduced. Both receive the same pre-normalized keyword list and request text. URL/domain lookups, database IPC, request lowercasing and network hooks are excluded from matching timings.

From the repository root, run:

```bash
./gradlew :app:testDebugUnitTest --tests '*KeywordMatcherTest*'
./gradlew :app:testDebugUnitTest --tests '*KeywordBenchmark*' -PkeywordBenchmark
```

On Windows, `gradlew.bat` can be used instead. No Android device is required. The benchmark runs only with `-PkeywordBenchmark`; ordinary unit tests skip it. This property also disables test up-to-date reuse so repeated benchmark commands execute again.

Results are written to:

- `app/build/reports/keywordBenchmark/matching.csv`
- `app/build/reports/keywordBenchmark/building.csv`
- Gradle's usual HTML test report, including environment and console output.

## Workloads

- Keyword counts: 100, 1,000, 10,000 and 100,000.
- Fixed seed: 20261029.
- `shared`: keywords share an ad-service prefix; `dispersed`: keywords start with deterministic pseudo-random characters.
- 128 URLs per scenario: all miss, all hit the first rule, all hit the last rule, or a mix of first/last/random hits and misses.
- Each method is warmed up independently; five measured samples alternate method order. Matching results use the median ns/request. Inputs and output checksums are consumed to discourage dead-code elimination.
- Build results report the median of three builds, excluding input-list construction. No timing assertions are used because machines, JIT/GC activity and power settings vary.
- Correctness is checked against the sequential reference for every measured input; separate unit tests cover suffix/failure transitions, overlap, list priority, duplicates, Unicode, snapshot copies, URL/domain precedence and 12,000 seeded random cases.

## Memory measurements

An agent built only for the test JVM uses `Instrumentation.getObjectSize` and identity-based object graph traversal. `rules_retained_bytes` includes the baseline rule list, rule objects and strings; `ac_extra_retained_bytes` includes the matcher, its primitive arrays and references, excluding objects already owned by the rule list. Shared objects are counted once. These are measured JVM object sizes, not process RSS or a `totalMemory - freeMemory` estimate. The agent is never packaged in the APK.

`ac_build_allocated_bytes` comes from the current thread's allocation counter. It includes temporary trie maps and sorting allocations, and is **not peak live heap**. Allocation tracing, the graph walker and fixture generation run outside matching timing sections. The worker uses a 512 MiB initial heap and a 2 GiB maximum heap, with one test fork. Large builds can trigger GC and affect build timings.

## Interpretation and update latency

`speedup = sequential_ns / ac_ns`: values above 1 favor AC. Early hits can favor sequential matching; large no-hit/late-hit lists should favor AC. This sparse implementation uses binary search over outgoing edges, so its scan bound includes the transition lookup factor (roughly `O(text length × log(max out-degree))`). Each state's output stores only the highest-priority rule, avoiding enumeration of all overlapping matches.

Rules are still built in the background and published as a complete snapshot. Existing requests use the previous snapshot until construction finishes; AC adds its build time to that update delay. First-load timeout and rule refresh scheduling are unchanged by the matcher. Snapshot rebuilding also temporarily keeps the previous matcher alive, so update-time heap requirements exceed the final retained matcher size.

Desktop HotSpot results quantify this implementation and synthetic workload; they are not Android ART measurements. Validate representative subscriptions and target devices before choosing a refresh interval or a maximum total keyword count. Repeat runs on an idle machine, and compare medians across runs for performance decisions.
