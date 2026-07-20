package io.github.kromus

/**
 * How the distance between two vectors is measured. This governs both graph construction and query
 * ranking, so an index must be queried with the same metric it was built with.
 *
 * [SearchResult.score] is always reported as a **similarity** (higher = closer), regardless of the
 * metric's internal distance:
 *
 * - [Cosine] — cosine similarity in `[-1, 1]`. Vectors are L2-normalized on insert and query, so
 *   magnitude is ignored; this is the right default for text/embedding search.
 * - [DotProduct] — raw inner product (maximum-inner-product search). Use when magnitude carries
 *   signal and vectors are not normalized.
 * - [Euclidean] — score is the negated L2 distance (`-sqrt(Σ(a-b)²)`), so higher still means closer.
 */
public enum class Metric {
    Cosine,
    DotProduct,
    Euclidean,
}
