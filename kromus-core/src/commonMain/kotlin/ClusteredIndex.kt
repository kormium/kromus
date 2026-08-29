package io.github.kromus

/**
 * Tuning for a [ClusteredIndex].
 *
 * @property clusters how many groups to split the corpus into. `0` picks `sqrt(n)`, the usual
 *   starting point: enough groups that each is small, few enough that comparing a query against every
 *   centroid stays cheap.
 * @property nprobe how many groups a query looks inside. The recall knob, and the honest one — every
 *   group it skips may hold a neighbour it will therefore never find.
 * @property seed seed for the clustering. Fixed by default, because the clustering decides the file's
 *   layout and kromus promises identical content encodes identically.
 * @property iterations k-means refinement passes. Fixed rather than run to convergence: convergence
 *   is a property of the data and of floating-point details that differ between targets, so stopping
 *   on it would make the result unreproducible.
 * @property quantization how stored vectors are compressed; see [Quantization].
 */
public data class ClusterConfig(
    val clusters: Int = 0,
    val nprobe: Int = 8,
    val seed: Long = 42L,
    val iterations: Int = 10,
    val quantization: Quantization = Quantization.None,
) {
    init {
        require(clusters >= 0) { "clusters must be >= 0 (0 = sqrt(n)), was $clusters" }
        require(nprobe >= 1) { "nprobe must be >= 1, was $nprobe" }
        require(iterations >= 1) { "iterations must be >= 1, was $iterations" }
    }
}

/** One entry handed to [ClusteredIndex.build]. */
public class ClusterEntry<K>(
    public val key: K,
    public val vector: FloatArray,
    public val attributes: Map<String, String> = emptyMap(),
)

/**
 * A vector index that groups its corpus into clusters instead of linking it into a graph.
 *
 * Where [VectorIndex] walks a graph — which is faster in memory and is what you want when the whole
 * index is resident — this one splits the corpus into groups and searches only the groups nearest the
 * query. The trade is deliberate and goes the other way on two axes:
 *
 * - **Recall costs more.** A group is a hard partition, so a neighbour just across a boundary is
 *   missed unless the query happens to probe that group too. A graph has no boundaries. Raising
 *   [ClusterConfig.nprobe] buys the recall back, linearly.
 * - **Locality is far better.** A group's vectors sit contiguously in the encoded bytes, so answering
 *   a query means reading a handful of runs rather than touching scattered nodes all over the file.
 *   That is what makes an index larger than memory tractable at all, and it is why this exists.
 *
 * **Built once, not grown.** There is no `add`: clustering needs the corpus in hand, and adding to a
 * clustering without redoing it drifts — entries pile into groups that no longer describe them. That
 * matches the case this is for, an index assembled on a server or in CI and shipped read-only. To
 * change the contents, build again.
 *
 * Searching is safe from any number of threads at once: a query allocates the little state it needs
 * and the index is only read.
 */
