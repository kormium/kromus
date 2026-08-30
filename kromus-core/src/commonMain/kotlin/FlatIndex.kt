package io.github.kromus

/**
 * Exhaustive search: every query is compared against every vector.
 *
 * No graph, no partitioning, no approximation — the results are the true nearest neighbours, always.
 * That sounds like the thing an index exists to avoid, and past a certain size it is. Below it, it is
 * the right answer:
 *
 * - **It is faster than an index on a small corpus.** A few thousand vectors scanned contiguously beat
 *   a graph traversal that jumps between nodes, and nothing has to be built first.
 * - **It is exact**, so it is what recall is measured *against*. Having it in the library rather than
 *   only in a benchmark means you can check an approximate index on your own data.
 * - **It has nothing to tune**, so it answers "do I even need an index yet" without a study.
 *
 * The cost is linear in the corpus: doubling it doubles every query. [VectorIndex] beats it from a few
 * thousand vectors upward and the gap only widens — 50 000 vectors take about 13 ms a query here
 * against 167 µs for the graph.
 *
 * Like [IvfIndex] this is built rather than grown, and can read its vectors from [ByteSource] rather
 * than hold them.
 */
public class FlatIndex<K> internal constructor(
    override val dimensions: Int,
    override val metric: Metric,
    public val quantization: Quantization,
    internal val store: VectorStore,
    internal val keyOf: List<K>,
    internal val attrsOf: List<Map<String, String>>,
    internal val blocks: ByteSource?,
) : VectorSearch<K> {
    private val idOf: Map<K, Int> = buildMap(keyOf.size) { keyOf.forEachIndexed { id, key -> put(key, id) } }

    override val size: Int get() = keyOf.size

    override val keys: Set<K> get() = idOf.keys

    override operator fun contains(key: K): Boolean = key in idOf

    public fun attributesOf(key: K): Map<String, String>? = idOf[key]?.let { attrsOf[it] }

    /** A reader with its own buffer; see [IvfIndex.searcher] for why that matters when streaming. */
    override fun searcher(): FlatSearcher<K> = FlatSearcher(this)

    internal val strideBytes: Int get() = store.strideBytes

    internal fun similarity(distance: Float): Float =
        when (metric) {
            Metric.Cosine -> 1f - distance
            Metric.DotProduct, Metric.Euclidean -> -distance
        }

    internal fun prepare(vector: FloatArray): FloatArray {
        if (metric != Metric.Cosine) return vector.copyOf()
        var norm = 0f
        for (x in vector) norm += x * x
        if (norm == 0f) return vector.copyOf()
        val inv = 1f / kotlin.math.sqrt(norm)
        return FloatArray(vector.size) { vector[it] * inv }
    }

    /** The same index, reading its vectors from [source] rather than holding them. */
    internal fun streaming(source: ByteSource): FlatIndex<K> =
        FlatIndex(dimensions, metric, quantization, store, keyOf, attrsOf, source)

    public companion object {
        /** Builds an exhaustive index over [entries]. Nothing is constructed beyond storing them. */
        public fun <K> build(
            dimensions: Int,
            entries: List<IvfEntry<K>>,
            metric: Metric = Metric.Cosine,
            quantization: Quantization = Quantization.None,
            store: VectorStoreFactory? = null,
        ): FlatIndex<K> {
            require(dimensions >= 1) { "dimensions must be >= 1, was $dimensions" }
            // A supplied factory wins over the built-in quantizations; the same one has to be given
            // again when the index is read, since nothing in the bytes names it.
            val store = store?.create(dimensions, metric) ?: Hnsw.newStore(dimensions, metric, quantization)
            val keyOf = ArrayList<K>(entries.size)
            val attrsOf = ArrayList<Map<String, String>>(entries.size)
            for (e in entries) {
                require(e.vector.size == dimensions) {
                    "entry '${e.key}' has ${e.vector.size} dimensions, expected $dimensions"
                }
                val prepared = if (metric == Metric.Cosine) normalize(e.vector) else e.vector.copyOf()
                store.add(prepared)
                keyOf.add(e.key)
                attrsOf.add(e.attributes)
            }
            return FlatIndex(dimensions, metric, quantization, store, keyOf, attrsOf, blocks = null)
        }

        private fun normalize(vector: FloatArray): FloatArray {
            var norm = 0f
            for (x in vector) norm += x * x
            if (norm == 0f) return vector.copyOf()
            val inv = 1f / kotlin.math.sqrt(norm)
            return FloatArray(vector.size) { vector[it] * inv }
        }
    }
}

/**
 * Searches a [FlatIndex], holding the buffer a streamed scan reads into.
 *
 * Not thread-safe. One per thread; different searchers run in parallel, and nothing writes to the
 * index.
 */
public class FlatSearcher<K> internal constructor(
    private val index: FlatIndex<K>,
) : Searcher<K> {
    private var block = ByteArray(0)
    private var distances = FloatArray(0)

    /** How many entries a streamed scan reads at a time. Bounds the buffer on a large corpus. */
    private val batch = 1024

    /**
     * Returns the [k] entries nearest to [query], closest first. Exact — these *are* the nearest
     * neighbours, not an approximation of them.
     */
    override fun search(query: FloatArray, k: Int, filter: MetadataFilter?): List<SearchResult<K>> {
        require(query.size == index.dimensions) {
            "query has ${query.size} dimensions, expected ${index.dimensions}"
        }
        require(k >= 1) { "k must be >= 1, was $k" }
        if (index.size == 0) return emptyList()

        val prepared = index.prepare(query)
        val top = TopK<K>(k)
        val blocks = index.blocks
        if (blocks == null) {
            for (id in 0 until index.size) {
                if (filter != null && !filter(index.attrsOf[id])) continue
                // TopK keeps the largest and a smaller distance is a better hit.
                top.offer(index.keyOf[id], -index.store.distanceToQuery(prepared, id).toDouble(), id)
            }
        } else {
            // Read in batches rather than whole: a flat index has no partitions to bound the buffer,
            // and pulling a hundred megabytes into one array would defeat streaming entirely.
            val stride = index.strideBytes
            if (block.size < batch * stride) block = ByteArray(batch * stride)
            if (distances.size < batch) distances = FloatArray(batch)
            var from = 0
            while (from < index.size) {
                val count = if (index.size - from < batch) index.size - from else batch
                blocks.read(from * stride, count * stride, block)
                BlockDistance.scan(
                    prepared,
                    block,
                    count,
                    index.dimensions,
                    index.metric,
                    index.quantization,
                    distances,
                )
                for (i in 0 until count) {
                    val id = from + i
                    if (filter != null && !filter(index.attrsOf[id])) continue
                    top.offer(index.keyOf[id], -distances[i].toDouble(), id)
                }
                from += count
            }
        }
        return top.toSortedList().map { SearchResult(it.key, index.similarity(-it.score)) }
    }
}
