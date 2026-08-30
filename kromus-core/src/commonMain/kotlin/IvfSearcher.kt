package io.github.kromus

/**
 * Searches a [IvfIndex], holding the buffer a cluster is read into.
 *
 * For a resident index the buffer is unused. For one reading from a file it is the point: a probed
 * cluster arrives as one run of bytes, and reusing the space it lands in is the difference between a
 * hundred kilobytes of churn per query and none.
 *
 * Not thread-safe. One per thread or coroutine; what runs in parallel is different searchers.
 */
public class IvfSearcher<K> internal constructor(
    private val index: IvfIndex<K>,
) : Searcher<K> {
    private var block = ByteArray(0)
    private var distances = FloatArray(0)

    // A vector placed in several lists is stored once per list, so two lists a query opens can hold
    // the same entry. Marks plus a monotonic epoch report it once without clearing anything between
    // queries — the same trick the graph traversal uses for its visited set.
    private var seen = IntArray(0)
    private var epoch = 0

    /** Searches with the index's own probe count, reusing this searcher's buffer. */
    override fun search(query: FloatArray, k: Int, filter: MetadataFilter?): List<SearchResult<K>> =
        search(query, k, index.nprobe, filter)

    /** As [IvfIndex.search], reusing this searcher's buffer. */
    public fun search(
        query: FloatArray,
        k: Int,
        nprobe: Int,
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
        if (index.needsDeduplication) {
            if (seen.size < index.size) seen = IntArray(index.size)
            if (epoch == Int.MAX_VALUE) {
                seen.fill(0)
                epoch = 0
            }
            epoch++
        }
        val blocks = index.blocks
        for (c in probes) {
            val range = index.clusterRange(c)
            val count = range.last - range.first + 1
            if (count <= 0) continue
            if (blocks == null) {
                for (id in range) {
                    if (!admit(id)) continue
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
                    if (!admit(id)) continue
                    if (filter != null && !filter(index.attrsOf[id])) continue
                    top.offer(index.keyOf[id], -distances[i].toDouble(), id)
                }
            }
        }
        return top.toSortedList().map { SearchResult(it.key, index.similarity(-it.score)) }
    }

    /** False when this stored copy's entry has already been offered by an earlier list. */
    private fun admit(id: Int): Boolean {
        if (!index.needsDeduplication) return true
        val origin = index.originOf[id]
        if (seen[origin] == epoch) return false
        seen[origin] = epoch
        return true
    }
}
