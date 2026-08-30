package io.github.kromus

/**
 * What every vector index in kromus can do, so calling code need not name which one it has.
 *
 * The three that ship — [FlatIndex], [VectorIndex] and [IvfIndex] — answer the same question and
 * differ only in what they trade to answer it. Which one is right depends on the corpus and on where
 * the index lives, and that is a decision worth being able to revisit without rewriting everything
 * around it: build behind this interface and the choice becomes configuration.
 *
 * It is also the seam a fourth implementation writes against. Nothing here assumes a graph or a
 * partitioning — an index built some other way satisfies it by answering queries, and [ContainerWriter]
 * offers the same file format, section checksums and provenance the built-in ones use.
 *
 * Read-only. Whether an index can be added to is a property of the implementation, not of searching it.
 */
public interface VectorSearch<K> {
    /** Length every vector and query must have. */
    public val dimensions: Int

    /** How distance between vectors is measured. */
    public val metric: Metric

    /** Number of entries that can be returned. */
    public val size: Int

    /** The keys held, in whatever order the implementation stores them. */
    public val keys: Set<K>

    public operator fun contains(key: K): Boolean

    /**
     * A reader carrying whatever per-query state this implementation needs.
     *
     * Searching goes through a searcher rather than the index itself because the state some
     * implementations need is not free to allocate — a streamed index reads a whole list into a buffer
     * — and because it is what makes concurrent reads possible: one searcher per thread or coroutine,
     * running against an index nothing writes to.
     */
    public fun searcher(): Searcher<K>
}

/** A reader over a [VectorSearch]; see [VectorSearch.searcher]. */
public interface Searcher<K> {
    /**
     * Returns up to [k] entries nearest to [query], closest first.
     *
     * @param filter optional predicate over each entry's attributes; only entries it accepts are
     *   returned.
     * @throws IllegalArgumentException if `query.size != dimensions` or `k < 1`.
     */
    public fun search(query: FloatArray, k: Int, filter: MetadataFilter? = null): List<SearchResult<K>>
}

/**
 * Searches through a fresh [Searcher].
 *
 * Convenient for a one-off query. For repeated ones hold a [VectorSearch.searcher] instead — that is
 * what lets an implementation reuse its buffers rather than allocate per query.
 */
public fun <K> VectorSearch<K>.search(
    query: FloatArray,
    k: Int,
    filter: MetadataFilter? = null,
): List<SearchResult<K>> = searcher().search(query, k, filter)