public class ClusteredIndex<K> internal constructor(
    public val dimensions: Int,
    public val metric: Metric,
    public val config: ClusterConfig,
    /** `clusterCount * dimensions` coordinates. */
    internal val centroids: FloatArray,
    internal val clusterCount: Int,
    /** Where each cluster's entries begin; `clusterCount + 1` long, so a cluster is `[c, c + 1)`. */
    internal val clusterStarts: IntArray,
    internal val store: VectorStore,
    internal val keyOf: List<K>,
    internal val attrsOf: List<Map<String, String>>,
) {
    private val idOf: Map<K, Int> = buildMap(keyOf.size) { keyOf.forEachIndexed { id, key -> put(key, id) } }

    public val size: Int get() = keyOf.size

    /** How many clusters the corpus was split into. */
    public val clusters: Int get() = clusterCount

    /** How many entries cluster [c] holds. */
    public fun clusterSize(c: Int): Int {
        require(c in 0 until clusterCount) { "cluster $c outside 0..${clusterCount - 1}" }
        return clusterStarts[c + 1] - clusterStarts[c]
    }

    /**
     * The clusters [query] would look inside, nearest first.
     *
     * Exposed because it is the answer to "what will this query actually read": each cluster is a
     * contiguous run in the encoded bytes, so this list *is* the read plan. Useful for sizing a cache,
     * for diagnosing a query that returns too little, and for measuring what a file-backed index costs
     * without guessing that clusters are evenly sized — k-means offers no such promise.
     */
    public fun probedClusters(query: FloatArray, nprobe: Int = config.nprobe): IntArray {
        require(query.size == dimensions) { "query has ${query.size} dimensions, expected $dimensions" }
        if (clusterCount == 0) return IntArray(0)
        return nearestClusters(prepare(query), if (nprobe > clusterCount) clusterCount else nprobe)
    }

    /** The keys, in the order the clustering laid them out — not the order they were supplied. */
    public val keys: Set<K> get() = idOf.keys

    public operator fun contains(key: K): Boolean = key in idOf

    /** The attributes stored with [key], or null if it is not in the index. */
    public fun attributesOf(key: K): Map<String, String>? = idOf[key]?.let { attrsOf[it] }

    /**
     * Returns up to [k] entries nearest to [query], closest first.
     *
     * @param nprobe how many clusters to look inside; defaults to [ClusterConfig.nprobe]. This is the
     *   recall knob: every cluster left unprobed may hold a neighbour that will not be found.
     * @param filter optional predicate over each entry's attributes, applied to candidates before
     *   they enter the result.
     */
    public fun search(
        query: FloatArray,
        k: Int,
        nprobe: Int = config.nprobe,
        filter: MetadataFilter? = null,
    ): List<SearchResult<K>> {
        require(query.size == dimensions) { "query has ${query.size} dimensions, expected $dimensions" }
        require(k >= 1) { "k must be >= 1, was $k" }
        if (size == 0) return emptyList()

        val prepared = prepare(query)
        val probes = nearestClusters(prepared, if (nprobe > clusterCount) clusterCount else nprobe)

        val top = TopK<K>(k)
        for (c in probes) {
            for (id in clusterStarts[c] until clusterStarts[c + 1]) {
                if (filter != null && !filter(attrsOf[id])) continue
                val distance = store.distanceToQuery(prepared, id)
                // TopK keeps the largest, and a smaller distance is a better hit.
                top.offer(keyOf[id], -distance.toDouble(), id)
            }
        }
        return top.toSortedList().map { SearchResult(it.key, similarityOf((-it.score).toFloat())) }
    }

    /** The [nprobe] clusters whose centroids sit closest to [prepared], nearest first. */
    private fun nearestClusters(prepared: FloatArray, nprobe: Int): IntArray {
        val distances = FloatArray(clusterCount) {
            Kmeans.distanceToCentroid(prepared, centroids, it * dimensions, dimensions, metric)
        }
        // clusterCount is small by construction — sqrt(n) — so a partial selection beats a sort only
        // marginally, and this keeps ties resolving by cluster index.
        val order = (0 until clusterCount).sortedWith(compareBy({ distances[it] }, { it }))
        return IntArray(nprobe) { order[it] }
    }

    private fun prepare(vector: FloatArray): FloatArray {
        if (metric != Metric.Cosine) return vector.copyOf()
        var norm = 0f
        for (x in vector) norm += x * x
        if (norm == 0f) return vector.copyOf()
        val inv = 1f / kotlin.math.sqrt(norm)
        return FloatArray(vector.size) { vector[it] * inv }
    }

    /** Mirrors [VectorIndex]'s reporting so scores mean the same thing across both index types. */
    private fun similarityOf(distance: Float): Float =
        when (metric) {
            Metric.Cosine -> 1f - distance
            Metric.DotProduct, Metric.Euclidean -> -distance
        }

    public companion object {
        /**
         * Clusters [entries] and builds an index over them.
         *
         * The entries are laid out cluster by cluster, so each cluster's vectors end up contiguous —
         * which is the whole point, and why the ids inside the index are not the order you supplied.
         */
        public fun <K> build(
            dimensions: Int,
            entries: List<ClusterEntry<K>>,
            metric: Metric = Metric.Cosine,
            config: ClusterConfig = ClusterConfig(),
        ): ClusteredIndex<K> {
            require(dimensions >= 1) { "dimensions must be >= 1, was $dimensions" }
            for (e in entries) {
                require(e.vector.size == dimensions) {
                    "entry '${e.key}' has ${e.vector.size} dimensions, expected $dimensions"
                }
            }

            val store = Hnsw.newStore(dimensions, metric, config.quantization)
            // Cluster on prepared vectors — the same form the store holds — so the centroids live in
            // the space distances are actually measured in.
            val prepared = entries.map { prepareFor(it.vector, metric) }
            val k = if (config.clusters > 0) config.clusters else defaultClusterCount(entries.size)
            val clustering = Kmeans.cluster(prepared, dimensions, k, metric, config.seed, config.iterations)

            // Stable within a cluster: entries keep their supplied order, so the layout depends on the
            // clustering and the input, and on nothing else.
            val order = (prepared.indices).sortedWith(compareBy({ clustering.assignment[it] }, { it }))
            val starts = IntArray(clustering.k + 1)
            for (i in prepared.indices) starts[clustering.assignment[i] + 1]++
            for (c in 1..clustering.k) starts[c] += starts[c - 1]

            val keyOf = ArrayList<K>(entries.size)
            val attrsOf = ArrayList<Map<String, String>>(entries.size)
            for (i in order) {
                store.add(prepared[i])
                keyOf.add(entries[i].key)
                attrsOf.add(entries[i].attributes)
            }

            return ClusteredIndex(
                dimensions,
                metric,
                config,
                clustering.centroids,
                clustering.k,
                starts,
                store,
                keyOf,
                attrsOf,
            )
        }

        /** `sqrt(n)`, the usual starting point: groups small enough to skip, few enough to scan. */
        internal fun defaultClusterCount(n: Int): Int =
            if (n <= 1) 1 else kotlin.math.sqrt(n.toFloat()).toInt().coerceAtLeast(1)

        private fun prepareFor(vector: FloatArray, metric: Metric): FloatArray {
            if (metric != Metric.Cosine) return vector.copyOf()
            var norm = 0f
            for (x in vector) norm += x * x
            if (norm == 0f) return vector.copyOf()
            val inv = 1f / kotlin.math.sqrt(norm)
            return FloatArray(vector.size) { vector[it] * inv }
        }
    }
}
