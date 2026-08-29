package io.github.kromus

/**
 * Searches a [ClusteredIndex], holding the buffer a cluster is read into.
 *
 * For a resident index the buffer is unused. For one reading from a file it is the point: a probed
 * cluster arrives as one run of bytes, and reusing the space it lands in is the difference between a
 * hundred kilobytes of churn per query and none.
 *
 * Not thread-safe. One per thread or coroutine; what runs in parallel is different searchers.
 */
public class ClusterSearcher<K> internal constructor(
    private val index: ClusteredIndex<K>,
) {
    private var block = ByteArray(0)
    private var distances = FloatArray(0)

    /** As [ClusteredIndex.search], reusing this searcher's buffer. */
    public fun search(
        query: FloatArray,
        k: Int,
        nprobe: Int = index.nprobe,
        filter: MetadataFilter? = null,
    ): List<SearchResult<K>> {
        require(query.size == index.dimensions) {
            "query has ${query.size} dimensions, expected ${index.dimensions}"
        }
        require(k >= 1) { "k must be >= 1, was $k" }
        if (index.size == 0) return emptyList()

        val prepared = index.preparedQuery(query)
        val probes = index.nearestClusters(
            prepared,
            if (nprobe > index.clusters) index.clusters else nprobe,
        )

        val top = TopK<K>(k)
        val blocks = index.blocks
        for (c in probes) {
            val range = index.clusterRange(c)
            val count = range.last - range.first + 1
            if (count <= 0) continue
            if (blocks == null) {
                for (id in range) {
                    if (filter != null && !filter(index.attrsOf[id])) continue
                    // TopK keeps the largest and a smaller distance is a better hit.
                    top.offer(index.keyOf[id], -index.store.distanceToQuery(prepared, id).toDouble(), id)
                }
            } else {
                val stride = index.strideBytes
                val needed = count * stride
                if (block.size < needed) block = ByteArray(needed)
                if (distances.size < count) distances = FloatArray(count)
                blocks.read(range.first * stride, needed, block)
                BlockDistance.scan(
                    prepared,
                    block,
                    count,
                    index.dimensions,
                    index.metric,
                    index.config.quantization,
                    distances,
                )
                for (i in 0 until count) {
                    val id = range.first + i
                    if (filter != null && !filter(index.attrsOf[id])) continue
                    top.offer(index.keyOf[id], -distances[i].toDouble(), id)
                }
            }
        }
        return top.toSortedList().map { SearchResult(it.key, index.similarity((-it.score).toFloat())) }
    }
}
