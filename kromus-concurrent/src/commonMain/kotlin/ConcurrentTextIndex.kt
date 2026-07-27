package io.github.kromus.concurrent

import io.github.kromus.Analyzer
import io.github.kromus.Bm25Config
import io.github.kromus.MetadataFilter
import io.github.kromus.SearchResult
import io.github.kromus.TextIndex

/**
 * A [TextIndex] that is safe to use from many coroutines at once: BM25 searches run concurrently,
 * mutations run alone. See [ConcurrentVectorIndex] for the rationale and the rules on [read] /
 * [write].
 *
 * @param index the index to guard. Ownership transfers to this wrapper.
 */
public class ConcurrentTextIndex<K>(private val index: TextIndex<K>) {
    /** Creates an empty guarded index; parameters are [TextIndex]'s. */
    public constructor(
        analyzer: Analyzer = Analyzer.standard(),
        config: Bm25Config = Bm25Config(),
    ) : this(TextIndex<K>(analyzer, config))

    private val lock: ReadWriteMutex = ReadWriteMutex()

    /** Immutable configuration, readable without taking the lock. */
    public val analyzer: Analyzer get() = index.analyzer
    public val config: Bm25Config get() = index.config

    /** Number of indexed documents; see [TextIndex.size]. */
    public suspend fun size(): Int = lock.read { index.size }

    public suspend fun contains(key: K): Boolean = lock.read { index.contains(key) }

    /** See [TextIndex.add]. */
    public suspend fun add(
        key: K,
        text: String,
        attributes: Map<String, String> = emptyMap(),
    ): Unit = lock.write { index.add(key, text, attributes) }

    /** Adds every entry of [entries] under a single exclusive lock. */
    public suspend fun addAll(entries: Iterable<TextEntry<K>>): Unit = lock.write {
        for (e in entries) index.add(e.key, e.text, e.attributes)
    }

    /** See [TextIndex.remove]. */
    public suspend fun remove(key: K): Boolean = lock.write { index.remove(key) }

    /** See [TextIndex.search]. Runs concurrently with other searches. */
    public suspend fun search(
        query: String,
        k: Int,
        filter: MetadataFilter? = null,
    ): List<SearchResult<K>> = lock.read { index.search(query, k, filter) }

    /** Runs [block] against the index under a shared lock; see [ConcurrentVectorIndex.read]. */
    public suspend fun <R> read(block: (TextIndex<K>) -> R): R = lock.read { block(index) }

    /** Runs [block] against the index under an exclusive lock; see [ConcurrentVectorIndex.write]. */
    public suspend fun <R> write(block: (TextIndex<K>) -> R): R = lock.write { block(index) }
}

/** One entry for [ConcurrentTextIndex.addAll]. */
public class TextEntry<K>(
    public val key: K,
    public val text: String,
    public val attributes: Map<String, String> = emptyMap(),
)
