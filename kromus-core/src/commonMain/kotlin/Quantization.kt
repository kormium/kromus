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
 */
public enum class Quantization {
    None,
    Int8,
}
