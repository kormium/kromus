package io.github.kromus

/**
 * Tuning for an [IvfIndex].
 *
 * @property clusters how many inverted lists to build — the coarse quantizer's size, written `nlist`
 *   in most of the literature. `0` picks `sqrt(n)`, the usual starting point: enough lists that each
 *   is small, few enough that comparing a query against every centroid stays cheap.
 * @property nprobe how many lists a query opens, or `0` to have [IvfIndex.build] measure
 *   the corpus and pick a value that reaches [targetRecall]. The recall knob, and the honest one —
 *   every group it skips may hold a neighbour it will therefore never find.
 *
 *   How many groups it takes is a property of the data rather than of any setting: a corpus that
 *   partitions cleanly needs one or two, a corpus with little group structure needs dozens. That is
 *   why the default measures instead of guessing — a number chosen blind is right for one kind of
 *   corpus and badly wrong for the other.
 * @property targetRecall what an automatically chosen [nprobe] aims for, as a fraction of the
 *   neighbours an exhaustive search would return. Ignored when [nprobe] is set explicitly.
 * @property assignments how many lists each vector is placed in. `1` is plain IVF, where a vector
 *   belongs to its nearest centroid and nothing else.
 *
 *   Raising it is the direct cure for the failure mode a partitioned index has and a graph does not: a
 *   vector near a boundary is missed by a query on the other side of it. Put that vector in both lists
 *   and the boundary stops existing for it. The cost is storage — a vector written into two lists is
 *   stored twice — which is the trade SPANN makes deliberately: disk is cheaper than the recall lost
 *   at a boundary, and the duplicate keeps each list contiguous, which pointers back to a single copy
 *   would not.
 * @property seed seed for the clustering. Fixed by default, because the clustering decides the file's
 *   layout and kromus promises identical content encodes identically.
 * @property iterations k-means refinement passes. Fixed rather than run to convergence: convergence
 *   is a property of the data and of floating-point details that differ between targets, so stopping
 *   on it would make the result unreproducible.
 * @property routing how a query finds the lists nearest to it; see [Routing].
 * @property quantization how stored vectors are compressed; see [Quantization].
 */
public data class IvfConfig(
    val clusters: Int = 0,
    val nprobe: Int = 0,
    val targetRecall: Float = 0.95f,
    val assignments: Int = 1,
    val routing: Routing = Routing.Auto,
    val seed: Long = 42L,
    val iterations: Int = 10,
    val quantization: Quantization = Quantization.None,
) {
    init {
        require(clusters >= 0) { "clusters must be >= 0 (0 = sqrt(n)), was $clusters" }
        require(nprobe >= 0) { "nprobe must be >= 0 (0 = measure it), was $nprobe" }
        require(targetRecall > 0f && targetRecall <= 1f) {
            "targetRecall must be in (0, 1], was $targetRecall"
        }
        require(assignments >= 1) { "assignments must be >= 1, was $assignments" }
        require(iterations >= 1) { "iterations must be >= 1, was $iterations" }
    }
}

/**
 * How a query finds the lists nearest to it.
 *
 * Comparing against every centroid is exact and, while there are few of them, free. It stops being
 * free as the count grows: at 65 536 lists it is itself a scan of 65 536 vectors, which is the work an
 * index exists to avoid. A graph over the centroids finds them in log time instead — the same
 * structure [VectorIndex] uses, applied to a much smaller set.
 *
 * The graph is approximate, so it adds a second place a neighbour can be missed. That is a good trade
 * only when the exact scan has become expensive, which is why [Auto] switches on the count rather than
 * always preferring one.
 */
public enum class Routing {
    /** Compare against every centroid. Exact, and cheap while the lists are few. */
    Linear,

    /** Navigate a graph over the centroids. Approximate, and the only thing that scales. */
    Graph,

    /** [Linear] while the centroids are few enough to scan, [Graph] beyond that. */
    Auto,
}

/**
 * Presets for [IvfConfig].
 */
