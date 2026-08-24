package io.github.kromus.sync

import io.github.kromus.HybridIndex
import io.github.kromus.MetadataFilter
import io.github.kromus.SearchResult
import io.github.kromus.TextIndex
import io.github.kromus.VectorIndex
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Serialized access to an index that is written and read from different coroutines.
//
// A kromus index is deliberately not thread-safe: it is a single-writer data structure, and paying
// for locks inside the hot loop would tax the common case of building and querying in one place. But
// the on-device case is usually not that case — a background sync or an import writes while the UI
// searches — and an unguarded index under concurrent access corrupts silently rather than failing
// loudly. These wrappers are the guard, kept out of the zero-dependency core because they need
// coroutines.
//
//     val index = HybridIndex<String>(dimensions = 384).concurrent()
//
//     // background: keeps the index fresh
//     scope.launch { docs.observe(db).syncTo(index, keyOf = { it.id }) { HybridDoc(embed(it.body), it.body) } }
//
//     // UI: searches whatever is indexed right now
//     val hits = index.search(embed(query), text = query, k = 10)
//
// The block passed to `use` is not `suspend` by design: it cannot await anything while holding the
// lock, which keeps a slow embedding or a network call from stalling every reader. Do the expensive
// work first, then take the lock to write the result — which is what the `syncTo` overloads for these
// wrappers already do.
//
// Never let the index itself escape (`use { it }`): every access has to go through the lock to be
// safe. Operations that legitimately hold it for a while — compaction, encoding a large index — block
// readers for their duration, so keep them off the UI path.

/** Guards this index behind a [Mutex]; see [ConcurrentVectorIndex]. */
public fun <K> VectorIndex<K>.concurrent(): ConcurrentVectorIndex<K> = ConcurrentVectorIndex(this)

/** Guards this index behind a [Mutex]; see [ConcurrentTextIndex]. */
public fun <K> TextIndex<K>.concurrent(): ConcurrentTextIndex<K> = ConcurrentTextIndex(this)

/** Guards this index behind a [Mutex]; see [ConcurrentHybridIndex]. */
public fun <K> HybridIndex<K>.concurrent(): ConcurrentHybridIndex<K> = ConcurrentHybridIndex(this)

/**
 * A [VectorIndex] behind a [Mutex], so a background writer and a foreground reader can share it.
 * Create with [concurrent]. Anything not exposed here is reachable through [use].
 */
public class ConcurrentVectorIndex<K> internal constructor(
    private val index: VectorIndex<K>,
) {
    private val mutex = Mutex()

    /** Runs [block] against the index under the lock. Do not let the index escape the block. */
    public suspend fun <R> use(block: (VectorIndex<K>) -> R): R = mutex.withLock { block(index) }

    public suspend fun add(key: K, vector: FloatArray, attributes: Map<String, String> = emptyMap()): Unit =
        use { it.add(key, vector, attributes) }

    public suspend fun remove(key: K): Boolean = use { it.remove(key) }

    public suspend fun updateAttributes(key: K, attributes: Map<String, String>): Boolean =
        use { it.updateAttributes(key, attributes) }

    public suspend fun search(
        query: FloatArray,
        k: Int,
        efSearch: Int? = null,
        maxVisited: Int? = null,
        filter: MetadataFilter? = null,
    ): List<SearchResult<K>> = use {
        it.search(query, k, efSearch ?: it.config.efSearch, maxVisited ?: it.config.maxVisited, filter)
    }

    public suspend fun size(): Int = use { it.size }

    public suspend fun tombstones(): Int = use { it.tombstones }

    /** Rebuilds the graph; see [VectorIndex.compact]. Holds the lock for the whole rebuild. */
    public suspend fun compact(): Int = use { it.compact() }
}

/**
 * A [TextIndex] behind a [Mutex]. Create with [concurrent]; see [ConcurrentVectorIndex] for the
 * locking contract.
 */
public class ConcurrentTextIndex<K> internal constructor(
    private val index: TextIndex<K>,
) {
    private val mutex = Mutex()

    /** Runs [block] against the index under the lock. Do not let the index escape the block. */
    public suspend fun <R> use(block: (TextIndex<K>) -> R): R = mutex.withLock { block(index) }

    public suspend fun add(key: K, text: String, attributes: Map<String, String> = emptyMap()): Unit =
        use { it.add(key, text, attributes) }

    public suspend fun remove(key: K): Boolean = use { it.remove(key) }

    public suspend fun updateAttributes(key: K, attributes: Map<String, String>): Boolean =
        use { it.updateAttributes(key, attributes) }

    public suspend fun search(query: String, k: Int, filter: MetadataFilter? = null): List<SearchResult<K>> =
        use { it.search(query, k, filter) }

    public suspend fun size(): Int = use { it.size }
}

/**
 * A [HybridIndex] behind a [Mutex]. Create with [concurrent]; see [ConcurrentVectorIndex] for the
 * locking contract.
 */
public class ConcurrentHybridIndex<K> internal constructor(
    private val index: HybridIndex<K>,
) {
    private val mutex = Mutex()

    /** Runs [block] against the index under the lock. Do not let the index escape the block. */
    public suspend fun <R> use(block: (HybridIndex<K>) -> R): R = mutex.withLock { block(index) }

    public suspend fun add(
        key: K,
        vector: FloatArray,
        text: String,
        attributes: Map<String, String> = emptyMap(),
    ): Unit = use { it.add(key, vector, text, attributes) }

    public suspend fun remove(key: K): Boolean = use { it.remove(key) }

    public suspend fun updateAttributes(key: K, attributes: Map<String, String>): Boolean =
        use { it.updateAttributes(key, attributes) }

    public suspend fun search(
        vector: FloatArray,
        text: String,
        k: Int,
        candidates: Int? = null,
        efSearch: Int? = null,
        maxVisited: Int? = null,
        filter: MetadataFilter? = null,
    ): List<SearchResult<K>> = use {
        it.search(
            vector,
            text,
            k,
            candidates ?: maxOf(k * 4, 50),
            efSearch ?: it.hnswConfig.efSearch,
            maxVisited ?: it.hnswConfig.maxVisited,
            filter,
        )
    }

    public suspend fun searchVector(
        vector: FloatArray,
        k: Int,
        efSearch: Int? = null,
        maxVisited: Int? = null,
        filter: MetadataFilter? = null,
    ): List<SearchResult<K>> = use {
        it.searchVector(vector, k, efSearch ?: it.hnswConfig.efSearch, maxVisited ?: it.hnswConfig.maxVisited, filter)
    }

    public suspend fun searchText(text: String, k: Int, filter: MetadataFilter? = null): List<SearchResult<K>> =
        use { it.searchText(text, k, filter) }

    public suspend fun size(): Int = use { it.size }

    public suspend fun tombstones(): Int = use { it.tombstones }

    /** Rebuilds the vector graph; see [HybridIndex.compact]. Holds the lock for the whole rebuild. */
    public suspend fun compact(): Int = use { it.compact() }
}
