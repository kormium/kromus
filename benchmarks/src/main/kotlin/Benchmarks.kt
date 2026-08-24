package io.github.kromus.benchmarks

import io.github.kromus.HnswConfig
import io.github.kromus.KeyCodec
import io.github.kromus.Quantization
import io.github.kromus.VectorIndex
import io.github.kromus.encodeToByteArray
import io.github.kromus.rerank

private fun buildIndex(dataset: Dataset, config: HnswConfig): Pair<VectorIndex<Int>, Double> {
    val index = VectorIndex<Int>(dataset.dimensions, config = config)
    val ms = Timing.millis {
        for (i in dataset.vectors.indices) index.add(i, dataset.vectors[i])
    }
    return index to ms
}

/** Recall and latency against the exact top-k, swept over the search-effort knob. */
fun recallSweep(dataset: Dataset, truth: List<IntArray>, k: Int, efValues: List<Int>): Report {
    val report = Report("Recall and latency vs `efSearch`")
    report.note("Full precision (`Quantization.None`), `m = 16`, `efConstruction = 200`, k = $k.")
    report.columns("efSearch", "recall@$k", "mean query", "p95 query")

    val (index, buildMs) = buildIndex(dataset, HnswConfig())
    println("  built in ${"%.0f".format(buildMs)} ms")

    // One warmup across the whole sweep, not just per row: the first row would otherwise be measured
    // on a colder JVM than the rest and come out slower than a strictly harder setting.
    repeat(1000) { i ->
        index.search(dataset.queries[i % dataset.queries.size], k = k, efSearch = efValues.max())
    }

    for (ef in efValues) {
        val results = dataset.queries.map { query -> index.search(query, k = k, efSearch = ef).map { it.key } }
        val latencies = Timing.latencies(dataset.queries.size) { i ->
            index.search(dataset.queries[i], k = k, efSearch = ef)
        }
        report.row(
            ef,
            "%.3f".format(recallAt(truth, results, k)),
            "%.0f µs".format(latencies.mean()),
            "%.0f µs".format(latencies.percentile(0.95)),
        )
    }
    return report
}

/** What each quantization costs and buys: build time, footprint, recall, latency. */
fun quantizationComparison(dataset: Dataset, truth: List<IntArray>, k: Int, ef: Int): Report {
    val report = Report("Quantization")
    report.note(
        "Same graph settings throughout; `efSearch = $ef`, k = $k. \"Serialized\" is the size of " +
            "`encodeToByteArray` — what you actually ship or cache.",
    )
    report.columns("mode", "build", "serialized", "recall@$k", "mean query")

    for (quantization in Quantization.entries) {
        val (index, buildMs) = buildIndex(dataset, HnswConfig(quantization = quantization))
        val serialized = index.encodeToByteArray(KeyCodec.int).size.toLong()
        val results = dataset.queries.map { query -> index.search(query, k = k, efSearch = ef).map { it.key } }
        val latencies = Timing.latencies(dataset.queries.size) { i ->
            index.search(dataset.queries[i], k = k, efSearch = ef)
        }
        report.row(
            quantization.name,
            "%.0f ms".format(buildMs),
            formatBytes(serialized),
            "%.3f".format(recallAt(truth, results, k)),
            "%.0f µs".format(latencies.mean()),
        )

        if (quantization == Quantization.Binary) {
            // The two-phase pattern the readme recommends: over-fetch coarsely, re-score exactly.
            fun rerankedHits(query: FloatArray): List<Int> {
                val coarse = index.search(query, k = k * 10, efSearch = ef * 2).map { it.key }
                return rerank(query, coarse, k = k) { dataset.vectors[it] }.map { it.key }
            }
            val reranked = dataset.queries.map { rerankedHits(it) }
            val rerankLatencies = Timing.latencies(dataset.queries.size) { i -> rerankedHits(dataset.queries[i]) }
            report.row(
                "Binary + rerank",
                "—",
                formatBytes(serialized),
                "%.3f".format(recallAt(truth, reranked, k)),
                "%.0f µs".format(rerankLatencies.mean()),
            )
        }
    }
    return report
}

