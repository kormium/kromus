package io.github.kromus

/**
 * A single hit from [VectorIndex.search].
 *
 * @property key the caller-supplied key of the matched item.
 * @property score similarity to the query — **higher is closer**. See [Metric] for the exact scale
 *   of each metric.
 */
public data class SearchResult<out K>(
    val key: K,
    val score: Float,
)
