package io.github.kromus

/**
 * A reader that owns the state one graph traversal needs, so several searchers can query the same
 * [VectorIndex] at once. Create with [VectorIndex.searcher].
 *
 * Not thread-safe in itself — that is the point. One searcher serves one thread or coroutine; what
 * becomes parallel is *different* searchers running together. The index must not be written to while
 * any of them is searching.
 */
public class VectorSearcher<K> internal constructor(
    private val index: VectorIndex<K>,
) : Searcher<K> {
    private val scratch = SearchScratch()

    /** As [VectorIndex.search], with this searcher's own traversal state and the index's defaults. */
    override fun search(query: FloatArray, k: Int, filter: MetadataFilter?): List<SearchResult<K>> =
        search(query, k, index.config.efSearch, index.config.maxVisited, filter)

    /** As [VectorIndex.search], but using this searcher's own traversal state. */
    public fun search(
        query: FloatArray,
        k: Int,
        efSearch: Int,
        maxVisited: Int = index.config.maxVisited,
        filter: MetadataFilter? = null,
    ): List<SearchResult<K>> {
        require(query.size == index.dimensions) {
            "query has ${query.size} dimensions, expected ${index.dimensions}"
        }
        require(k >= 1) { "k must be >= 1, was $k" }
        return index.searchWith(scratch, query, k, efSearch, maxVisited, filter)
    }
}
