package io.github.kromus

/**
 * A reader that owns the state one graph traversal needs, so several searchers can query the same
 * [HybridIndex] at once. Create with [HybridIndex.searcher].
 *
 * Only the vector half needs this: BM25 retrieval already builds its working state per call and is
 * re-entrant as it stands, so the text side of a hybrid query was never the obstacle.
 *
 * Not thread-safe in itself — one searcher per thread or coroutine; what becomes parallel is
 * *different* searchers running together. The index must not be written to while any of them is
 * searching.
 */
public class HybridSearcher<K> internal constructor(
    private val index: HybridIndex<K>,
) {
    private val vectorSearcher: VectorSearcher<K> = index.vectorPart().searcher()

    /** As [HybridIndex.search], but using this searcher's own traversal state. */
    public fun search(
        vector: FloatArray,
        text: String,
        k: Int,
        candidates: Int = maxOf(k * 4, 50),
        efSearch: Int = index.hnswConfig.efSearch,
        maxVisited: Int = index.hnswConfig.maxVisited,
        filter: MetadataFilter? = null,
    ): List<SearchResult<K>> {
        require(k >= 1) { "k must be >= 1, was $k" }
        val vectorHits = vectorSearcher.search(vector, candidates, efSearch, maxVisited, filter).map { it.key }
        val textHits = index.searchText(text, candidates, filter).map { it.key }
        return Rrf.fuse(listOf(vectorHits, textHits), limit = k, k = index.rrfK)
    }

    /** As [HybridIndex.searchVector], but using this searcher's own traversal state. */
    public fun searchVector(
        vector: FloatArray,
        k: Int,
        efSearch: Int = index.hnswConfig.efSearch,
        maxVisited: Int = index.hnswConfig.maxVisited,
        filter: MetadataFilter? = null,
    ): List<SearchResult<K>> = vectorSearcher.search(vector, k, efSearch, maxVisited, filter)

    /** As [HybridIndex.searchText]. Needs no scratch: BM25 retrieval is already re-entrant. */
    public fun searchText(text: String, k: Int, filter: MetadataFilter? = null): List<SearchResult<K>> =
        index.searchText(text, k, filter)
}
