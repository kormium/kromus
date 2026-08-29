package io.github.kromus.sync

import io.github.kromus.HybridIndex
import io.github.kromus.HybridSearcher
import io.github.kromus.MetadataFilter
import io.github.kromus.SearchResult
import io.github.kromus.TextIndex
import io.github.kromus.VectorIndex

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
// Searches run in parallel; writes run alone. That took a change in the core to become possible: a
// traversal's working state — visited marks, candidate heaps, layer buffers — used to live on the
// index and be reused between calls, which is what makes a query allocate almost nothing and equally
// what made two concurrent queries corrupt each other. It now lives in a scratch owned by a
// `searcher()`, so readers that each hold one never touch the same memory.
//
// The lock is writer-preferring. A UI issuing a steady stream of searches would otherwise keep the
// read side permanently occupied and the coroutine keeping the index fresh would never get in.
//
// Searchers come from a small pool rather than one per call: a scratch holds a visited-mark array
// sized to the graph, so allocating one per search would give back exactly the allocation the core
// change preserved.
//
// Both the lock and the pool had to be built without a shared mutex on the read path, and that is not
// a micro-optimization. Measured at 20 000 vectors with six concurrent readers, a first version that
// routed every acquire and release through one Mutex — first in the lock, then in the pool — held
// throughput at 5 300 searches/s, no better than a single thread. With an atomic fast path in each,
// the same six readers reach 24 200 against 4 000 for one: 6.0x, against a lock-free ceiling of
// 30 400. A guard whose bookkeeping costs more than the work it guards buys nothing.
//
// Never let the index itself escape (`use { it }`): every access has to go through the lock to be
// safe. Operations that legitimately hold it for a while — compaction, encoding a large index — block
// readers for their duration, so keep them off the UI path.

/** Guards this index behind a readers-writer lock; see [ConcurrentVectorIndex]. */
public fun <K> VectorIndex<K>.concurrent(): ConcurrentVectorIndex<K> = ConcurrentVectorIndex(this)

/** Guards this index behind a readers-writer lock; see [ConcurrentTextIndex]. */
public fun <K> TextIndex<K>.concurrent(): ConcurrentTextIndex<K> = ConcurrentTextIndex(this)

/** Guards this index behind a readers-writer lock; see [ConcurrentHybridIndex]. */
public fun <K> HybridIndex<K>.concurrent(): ConcurrentHybridIndex<K> = ConcurrentHybridIndex(this)

/**
 * A [VectorIndex] behind a readers-writer lock, so a background writer and foreground readers can
 * share it — and the readers run at the same time as each other. Create with [concurrent].
 *
 * Anything not exposed here is reachable through [use], which takes the lock exclusively because a
 * caller-supplied block may write.
 */
public class ConcurrentVectorIndex<K> internal constructor(
    private val index: VectorIndex<K>,
) {
    private val lock = ReadWriteMutex()
    private val pool = SearcherPool { index.searcher() }

    /**
     * Runs [block] against the index with the lock held **exclusively**. Do not let the index escape
     * the block. Use it for anything that may mutate; for reads, prefer [read], which lets other
     * readers in.
     */
    public suspend fun <R> use(block: (VectorIndex<K>) -> R): R = lock.write { block(index) }

    /**
     * Runs a read-only [block] against the index alongside other readers.
     *
     * Nothing checks that it only reads — writing from here corrupts the index exactly as an
     * unguarded write would. Searching through [search] is the safe form of this.
     */
    public suspend fun <R> read(block: (VectorIndex<K>) -> R): R = lock.read { block(index) }

    public suspend fun add(key: K, vector: FloatArray, attributes: Map<String, String> = emptyMap()): Unit =
        use { it.add(key, vector, attributes) }

    public suspend fun remove(key: K): Boolean = use { it.remove(key) }

    public suspend fun updateAttributes(key: K, attributes: Map<String, String>): Boolean =
        use { it.updateAttributes(key, attributes) }

    /** Searches alongside other searches; only a writer excludes it. */
    public suspend fun search(
        query: FloatArray,
        k: Int,
        efSearch: Int? = null,
        maxVisited: Int? = null,
        filter: MetadataFilter? = null,
    ): List<SearchResult<K>> {
        val searcher = pool.borrow()
        try {
            return lock.read {
                searcher.search(
                    query,
                    k,
                    efSearch ?: index.config.efSearch,
                    maxVisited ?: index.config.maxVisited,
                    filter,
                )
            }
        } finally {
            pool.giveBack(searcher)
        }
    }

    public suspend fun size(): Int = read { it.size }

    public suspend fun tombstones(): Int = read { it.tombstones }

    /** Rebuilds the graph; see [VectorIndex.compact]. Holds the lock exclusively for the rebuild. */
    public suspend fun compact(): Int = use { it.compact() }
}