public object IvfPresets {
    /**
     * The **SPANN** arrangement, as a configuration rather than a separate index type.
     *
     * SPANN is not a different structure from IVF — it is IVF with three specific choices, and naming
     * them together is more useful than reimplementing the parts around them:
     *
     * - **many small lists**, so a query reads little; [postingSize] sets how many entries each holds
     *   rather than fixing a list count, because what matters is the size of the read;
     * - **[Routing.Graph]**, because scanning that many centroids would itself be the expensive part;
     * - **redundant assignment**, so a vector near a boundary is in both lists and stops being lost to
     *   whichever side a query arrives from.
     *
     * The fourth choice — postings that live on disk rather than in memory — is not a setting but a
     * loader: see [openIvfIndex].
     *
     * Storage grows with [assignments]: a vector written into two lists is stored twice. That is the
     * trade the design makes on purpose, disk being cheaper than the recall lost at a boundary, and
     * the duplicate is what keeps each list a contiguous run.
     */
    public fun spann(
        entryCount: Int,
        postingSize: Int = 128,
        assignments: Int = 2,
        targetRecall: Float = 0.95f,
        quantization: Quantization = Quantization.Int8,
    ): IvfConfig {
        require(entryCount >= 0) { "entryCount must be >= 0, was $entryCount" }
        require(postingSize >= 1) { "postingSize must be >= 1, was $postingSize" }
        val lists = if (entryCount <= postingSize) 1 else entryCount / postingSize
        return IvfConfig(
            clusters = lists,
            targetRecall = targetRecall,
            assignments = assignments,
            routing = Routing.Graph,
            quantization = quantization,
        )
    }
}

/** One entry handed to [IvfIndex.build]. */
public class IvfEntry<K>(
    public val key: K,
    public val vector: FloatArray,
    public val attributes: Map<String, String> = emptyMap(),
)

/**
 * An **IVF** (inverted file) index: the corpus is partitioned by a coarse quantizer, and a query
 * searches only the partitions nearest to it.
 *
 * This is the standard structure of that name, in its standard parts — k-means centroids as the coarse
 * quantizer, one inverted list of entries per centroid, and `nprobe` lists opened per query — so what
 * is known about tuning IVF applies here unchanged.
 *
 * Where [VectorIndex] walks a graph — faster in memory, and what you want when the whole index is
 * resident — this one trades deliberately in the other direction on two axes:
 *
 * - **Recall costs more.** A list is a hard partition, so a neighbour just across a boundary is missed
 *   unless the query happens to open that list too. A graph has no boundaries. [IvfConfig.nprobe] buys
 *   the recall back, linearly.
 * - **Locality is far better.** A list's vectors sit contiguously in the encoded bytes, so answering a
 *   query means reading a handful of runs rather than touching scattered nodes all over the file. That
 *   is what makes an index larger than memory tractable at all, and it is why this exists.
 *
 * **Built once, not grown.** There is no `add`: the quantizer needs the corpus in hand, and adding to a
 * partitioning without redoing it drifts — entries pile into lists that no longer describe them. That
 * matches the case this is for, an index assembled on a server or in CI and shipped read-only. To
 * change the contents, build again.
 *
 * Searching is safe from any number of threads at once: nothing writes to the index, and each
 * [searcher] carries its own state.
 */
