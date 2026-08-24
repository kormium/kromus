package io.github.kromus

/**
 * Tuning knobs for the HNSW graph. Defaults are sensible for on-device corpora (thousands to low
 * millions of vectors); raise [m] / [efConstruction] for higher recall at the cost of memory and
 * build time.
 *
 * @property m maximum number of bidirectional links kept per node on the upper layers. Layer 0 keeps
 *   up to `2 * m` links ([maxM0]). Higher `m` improves recall and connectivity but grows the index.
 * @property efConstruction size of the dynamic candidate list while inserting. Larger values build a
 *   better-connected graph (higher recall) but make inserts slower.
 * @property efSearch default size of the dynamic candidate list at query time. Must be `>= k` to
 *   return `k` results; larger values trade latency for recall. Overridable per query.
 * @property seed seed for the level-assignment RNG. Fixed by default so index construction is fully
 *   deterministic and reproducible across every platform.
 * @property quantization how stored vectors are compressed in memory; see [Quantization]. Queries
 *   always run at full precision against the compressed vectors (asymmetric distance).
 * @property maxVisited hard cap on how many nodes one query may touch during graph traversal.
 *   `0` (the default) means the whole index, which is the traversal's natural ceiling — an ordinary
 *   query visits a small multiple of `efSearch` and never comes close.
 *
 *   It matters for **selective metadata filters**. Filtered nodes are traversed but never enter the
 *   result set, so a filter that matches almost nothing keeps the search from ever filling its
 *   candidate list, and the query degenerates into a walk over the entire graph — on device, a
 *   visible stall. Setting a budget bounds that worst case: the search stops early and returns the
 *   matches it did find (possibly fewer than `k`). A few thousand is a reasonable ceiling for
 *   interactive use; measure against your own filters.
 */
public data class HnswConfig(
    val m: Int = 16,
    val efConstruction: Int = 200,
    val efSearch: Int = 64,
    val seed: Long = 42L,
    val quantization: Quantization = Quantization.None,
    val maxVisited: Int = 0,
) {
    init {
        require(m >= 2) { "m must be >= 2, was $m" }
        require(efConstruction >= 1) { "efConstruction must be >= 1, was $efConstruction" }
        require(efSearch >= 1) { "efSearch must be >= 1, was $efSearch" }
        require(maxVisited >= 0) { "maxVisited must be >= 0 (0 = the whole index), was $maxVisited" }
    }

    /** Maximum links per node on layer 0 (the densest, most-searched layer). */
    val maxM0: Int get() = m * 2
}