/**
 * A [TextIndex] behind a readers-writer lock. Create with [concurrent]; see [ConcurrentVectorIndex]
 * for the
 * locking contract.
 */
public class ConcurrentTextIndex<K> internal constructor(
    private val index: TextIndex<K>,
) {
    private val lock = ReadWriteMutex()

    /**
     * Runs [block] against the index with the lock held **exclusively**. Do not let the index escape
     * the block. For reads, prefer [read].
     */
    public suspend fun <R> use(block: (TextIndex<K>) -> R): R = lock.write { block(index) }

    /** Runs a read-only [block] alongside other readers; nothing checks that it only reads. */
    public suspend fun <R> read(block: (TextIndex<K>) -> R): R = lock.read { block(index) }

    public suspend fun add(key: K, text: String, attributes: Map<String, String> = emptyMap()): Unit =
        use { it.add(key, text, attributes) }

    public suspend fun remove(key: K): Boolean = use { it.remove(key) }

    public suspend fun updateAttributes(key: K, attributes: Map<String, String>): Boolean =
        use { it.updateAttributes(key, attributes) }

    /**
     * Searches alongside other searches. BM25 retrieval builds its working state per call, so unlike
     * the vector side this needed no scratch of its own to become parallel.
     */
    public suspend fun search(query: String, k: Int, filter: MetadataFilter? = null): List<SearchResult<K>> =
        read { it.search(query, k, filter) }

    public suspend fun size(): Int = read { it.size }
}

/**
 * A [HybridIndex] behind a readers-writer lock. Create with [concurrent]; see [ConcurrentVectorIndex]
 * for the
 * locking contract.
 */
public class ConcurrentHybridIndex<K> internal constructor(
    private val index: HybridIndex<K>,
) {
    private val lock = ReadWriteMutex()
    private val pool = SearcherPool { index.searcher() }

    /**
     * Runs [block] against the index with the lock held **exclusively**. Do not let the index escape
     * the block. For reads, prefer [read].
     */
    public suspend fun <R> use(block: (HybridIndex<K>) -> R): R = lock.write { block(index) }

    /** Runs a read-only [block] alongside other readers; nothing checks that it only reads. */
    public suspend fun <R> read(block: (HybridIndex<K>) -> R): R = lock.read { block(index) }

    private suspend fun <R> searching(block: (HybridSearcher<K>) -> R): R {
        val searcher = pool.borrow()
        try {
            return lock.read { block(searcher) }
        } finally {
            pool.giveBack(searcher)
        }
    }

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
    ): List<SearchResult<K>> = searching {
        it.search(
            vector,
            text,
            k,
            candidates ?: maxOf(k * 4, 50),
            efSearch ?: index.hnswConfig.efSearch,
            maxVisited ?: index.hnswConfig.maxVisited,
            filter,
        )
    }

    public suspend fun searchVector(
        vector: FloatArray,
        k: Int,
        efSearch: Int? = null,
        maxVisited: Int? = null,
        filter: MetadataFilter? = null,
    ): List<SearchResult<K>> = searching {
        it.searchVector(
            vector,
            k,
            efSearch ?: index.hnswConfig.efSearch,
            maxVisited ?: index.hnswConfig.maxVisited,
            filter,
        )
    }

    public suspend fun searchText(text: String, k: Int, filter: MetadataFilter? = null): List<SearchResult<K>> =
        read { it.searchText(text, k, filter) }

    public suspend fun size(): Int = read { it.size }

    public suspend fun tombstones(): Int = read { it.tombstones }

    /** Rebuilds the vector graph; see [HybridIndex.compact]. Holds the lock for the whole rebuild. */
    public suspend fun compact(): Int = use { it.compact() }
}
