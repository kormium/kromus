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
    private var hnsw: Hnsw,
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
    // (ids are never reused); a removed or replaced slot is nulled out. attrsOf is id-indexed too.
    private val idOf = HashMap<K, Int>()
    private val keyOf = ArrayList<K?>()
    private val attrsOf = ArrayList<Map<String, String>>()

    /** Number of live (non-removed) entries. */
    public val size: Int get() = idOf.size

    /**
     * Slots held by removed or replaced entries — memory and traversal cost the index still pays.
     * See [compact].
     */
    public val tombstones: Int get() = hnsw.tombstones

    /** The live keys, in insertion order of their current vectors. Read-only; do not retain across edits. */
    public val keys: Set<K> get() = idOf.keys

    public operator fun contains(key: K): Boolean = idOf.containsKey(key)

    /**
     * Inserts [vector] under [key], replacing any existing vector for that key. The array is copied.
     * Optional [attributes] are stored with the entry and can be used to restrict later searches (see
     * the `filter` parameter of [search]).
     *
     * @throws IllegalArgumentException if `vector.size != dimensions`.
     */
    public fun add(key: K, vector: FloatArray, attributes: Map<String, String> = emptyMap()) {
        require(vector.size == dimensions) {
            "vector has ${vector.size} dimensions, expected $dimensions"
        }
        idOf.remove(key)?.let { old ->
            hnsw.markDeleted(old)
            keyOf[old] = null
        }
        val id = hnsw.add(vector)
        // Every hnsw.add() appends exactly one id equal to the current capacity - 1, kept in lockstep
        // with keyOf/attrsOf, so the new id always lands at the end of both.
        check(id == keyOf.size) { "index desynchronized: id=$id, keyOf.size=${keyOf.size}" }
        keyOf.add(key)
        attrsOf.add(attributes)
        idOf[key] = id
    }

    /**
     * Removes [key] if present. The underlying vector is flagged and stops appearing in results while
     * remaining a routing hop in the graph, so lookups stay correct and connected. Reclaiming that
     * space requires [compact].
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
     * @param maxVisited cap on nodes touched by this query; `0` means the whole index. Bounds the
     *   worst case of a very selective [filter] at the price of possibly fewer than [k] results —
     *   see [HnswConfig.maxVisited].
     * @param filter optional predicate over each entry's [attributes][add]; only entries it accepts
     *   are returned. Applied during traversal, so a filtered query still yields up to [k] matches
     *   (a very selective filter benefits from a larger [efSearch]).
     * @throws IllegalArgumentException if `query.size != dimensions` or `k < 1`.
     */
    public fun search(
        query: FloatArray,
        k: Int,
        efSearch: Int = config.efSearch,
        maxVisited: Int = config.maxVisited,
        filter: MetadataFilter? = null,
    ): List<SearchResult<K>> {
        require(query.size == dimensions) {
            "query has ${query.size} dimensions, expected $dimensions"
        }
        require(k >= 1) { "k must be >= 1, was $k" }
        val accept: (Int) -> Boolean = if (filter == null) { { true } } else { { id -> filter(attrsOf[id]) } }
        val hits = hnsw.query(query, k, efSearch, maxVisited, accept)
        val out = ArrayList<SearchResult<K>>(hits.size)
        for (i in 0 until hits.size) {
            val key = keyOf[hits.ids[i]] ?: continue
            out.add(SearchResult(key, hits.scores[i]))
        }
        return out
    }

    /**
     * Replaces the [attributes][add] stored for [key] without touching the graph.
     *
     * Re-[add]ing an entry just to change a metadata value would insert a whole new node and leave a
     * tombstone behind; this does not. Prefer it whenever the vector itself has not changed.
     *
     * @return true if [key] was present.
     */
    public fun updateAttributes(key: K, attributes: Map<String, String>): Boolean {
        val id = idOf[key] ?: return false
        attrsOf[id] = attributes
        return true
    }

    /**
     * Returns the vector stored for [key], or null if absent.
     *
     * The vector comes back in *stored* form: L2-normalized when the metric is [Metric.Cosine], and
     * dequantized (so, approximate) when [HnswConfig.quantization] is not [Quantization.None]. Handy
     * for feeding an unquantized index's own vectors to [rerank] instead of keeping a second copy.
     */
    public fun vectorOf(key: K): FloatArray? {
        val id = idOf[key] ?: return null
        return hnsw.store().reconstruct(id)
    }

    /**
     * Rebuilds the graph over the live entries only, dropping every tombstone left by [remove] and by
     * re-[add]ing an existing key.
     *
     * Those tombstones are not free: each keeps a vector in memory and stays in the graph as a routing
     * hop, so a long-lived index over changing data grows and its queries slow down. Compaction is the
     * reclaim step.
     *
     * No vector data is lost or re-approximated: entries are reinserted in their stored form, so
     * quantized codes come through a rebuild bit-exact. With [Quantization.None] the result is
     * byte-identical to an index built from the same live entries in the same order. With a quantized
     * store the new graph is built from those stored vectors where the original build used the
     * full-precision originals, so its links can differ marginally — the rebuild is deterministic
     * either way.
     *
     * The cost is a full rebuild (comparable to the original build), so call it on churn, not per edit:
     * a common trigger is `tombstones > size / 2` at app start or after a bulk sync.
     *
     * @return the number of reclaimed slots.
     */
    public fun compact(): Int {
        val reclaimed = hnsw.tombstones
        if (reclaimed == 0) return 0

        val oldStore = hnsw.store()
        val fresh = Hnsw(dimensions, metric, config)
        val liveCount = idOf.size
        val newKeyOf = ArrayList<K?>(liveCount)
        val newAttrs = ArrayList<Map<String, String>>(liveCount)
        val newIdOf = HashMap<K, Int>(liveCount * 2)

        // Ascending old id — i.e. insertion order — so the rebuild does not depend on hash iteration
        // order and stays identical on every platform.
        for (oldId in 0 until keyOf.size) {
            val key = keyOf[oldId] ?: continue
            val newId = fresh.addPrepared(oldStore.reconstruct(oldId))
            newIdOf[key] = newId
            newKeyOf.add(key)
            newAttrs.add(attrsOf[oldId])
        }

        hnsw = fresh
        idOf.clear()
        idOf.putAll(newIdOf)
        keyOf.clear()
        keyOf.addAll(newKeyOf)
        attrsOf.clear()
        attrsOf.addAll(newAttrs)
        return reclaimed
    }

    /** Removes every entry and releases the graph. */
    public fun clear() {
        hnsw = Hnsw(dimensions, metric, config)
        idOf.clear()
        keyOf.clear()
        attrsOf.clear()
    }

    // --- persistence support (accessed by the encode/decode functions in Persistence.kt) ---

    internal fun graph(): Hnsw = hnsw

    /** Live key -> internal id, in iteration order. */
    internal fun liveEntries(): Map<K, Int> = idOf

    /** The key at internal [id], or null if that slot is a tombstone. */
    internal fun keyAt(id: Int): K? = keyOf[id]

    internal fun attributesAt(id: Int): Map<String, String> = attrsOf[id]

    internal companion object {
        /** Rebuilds an index from a restored graph, its live key mapping and per-id attributes. */
        fun <K> fromState(
            dimensions: Int,
            metric: Metric,
            config: HnswConfig,
            hnsw: Hnsw,
            liveKeys: Map<K, Int>,
            liveAttrs: Map<Int, Map<String, String>>,
            capacity: Int,
        ): VectorIndex<K> {
            val index = VectorIndex<K>(dimensions, metric, config, hnsw)
            repeat(capacity) {
                index.keyOf.add(null)
                index.attrsOf.add(emptyMap())
            }
            for ((key, id) in liveKeys) {
                index.idOf[key] = id
                index.keyOf[id] = key
            }
            for ((id, attrs) in liveAttrs) {
                index.attrsOf[id] = attrs
            }
            return index
        }
    }
}