/** The cost of a selective metadata filter, and what a traversal budget does to it. */
fun filterBudget(dataset: Dataset, k: Int): Report {
    val report = Report("Selective filters and the traversal budget")
    report.note(
        "One entry in 200 matches the filter. Without a budget the search keeps walking until it has " +
            "k matches; with one it stops early and returns what it found.",
    )
    report.columns("query", "mean latency", "hits (k = $k)")

    val index = VectorIndex<Int>(dataset.dimensions)
    for (i in dataset.vectors.indices) {
        index.add(i, dataset.vectors[i], mapOf("g" to (i % 200).toString()))
    }
    val filter: (Map<String, String>) -> Boolean = { it["g"] == "7" }

    val plainHits = IntArray(dataset.queries.size)
    val plain = Timing.latencies(dataset.queries.size) { i ->
        plainHits[i] = index.search(dataset.queries[i], k = k).size
    }
    report.row("unfiltered", "%.0f µs".format(plain.mean()), plainHits.average())

    val filteredHits = IntArray(dataset.queries.size)
    val filtered = Timing.latencies(dataset.queries.size) { i ->
        filteredHits[i] = index.search(dataset.queries[i], k = k, filter = filter).size
    }
    report.row("filtered, no budget", "%.0f µs".format(filtered.mean()), filteredHits.average())

    for (budget in listOf(2000, 500)) {
        val hits = IntArray(dataset.queries.size)
        val latencies = Timing.latencies(dataset.queries.size) { i ->
            hits[i] = index.search(dataset.queries[i], k = k, maxVisited = budget, filter = filter).size
        }
        report.row("filtered, maxVisited = $budget", "%.0f µs".format(latencies.mean()), hits.average())
    }
    return report
}

/** What churn costs an index, and what compaction gives back. */
fun compaction(dataset: Dataset, k: Int): Report {
    val report = Report("Churn and compaction")
    report.note(
        "Every entry is replaced once — the shape of an index kept fresh from changing data. " +
            "Tombstones stay in the graph as routing hops until a rebuild reclaims them.",
    )
    report.columns("state", "live entries", "graph slots", "serialized", "mean query")

    val index = VectorIndex<Int>(dataset.dimensions)
    for (i in dataset.vectors.indices) index.add(i, dataset.vectors[i])

    fun measure(label: String) {
        val latencies = Timing.latencies(dataset.queries.size) { i ->
            index.search(dataset.queries[i], k = k)
        }
        report.row(
            label,
            index.size,
            index.size + index.tombstones,
            formatBytes(index.encodeToByteArray(KeyCodec.int).size.toLong()),
            "%.0f µs".format(latencies.mean()),
        )
    }

    measure("fresh")
    for (i in dataset.vectors.indices) index.add(i, dataset.vectors[i]) // replace every entry
    measure("after replacing every entry")

    val compactMs = Timing.millis { index.compact() }
    measure("after compact() (${"%.0f".format(compactMs)} ms)")
    return report
}

/**
 * How corpus structure moves recall. A recall number without this context means little: on tightly
 * clustered data any graph index looks perfect, and on data that is effectively uniform noise none
 * does — the true tenth neighbour is barely nearer than a random point, so there is nothing for the
 * graph to navigate towards.
 *
 * Runs on a smaller corpus than the rest of the suite, since it builds one index per spread.
 */
fun hardnessSweep(count: Int, dimensions: Int, queries: Int, k: Int, ef: Int): Report {
    val report = Report("Recall vs corpus hardness")
    report.note(
        "$count vectors, `efSearch = $ef`. \"Contrast\" is how much closer the true k-th neighbour is " +
            "than an average corpus member — the property that decides whether approximate search can " +
            "work at all. Real embedding corpora sit around 1.5–3×.",
    )
    report.columns("cluster spread", "contrast", "recall@$k", "mean query", "brute force")

    for (spread in listOf(0.5f, 1f, 2f, 4f)) {
        val dataset = Dataset.generate(count, dimensions, queries, spread = spread)
        val truth = dataset.groundTruth(k)
        val (index, _) = buildIndex(dataset, HnswConfig())
        val results = dataset.queries.map { query -> index.search(query, k = k, efSearch = ef).map { it.key } }
        val latencies = Timing.latencies(dataset.queries.size) { i ->
            index.search(dataset.queries[i], k = k, efSearch = ef)
        }
        val bruteForce = Timing.latencies(minOf(queries, 20)) { i ->
            var best = -2f
            for (v in dataset.vectors) {
                var dot = 0f
                for (d in v.indices) dot += dataset.queries[i][d] * v[d]
                if (dot > best) best = dot
            }
        }
        report.row(
            spread,
            "%.2f×".format(dataset.contrast(k)),
            "%.3f".format(recallAt(truth, results, k)),
            "%.0f µs".format(latencies.mean()),
            "%.0f µs".format(bruteForce.mean()),
        )
    }
    return report
}
