package io.github.kromus

import kotlin.math.sqrt

/**
 * Re-ranks coarse candidates by exact, full-precision similarity — the second phase of the standard
 * two-phase search used with [Quantization.Binary] (and, if you like, [Quantization.Int8]).
 *
 * A quantized index is fast and tiny but only approximate; it can't resolve the fine ordering of the
 * closest hits. So over-fetch a wider candidate list from the index, then call [rerank] with the
 * original full-precision vectors to get an accurate top-[k]:
 *
 * ```
 * val coarse = index.search(query, k = 100)               // binary: fast, approximate
 * val exact = rerank(query, coarse.map { it.key }, k = 10) { fullVectors[it] }
 * ```
 *
 * kromus is embedder-agnostic and a quantized store does not keep the original vectors, so you supply
 * them via [vectorOf] (the real-world pattern: quantized graph in RAM, full vectors on disk/in a DB).
 * A candidate whose [vectorOf] returns `null` is dropped. Scores use the same scale as [metric]
 * (see [Metric]); duplicate candidates (e.g. from a fused list) are de-duplicated.
 *
 * @throws IllegalArgumentException if `k < 1` or a supplied vector's length differs from [query].
 */
public fun <K> rerank(
    query: FloatArray,
    candidates: List<K>,
    k: Int,
    metric: Metric = Metric.Cosine,
    vectorOf: (K) -> FloatArray?,
): List<SearchResult<K>> {
    require(k >= 1) { "k must be >= 1, was $k" }

    val scored = ArrayList<SearchResult<K>>(candidates.size)
    val seen = HashSet<K>()
    for (key in candidates) {
        if (!seen.add(key)) continue
        val vector = vectorOf(key) ?: continue
        require(vector.size == query.size) {
            "vector for '$key' has ${vector.size} dimensions, query has ${query.size}"
        }
        scored.add(SearchResult(key, exactSimilarity(query, vector, metric)))
    }
    scored.sortByDescending { it.score }
    return if (scored.size > k) scored.subList(0, k).toList() else scored
}

private fun exactSimilarity(query: FloatArray, vector: FloatArray, metric: Metric): Float =
    when (metric) {
        Metric.Cosine -> {
            var dot = 0f
            var qq = 0f
            var vv = 0f
            for (i in query.indices) {
                dot += query[i] * vector[i]
                qq += query[i] * query[i]
                vv += vector[i] * vector[i]
            }
            val denom = sqrt(qq) * sqrt(vv)
            if (denom == 0f) 0f else dot / denom
        }
        Metric.DotProduct -> {
            var dot = 0f
            for (i in query.indices) dot += query[i] * vector[i]
            dot
        }
        Metric.Euclidean -> {
            var s = 0f
            for (i in query.indices) {
                val d = query[i] - vector[i]
                s += d * d
            }
            -sqrt(s)
        }
    }