public class IvfIndex<K> internal constructor(
    override val dimensions: Int,
    override val metric: Metric,
    public val config: IvfConfig,
    /** `clusterCount * dimensions` coordinates. */
    internal val centroids: FloatArray,
    internal val clusterCount: Int,
    /** Where each cluster's entries begin; `clusterCount + 1` long, so a cluster is `[c, c + 1)`. */
    internal val clusterStarts: IntArray,
    internal val store: VectorStore,
    internal val keyOf: List<K>,
    internal val attrsOf: List<Map<String, String>>,
    /**
     * Which original entry each stored copy came from.
     *
     * With [IvfConfig.assignments] above one a vector is written into several lists, so two lists a
     * query opens can hold the same entry. This is how a search reports it once.
     */
    internal val originOf: IntArray,
    /**
     * A graph over the centroids, when the lists are numerous enough for scanning them all to cost
     * more than navigating them; null when a query compares against every centroid.
     */
    internal val router: VectorIndex<Int>?,
    /** Distinct entries, as opposed to stored copies of them. */
    internal val entryCount: Int,
    /**
     * Where vectors are read from when the index does not hold them, or null when it does.
     *
     * Set by [openIvfIndex]: the index keeps its centroids, cluster table and keys resident —
     * a few megabytes — and reads each probed cluster's vectors as a run when a query needs them.
     */
    internal val blocks: ByteSource?,
    /** Clusters a query probes unless told otherwise — measured at build time when not set. */
    public val nprobe: Int,
    /**
     * What fraction of an exhaustive search's neighbours [nprobe] was measured to recover, on a
     * sample of the corpus taken at build time.
     *
     * `NaN` when [nprobe] was set explicitly and nothing was measured.
     *
     * Optimistic by construction: the only queries available at build time are corpus points, which
     * sit inside clusters rather than between them. On a corpus that partitions cleanly it is
     * accurate; on one that barely partitions it runs a few points high. Either way it is worth
     * looking at — an index that must open most of its clusters to reach its target is telling you
     * its data has no group structure, and a graph will serve it better.
     */
    public val estimatedRecall: Float,
) : VectorSearch<K> {
    private val idOf: Map<K, Int> = buildMap(keyOf.size) { keyOf.forEachIndexed { id, key -> put(key, id) } }

    /** Distinct entries in the index. Stored copies may be more, when a vector sits in several lists. */
    override val size: Int get() = entryCount

    /** Stored vectors, counting a vector once per list it was placed in. */
    public val storedVectors: Int get() = keyOf.size

    /** True when a vector sits in more than one list, so a search can meet it twice. */
    internal val needsDeduplication: Boolean get() = storedVectors != entryCount

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
    public fun probedClusters(query: FloatArray, nprobe: Int = this.nprobe): IntArray {
        require(query.size == dimensions) { "query has ${query.size} dimensions, expected $dimensions" }
        if (clusterCount == 0) return IntArray(0)
        return nearestClusters(prepare(query), if (nprobe > clusterCount) clusterCount else nprobe)
    }

    /** The keys, in the order the clustering laid them out — not the order they were supplied. */
    override val keys: Set<K> get() = idOf.keys

    override operator fun contains(key: K): Boolean = key in idOf

    /** The attributes stored with [key], or null if it is not in the index. */
    public fun attributesOf(key: K): Map<String, String>? = idOf[key]?.let { attrsOf[it] }

    /**
     * Returns up to [k] entries nearest to [query], closest first.
     *
     * @param nprobe how many clusters to look inside; defaults to [IvfConfig.nprobe]. This is the
     *   recall knob: every cluster left unprobed may hold a neighbour that will not be found.
     * @param filter optional predicate over each entry's attributes, applied to candidates before
     *   they enter the result.
     */
    public fun search(
        query: FloatArray,
        k: Int,
        nprobe: Int,
        filter: MetadataFilter? = null,
    ): List<SearchResult<K>> = searcher().search(query, k, nprobe, filter)

    /**
     * A reader that keeps the buffer a streamed search needs, so repeated queries do not each
     * allocate one.
     *
     * For an index that holds its vectors this is a convenience; for one reading them from a file it
     * matters, because the buffer is a whole cluster wide. One searcher belongs to one thread at a
     * time — what runs in parallel is *different* searchers, and nothing writes to the index, so any
     * number of them may.
     */
    override fun searcher(): IvfSearcher<K> = IvfSearcher(this)

    internal fun similarity(distance: Float): Float = similarityOf(distance)

    internal fun preparedQuery(query: FloatArray): FloatArray = prepare(query)

    internal fun clusterRange(c: Int): IntRange = clusterStarts[c] until clusterStarts[c + 1]

    /** Bytes one stored vector occupies — the fixed stride the layout is built around. */
    internal val strideBytes: Int get() = store.strideBytes

    /** The same index, reading its vectors from [source] rather than holding them. */
    internal fun streaming(source: ByteSource): IvfIndex<K> =
        IvfIndex(
            dimensions,
            metric,
            config,
            centroids,
            clusterCount,
            clusterStarts,
            store,
            keyOf,
            attrsOf,
            originOf,
            router,
            entryCount,
            source,
            nprobe,
            estimatedRecall,
        )

    /** The [nprobe] lists whose centroids sit closest to [prepared], nearest first. */
    internal fun nearestClusters(prepared: FloatArray, nprobe: Int): IntArray {
        val graph = router
        if (graph != null) {
            // Ask the graph for more than is needed: it is approximate, and a centroid it ranks
            // slightly wrong costs a list that should have been opened.
            val hits = graph.search(prepared, nprobe, efSearch = if (nprobe * 2 > 32) nprobe * 2 else 32)
            if (hits.size >= nprobe) return IntArray(nprobe) { hits[it].key }
            // A short result would quietly open fewer lists than asked for; fall through to the exact
            // scan rather than return less than the caller believes they are getting.
        }
        val distances = FloatArray(clusterCount) {
            Kmeans.distanceToCentroid(prepared, centroids, it * dimensions, dimensions, metric)
        }
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
            entries: List<IvfEntry<K>>,
            metric: Metric = Metric.Cosine,
            config: IvfConfig = IvfConfig(),
            store: VectorStoreFactory? = null,
        ): IvfIndex<K> {
            require(dimensions >= 1) { "dimensions must be >= 1, was $dimensions" }
            for (e in entries) {
                require(e.vector.size == dimensions) {
                    "entry '${e.key}' has ${e.vector.size} dimensions, expected $dimensions"
                }
            }

            // A supplied factory wins over the built-in quantizations; the same one has to be given
            // again when the index is read, since nothing in the bytes names it.
            val store = store?.create(dimensions, metric) ?: Hnsw.newStore(dimensions, metric, config.quantization)
            // Cluster on prepared vectors — the same form the store holds — so the centroids live in
            // the space distances are actually measured in.
            val prepared = entries.map { prepareFor(it.vector, metric) }
            val k = if (config.clusters > 0) config.clusters else defaultClusterCount(entries.size)
            val clustering = Kmeans.cluster(prepared, dimensions, k, metric, config.seed, config.iterations)

            // With assignments > 1 an entry is written into several lists. It is stored once per
            // list rather than referenced, because a reference would scatter the read that keeping
            // lists contiguous exists to avoid — the duplicate is what preserves the run.
            val copies = if (config.assignments > clustering.k) clustering.k else config.assignments
            val postings = ArrayList<IntArray>(clustering.k)
            repeat(clustering.k) { postings.add(IntArray(0)) }
            val perCluster = Array(clustering.k) { ArrayList<Int>() }
            for (i in prepared.indices) {
                for (c in nearestCentroids(prepared[i], clustering, dimensions, metric, copies)) {
                    perCluster[c].add(i)
                }
            }

            val starts = IntArray(clustering.k + 1)
            for (c in 0 until clustering.k) starts[c + 1] = starts[c] + perCluster[c].size

            val total = starts[clustering.k]
            val keyOf = ArrayList<K>(total)
            val attrsOf = ArrayList<Map<String, String>>(total)
            // Where each stored copy came from, so a query that opens two lists holding the same entry
            // reports it once.
            val originOf = IntArray(total)
            var at = 0
            for (c in 0 until clustering.k) {
                // Stable within a list: entries keep their supplied order, so the layout depends on
                // the clustering and the input and on nothing else.
                for (i in perCluster[c]) {
                    store.add(prepared[i])
                    keyOf.add(entries[i].key)
                    attrsOf.add(entries[i].attributes)
                    originOf[at++] = i
                }
            }

            // A graph over the centroids is itself an index, so it earns its place only once
            // scanning them all has become the expensive part.
            val useGraph = when (config.routing) {
                Routing.Linear -> false
                Routing.Graph -> true
                Routing.Auto -> clustering.k > GRAPH_ROUTING_FROM
            }
            val router = if (!useGraph || clustering.k < 2) {
                null
            } else {
                VectorIndex<Int>(dimensions, metric).also { graph ->
                    for (c in 0 until clustering.k) {
                        graph.add(c, FloatArray(dimensions) { clustering.centroids[c * dimensions + it] })
                    }
                }
            }

            val measured = if (config.nprobe > 0) {
                config.nprobe to Float.NaN
            } else {
                chooseNprobe(store, clustering, starts, originOf, dimensions, metric, config.targetRecall)
            }

            return IvfIndex(
                dimensions,
                metric,
                config,
                clustering.centroids,
                clustering.k,
                starts,
                store,
                keyOf,
                attrsOf,
                originOf,
                router,
                entries.size,
                blocks = null,
                nprobe = measured.first,
                estimatedRecall = measured.second,
            )
        }

        /**
         * Lists beyond which [Routing.Auto] navigates rather than scans.
         *
         * Below this the exact scan costs less than the graph traversal that would replace it, and is
         * exact into the bargain; above it the scan is itself the kind of work an index exists to
         * avoid.
         */
        internal const val GRAPH_ROUTING_FROM: Int = 2048

        /** How many sample queries the automatic choice is measured over. */
        private const val PROBE_SAMPLES = 64

        /** Neighbours per sample the automatic choice tries to recover. */
        private const val PROBE_K = 10

        /**
         * Picks the smallest `nprobe` that recovers [targetRecall] of an exhaustive search, measured
         * on the corpus itself.
         *
         * A blind default cannot be right here: how many clusters a query must open is a property of
         * the data. A corpus that partitions cleanly needs one; a corpus that barely partitions needs
         * dozens, and would silently return half its neighbours at the same setting.
         *
         * The whole curve comes out of a single pass. For a sample query, rank every cluster by
         * distance to its centroid, then ask where each of its true neighbours sits in that ranking:
         * recall at `nprobe` is simply the share of neighbours ranked inside the first `nprobe`. No
         * search has to be run per candidate value.
         *
         * Samples are taken at a fixed stride rather than at random, so the choice is as reproducible
         * as everything else that decides the layout.
         *
         * **The estimate is optimistic, and knowingly so.** The only queries available at build time
         * are the corpus itself, and a corpus point is an easier query than a real one: it sits inside
         * a cluster rather than between them, where a boundary can fall between a query and its
         * neighbours. Its own trivial self-match is excluded, which removes the largest part of the
         * bias, but not all of it — measured against held-out queries on a corpus with little cluster
         * structure, a target of 0.95 lands near 0.89.
         *
         * Attempts to make the samples harder all need an arbitrary constant (how far from a point is
         * a realistic query?), and a wrong constant is worse than a known bias. So the shortfall is
         * reported through [estimatedRecall] rather than papered over: on a corpus where the automatic
         * choice has to open most of the clusters, treat the number as an upper bound and measure on
         * your own queries.
         */
        private fun chooseNprobe(
            store: VectorStore,
            clustering: Clustering,
            starts: IntArray,
            originOf: IntArray,
            dimensions: Int,
            metric: Metric,
            targetRecall: Float,
        ): Pair<Int, Float> {
            val n = store.size
            val k = clustering.k
            if (n == 0 || k <= 1) return 1 to 1f

            val clusterOf = IntArray(n)
            for (c in 0 until k) {
                for (id in starts[c] until starts[c + 1]) clusterOf[id] = c
            }
            // With redundant assignment a stored copy is not an entry: the same entry appears several
            // times, and counting its copies as separate neighbours would make the corpus look far
            // better partitioned than it is.
            // Copies of an entry, grouped once rather than searched for per neighbour per sample.
            val copiesOf = HashMap<Int, MutableList<Int>>()
            for (id in 0 until n) copiesOf.getOrPut(originOf[id]) { ArrayList(2) }.add(id)

            val sampleCount = if (n < PROBE_SAMPLES) n else PROBE_SAMPLES
            val stride = n / sampleCount
            // ranksAt[r] counts neighbours whose cluster was ranked r-th; the running sum over r is
            // then the recall at nprobe = r + 1.
            val ranksAt = IntArray(k)
            var neighbours = 0

            val distances = FloatArray(n)
            val clusterDistance = FloatArray(k)
            for (s in 0 until sampleCount) {
                val anchor = s * stride
                val query = store.reconstruct(anchor)
                for (i in 0 until n) distances[i] = store.distanceToQuery(query, i)
                // The anchor is its own nearest neighbour at distance zero, inside the cluster its
                // query probes first. Counting it would buy a hit in ten for free.
                val truth = (0 until n)
                    .sortedWith(compareBy({ distances[it] }, { it }))
                    .filter { it != anchor && copiesOf[originOf[it]]!!.first() == it }
                    .take(PROBE_K)

                for (c in 0 until k) {
                    clusterDistance[c] =
                        Kmeans.distanceToCentroid(query, clustering.centroids, c * dimensions, dimensions, metric)
                }
                val order = (0 until k).sortedWith(compareBy({ clusterDistance[it] }, { it }))
                val rankOf = IntArray(k)
                order.forEachIndexed { rank, cluster -> rankOf[cluster] = rank }

                for (id in truth) {
                    // An entry may sit in several lists; it is found as soon as the *first* of them is
                    // opened, so its rank is the best among its copies.
                    var best = k - 1
                    for (copy in copiesOf[originOf[id]]!!) {
                        val rank = rankOf[clusterOf[copy]]
                        if (rank < best) best = rank
                    }
                    ranksAt[best]++
                    neighbours++
                }
            }

            var covered = 0
            for (r in 0 until k) {
                covered += ranksAt[r]
                val recall = covered.toFloat() / neighbours
                if (recall >= targetRecall) return (r + 1) to recall
            }
            // Even probing everything fell short, which only happens when the sample is degenerate.
            return k to covered.toFloat() / neighbours
        }

        /** The [count] centroids nearest [vector], nearest first; ties to the lower index. */
        private fun nearestCentroids(
            vector: FloatArray,
            clustering: Clustering,
            dimensions: Int,
            metric: Metric,
            count: Int,
        ): IntArray {
            if (count >= clustering.k) return IntArray(clustering.k) { it }
            val distances = FloatArray(clustering.k) {
                Kmeans.distanceToCentroid(vector, clustering.centroids, it * dimensions, dimensions, metric)
            }
            val order = (0 until clustering.k).sortedWith(compareBy({ distances[it] }, { it }))
            return IntArray(count) { order[it] }
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
