package io.github.kromus

/**
 * Reciprocal Rank Fusion — merges several ranked result lists into one by rank alone, ignoring each
 * retriever's raw scores. That is exactly what makes it robust for hybrid search: BM25 scores are
 * unbounded and corpus-dependent while cosine similarity lives in `[-1, 1]`, so the two cannot be
 * normalized onto a common scale — but their *rankings* can be combined.
 *
 * Each list contributes `1 / (k + rank)` to every key it contains (rank starting at 1), and keys are
 * ranked by the sum. Keys near the top of multiple lists win.
 */
public object Rrf {
    /** Conventional fusion constant; larger flattens the contribution of top ranks. */
    public const val DEFAULT_K: Int = 60

    /**
     * Fuses [rankings] (each already ordered best-first) into a single best-first list of up to
     * [limit] results. [SearchResult.score] is the RRF score.
     *
     * @param k fusion constant; see [DEFAULT_K].
     */
    public fun <K> fuse(
        rankings: List<List<K>>,
        limit: Int,
        k: Int = DEFAULT_K,
    ): List<SearchResult<K>> {
        require(limit >= 1) { "limit must be >= 1, was $limit" }
        require(k >= 1) { "k must be >= 1, was $k" }

        // LinkedHashMap keeps first-seen order, so the stable sort below breaks ties by first
        // appearance across the input rankings — deterministic on every platform.
        val scores = LinkedHashMap<K, Float>()
        for (ranking in rankings) {
            for ((index, key) in ranking.withIndex()) {
                val contribution = 1f / (k + index + 1)
                scores[key] = (scores[key] ?: 0f) + contribution
            }
        }

        return scores.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { SearchResult(it.key, it.value) }
    }
}
