package io.github.kromus.concurrent

import io.github.kromus.Analyzer
import io.github.kromus.Bm25Config
import io.github.kromus.HnswConfig
import io.github.kromus.HybridIndex
import io.github.kromus.MetadataFilter
import io.github.kromus.Metric
import io.github.kromus.Rrf
import io.github.kromus.SearchResult

/**
 * A [HybridIndex] that is safe to use from many coroutines at once: fused vector + BM25 searches run
 * concurrently, mutations run alone. See [ConcurrentVectorIndex] for the rationale and the rules on
 * [read] / [write].
 *
 * The exclusive lock matters more here than for the single-modality indexes: [HybridIndex.add]
 * touches both the vector and the text side, and [add] holds the lock across both, so a search can
 * never see an entry that has landed in one modality but not the other.
 *
 * ```
 * val index = ConcurrentHybridIndex<String>(dimensions = 384)
 * index.add("doc-1", embedder.embed(text), text)
 * val hits = index.search(embedder.embed(query), text = query, k = 10)
 * ```
 *
 * @param index the index to guard. Ownership transfers to this wrapper.
 */
public class ConcurrentHybridIndex<K>(private val index: HybridIndex<K>) {
    /** Creates an empty guarded index; parameters are [HybridIndex]'s. */
    public constructor(
        dimensions: Int,
        metric: Metric = Metric.Cosine,
        hnswConfig: HnswConfig = HnswConfig(),
        analyzer: Analyzer = Analyzer.standard(),
        bm25Config: Bm25Config = Bm25Config(),
        rrfK: Int = Rrf.DEFAULT_K,
    ) : this(HybridIndex<K>(dimensions, metric, hnswConfig, analyzer, bm25Config, rrfK))

    private val lock: ReadWriteMutex = ReadWriteMutex()

    /** Immutable configuration, readable without taking the lock. */
    public val dimensions: Int get() = index.dimensions
    public val metric: Metric get() = index.metric
    public val hnswConfig: HnswConfig get() = index.hnswConfig
    public val analyzer: Analyzer get() = index.analyzer
    public val bm25Config: Bm25Config get() = index.bm25Config
    public val rrfK: Int get() = index.rrfK

    /** Number of live entries; see [HybridIndex.size]. */
    public suspend fun size(): Int = lock.read { index.size }

    public suspend fun contains(key: K): Boolean = lock.read { index.contains(key) }

    /** See [HybridIndex.add]. Both modalities are updated under one exclusive lock. */
    public suspend fun add(
        key: K,
        vector: FloatArray,
        text: String,
        attributes: Map<String, String> = emptyMap(),
    ): Unit = lock.write { index.add(key, vector, text, attributes) }

    /** Adds every entry of [entries] under a single exclusive lock. */
    public suspend fun addAll(entries: Iterable<HybridEntry<K>>): Unit = lock.write {
        for (e in entries) index.add(e.key, e.vector, e.text, e.attributes)
    }

    /** See [HybridIndex.remove]. */
    public suspend fun remove(key: K): Boolean = lock.write { index.remove(key) }

    /** See [HybridIndex.search]. Runs concurrently with other searches. */
    public suspend fun search(
        vector: FloatArray,
        text: String,
        k: Int,
        candidates: Int = maxOf(k * 4, 50),
        efSearch: Int = index.hnswConfig.efSearch,
        filter: MetadataFilter? = null,
    ): List<SearchResult<K>> = lock.read { index.search(vector, text, k, candidates, efSearch, filter) }

    /** See [HybridIndex.searchVector]. */
    public suspend fun searchVector(
        vector: FloatArray,
        k: Int,
        efSearch: Int = index.hnswConfig.efSearch,
        filter: MetadataFilter? = null,
    ): List<SearchResult<K>> = lock.read { index.searchVector(vector, k, efSearch, filter) }

    /** See [HybridIndex.searchText]. */
    public suspend fun searchText(
        text: String,
        k: Int,
        filter: MetadataFilter? = null,
    ): List<SearchResult<K>> = lock.read { index.searchText(text, k, filter) }

    /** Runs [block] against the index under a shared lock; see [ConcurrentVectorIndex.read]. */
    public suspend fun <R> read(block: (HybridIndex<K>) -> R): R = lock.read { block(index) }

    /** Runs [block] against the index under an exclusive lock; see [ConcurrentVectorIndex.write]. */
    public suspend fun <R> write(block: (HybridIndex<K>) -> R): R = lock.write { block(index) }
}

/** One entry for [ConcurrentHybridIndex.addAll]. */
public class HybridEntry<K>(
    public val key: K,
    public val vector: FloatArray,
    public val text: String,
    public val attributes: Map<String, String> = emptyMap(),
)
