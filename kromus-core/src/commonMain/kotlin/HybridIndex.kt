package io.github.kromus

/**
 * A hybrid semantic + keyword index: every entry has both a vector (for similarity search) and text
 * (for BM25 keyword search), and queries fuse the two rankings with [Reciprocal Rank Fusion][Rrf].
 *
 * This is kromus's headline capability. Vector search alone captures meaning but misses exact tokens
 * (product codes, error strings, rare names); BM25 alone matches tokens but not meaning. Fusing them
 * recovers both — the standard 2026 retrieval recipe — in one embedded, pure-Kotlin engine that runs
 * on every KMP target.
 *
 * ```
 * val index = HybridIndex<String>(dimensions = 384)
 * index.add("doc-1", embed("Kotlin coroutines guide"), "Kotlin coroutines guide")
 * val hits = index.search(
 *     vector = embed("async programming"),
 *     text = "coroutines",
 *     k = 10,
 * )
 * ```
 *
 * Keys are unique across both modalities; re-[add]ing replaces the entry. Not thread-safe.
 *
 * @property rrfK fusion constant passed to [Rrf.fuse].
 */
public class HybridIndex<K> private constructor(
    public val dimensions: Int,
    public val metric: Metric,
    public val hnswConfig: HnswConfig,
    public val analyzer: Analyzer,
    public val bm25Config: Bm25Config,
    public val rrfK: Int,
    private val vectorIndex: VectorIndex<K>,
    private val textIndex: TextIndex<K>,
    initialKeys: Set<K>,
) {
    /** Creates an empty hybrid index. */
    public constructor(
        dimensions: Int,
        metric: Metric = Metric.Cosine,
        hnswConfig: HnswConfig = HnswConfig(),
        analyzer: Analyzer = Analyzer.standard(),
        bm25Config: Bm25Config = Bm25Config(),
        rrfK: Int = Rrf.DEFAULT_K,
    ) : this(
        dimensions, metric, hnswConfig, analyzer, bm25Config, rrfK,
        VectorIndex(dimensions, metric, hnswConfig), TextIndex(analyzer, bm25Config), emptySet(),
    )

    private val keys = HashSet<K>(initialKeys)

    /** Number of live entries. */
    public val size: Int get() = keys.size

    public operator fun contains(key: K): Boolean = key in keys

    /** Adds or replaces the entry [key] with its [vector] and [text]. */
    public fun add(key: K, vector: FloatArray, text: String) {
        vectorIndex.add(key, vector)
        textIndex.add(key, text)
        keys.add(key)
    }

    /** Removes [key] from both modalities. @return true if it was present. */
    public fun remove(key: K): Boolean {
        val removed = keys.remove(key)
        vectorIndex.remove(key)
        textIndex.remove(key)
        return removed
    }

    /**
     * Runs both retrievers and fuses their rankings, returning up to [k] results.
     *
     * @param candidates how many hits to pull from each retriever before fusion. Larger widens the
     *   pool RRF can draw from (better recall) at some cost; defaults to a multiple of [k].
     * @param efSearch vector-search breadth; see [VectorIndex.search].
     */
    public fun search(
        vector: FloatArray,
        text: String,
        k: Int,
        candidates: Int = maxOf(k * 4, 50),
        efSearch: Int = hnswConfig.efSearch,
    ): List<SearchResult<K>> {
        require(k >= 1) { "k must be >= 1, was $k" }
        val vectorHits = vectorIndex.search(vector, candidates, efSearch).map { it.key }
        val textHits = textIndex.search(text, candidates).map { it.key }
        return Rrf.fuse(listOf(vectorHits, textHits), limit = k, k = rrfK)
    }

    /** Vector-only retrieval, bypassing fusion. */
    public fun searchVector(vector: FloatArray, k: Int, efSearch: Int = hnswConfig.efSearch): List<SearchResult<K>> =
        vectorIndex.search(vector, k, efSearch)

    /** Text-only (BM25) retrieval, bypassing fusion. */
    public fun searchText(text: String, k: Int): List<SearchResult<K>> =
        textIndex.search(text, k)

    // --- persistence support (accessed by Persistence.kt) ---

    internal fun vectorPart(): VectorIndex<K> = vectorIndex

    internal fun textPart(): TextIndex<K> = textIndex

    internal companion object {
        fun <K> fromParts(
            dimensions: Int,
            metric: Metric,
            hnswConfig: HnswConfig,
            analyzer: Analyzer,
            bm25Config: Bm25Config,
            rrfK: Int,
            vectorIndex: VectorIndex<K>,
            textIndex: TextIndex<K>,
        ): HybridIndex<K> = HybridIndex(
            dimensions, metric, hnswConfig, analyzer, bm25Config, rrfK,
            vectorIndex, textIndex, vectorIndex.liveEntries().keys.toHashSet(),
        )
    }
}
