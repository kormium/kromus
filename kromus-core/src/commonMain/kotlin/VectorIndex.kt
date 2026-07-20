package io.github.kromus

/**
 * An in-memory, embeddable approximate-nearest-neighbour index mapping caller keys of type [K] to
 * dense vectors, backed by a pure-Kotlin HNSW graph.
 *
 * kromus is **embedder-agnostic**: you bring the vectors (from any on-device or server embedding
 * model) as [FloatArray]s of length [dimensions]; the index owns storage, graph construction and
 * `k`-NN retrieval — nothing else. It has zero third-party dependencies and the exact same behaviour
 * on JVM, Android, iOS, Native and the web, so an index built on one platform ranks identically on
 * another.
 *
 * ```
 * val index = VectorIndex<String>(dimensions = 384, metric = Metric.Cosine)
 * index.add("doc-1", embedding1)
 * index.add("doc-2", embedding2)
 * val hits = index.search(queryEmbedding, k = 10) // List<SearchResult<String>>, closest first
 * ```
 *
 * Keys are unique: re-[add]ing an existing key replaces its vector. Not thread-safe — mutate and
 * query from a single thread or under external synchronization.
 *
 * @param K the caller-facing identifier type. Must have stable `hashCode`/`equals`.
 * @property dimensions required length of every vector.
 * @property metric distance/similarity measure; see [Metric].
 * @property config HNSW tuning; see [HnswConfig].
 */
public class VectorIndex<K> private constructor(
    public val dimensions: Int,
    public val metric: Metric,
    public val config: HnswConfig,
    private val hnsw: Hnsw,
) {
    /** Creates an empty index. */
    public constructor(
        dimensions: Int,
        metric: Metric = Metric.Cosine,
        config: HnswConfig = HnswConfig(),
    ) : this(dimensions, metric, config, Hnsw(dimensions, metric, config))

    init {
        require(dimensions >= 1) { "dimensions must be >= 1, was $dimensions" }
    }

    // Bidirectional key <-> internal-id mapping. keyOf is id-indexed and grows once per HNSW insert
    // (ids are never reused); a removed or replaced slot is nulled out.
    private val idOf = HashMap<K, Int>()
    private val keyOf = ArrayList<K?>()

    /** Number of live (non-removed) entries. */
    public val size: Int get() = idOf.size

    public operator fun contains(key: K): Boolean = idOf.containsKey(key)

    /**
     * Inserts [vector] under [key], replacing any existing vector for that key. The array is copied.
     *
     * @throws IllegalArgumentException if `vector.size != dimensions`.
     */
    public fun add(key: K, vector: FloatArray) {
        require(vector.size == dimensions) {
            "vector has ${vector.size} dimensions, expected $dimensions"
        }
        idOf.remove(key)?.let { old ->
            hnsw.markDeleted(old)
            keyOf[old] = null
        }
        val id = hnsw.add(vector)
        // Every hnsw.add() appends exactly one id equal to the current capacity - 1, kept in lockstep
        // with keyOf, so the new id always lands at the end of keyOf.
        check(id == keyOf.size) { "index desynchronized: id=$id, keyOf.size=${keyOf.size}" }
        keyOf.add(key)
        idOf[key] = id
    }

    /**
     * Removes [key] if present. The underlying vector is flagged and stops appearing in results while
     * remaining a routing hop in the graph, so lookups stay correct and connected. Reclaiming that
     * space requires a rebuild (planned for a later version).
     *
     * @return true if [key] was present.
     */
    public fun remove(key: K): Boolean {
        val id = idOf.remove(key) ?: return false
        hnsw.markDeleted(id)
        keyOf[id] = null
        return true
    }

    /**
     * Returns up to [k] entries nearest to [query], closest first.
     *
     * @param efSearch dynamic candidate-list size for this query; larger trades latency for recall.
     *   Defaults to [HnswConfig.efSearch] and is raised to at least [k].
     * @throws IllegalArgumentException if `query.size != dimensions` or `k < 1`.
     */
    public fun search(
        query: FloatArray,
        k: Int,
        efSearch: Int = config.efSearch,
    ): List<SearchResult<K>> {
        require(query.size == dimensions) {
            "query has ${query.size} dimensions, expected $dimensions"
        }
        require(k >= 1) { "k must be >= 1, was $k" }
        return hnsw.query(query, k, efSearch).mapNotNull { (id, score) ->
            keyOf[id]?.let { SearchResult(it, score) }
        }
    }

    // --- persistence support (accessed by the encode/decode functions in Persistence.kt) ---

    internal fun graph(): Hnsw = hnsw

    /** Live key -> internal id, in iteration order. */
    internal fun liveEntries(): Map<K, Int> = idOf

    internal companion object {
        /** Rebuilds an index from a restored graph and its live key mapping. */
        fun <K> fromState(
            dimensions: Int,
            metric: Metric,
            config: HnswConfig,
            hnsw: Hnsw,
            liveKeys: Map<K, Int>,
            capacity: Int,
        ): VectorIndex<K> {
            val index = VectorIndex<K>(dimensions, metric, config, hnsw)
            repeat(capacity) { index.keyOf.add(null) }
            for ((key, id) in liveKeys) {
                index.idOf[key] = id
                index.keyOf[id] = key
            }
            return index
        }
    }
}
