package io.github.kromus

/**
 * How stored vectors are kept in memory. Quantization trades a little recall for a large cut in
 * footprint, which is what makes big corpora fit on a phone. Queries always run at full `Float`
 * precision against the compressed vectors (asymmetric distance), so accuracy loss stays small.
 *
 * - [None] — full 32-bit floats. Exact, ~`4 * dimensions` bytes per vector. The default.
 * - [Int8] — 8-bit scalar quantization with a per-vector scale, ~`dimensions + 4` bytes per vector
 *   (about 4× smaller). Recall usually stays within a handful of points of [None], depending on the
 *   data and how much search effort (`efSearch`) you spend.
 * - [Binary] — 1 bit per dimension (the sign), ~`dimensions / 8` bytes per vector (about 32× smaller).
 *   The graph is built on Hamming distance while queries stay full precision against the sign vectors
 *   (asymmetric). The coarsest option — best for Cosine/DotProduct on high-dimensional embeddings, and
 *   often paired with a full-precision re-rank of the top hits. Recall drops more than [Int8].
 */
public enum class Quantization {
    None,
    Int8,
    Binary,
}
