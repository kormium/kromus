package io.github.kromus.concurrent

import io.github.kromus.HnswConfig
import io.github.kromus.MetadataFilter
import io.github.kromus.Metric
import io.github.kromus.SearchResult
import io.github.kromus.VectorIndex

/**
 * A [VectorIndex] that is safe to use from many coroutines at once: searches run concurrently,
 * mutations run alone.
 *
 * kromus's indexes are single-threaded by design — that is what keeps the core zero-dependency and
 * identical on every target. This wrapper adds the missing half for the common on-device shape:
 * *index in the background, search from the UI*. Every operation is a `suspend` function guarded by
 * a [ReadWriteMutex], so callers never block a thread waiting for the index.
 *
 * ```
 * val index = ConcurrentVectorIndex<String>(dimensions = 384)
 *
 * scope.launch { index.add("doc-1", embedder.embed(text)) }   // exclusive
 * val hits = index.search(embedder.embed(query), k = 10)      // concurrent with other searches
 * ```
 *
 * The wrapped index must not be touched directly once handed over — [read] and [write] are the
 * supported escape hatches, and neither may leak the index out of its block.
 *
 * @param index the index to guard. Ownership transfers to this wrapper.
 */
public class ConcurrentVectorIndex<K>(private val index: VectorIndex<K>) {
    /** Creates an empty guarded index; parameters are [VectorIndex]'s. */
    public constructor(
        dimensions: Int,
        metric: Metric = Metric.Cosine,
        config: HnswConfig = HnswConfig(),
    ) : this(VectorIndex<K>(dimensions, metric, config))

    private val lock: ReadWriteMutex = ReadWriteMutex()

    /** Immutable configuration, readable without taking the lock. */
    public val dimensions: Int get() = index.dimensions
    public val metric: Metric get() = index.metric
    public val config: HnswConfig get() = index.config

    /** Number of live entries; see [VectorIndex.size]. */
    public suspend fun size(): Int = lock.read { index.size }

    public suspend fun contains(key: K): Boolean = lock.read { index.contains(key) }

    /** See [VectorIndex.add]. */
    public suspend fun add(
        key: K,
        vector: FloatArray,
        attributes: Map<String, String> = emptyMap(),
    ): Unit = lock.write { index.add(key, vector, attributes) }

    /**
     * Adds every entry of [entries] under a single exclusive lock — one acquisition for the batch
     * instead of one per entry, and searches never observe a partially applied batch.
     */
    public suspend fun addAll(entries: Iterable<VectorEntry<K>>): Unit = lock.write {
        for (e in entries) index.add(e.key, e.vector, e.attributes)
    }

    /** See [VectorIndex.remove]. */
    public suspend fun remove(key: K): Boolean = lock.write { index.remove(key) }

    /** See [VectorIndex.search]. Runs concurrently with other searches. */
    public suspend fun search(
        query: FloatArray,
        k: Int,
        efSearch: Int = index.config.efSearch,
        filter: MetadataFilter? = null,
    ): List<SearchResult<K>> = lock.read { index.search(query, k, efSearch, filter) }

    /**
     * Runs [block] against the index with the lock held in shared mode — for read-only work this
     * class does not wrap, such as encoding a snapshot:
     *
     * ```
     * val bytes = index.read { it.encodeToByteArray(KeyCodec.string) }
     * ```
     *
     * [block] must not mutate the index and must not let it escape.
     */
    public suspend fun <R> read(block: (VectorIndex<K>) -> R): R = lock.read { block(index) }

    /**
     * Runs [block] against the index with the lock held exclusively — for mutations this class does
     * not wrap, or for a group of changes that must land as one. [block] must not let the index
     * escape.
     */
    public suspend fun <R> write(block: (VectorIndex<K>) -> R): R = lock.write { block(index) }
}

/** One entry for [ConcurrentVectorIndex.addAll]. */
public class VectorEntry<K>(
    public val key: K,
    public val vector: FloatArray,
    public val attributes: Map<String, String> = emptyMap(),
)
