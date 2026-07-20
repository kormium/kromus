package io.github.kromus

/**
 * Okapi BM25 ranking parameters.
 *
 * @property k1 term-frequency saturation. Higher lets repeated terms keep adding weight; the common
 *   range is 1.2–2.0.
 * @property b length normalization, in `[0, 1]`. 0 disables it; 1 fully penalizes long documents.
 */
public data class Bm25Config(
    val k1: Float = 1.2f,
    val b: Float = 0.75f,
) {
    init {
        require(k1 >= 0f) { "k1 must be >= 0, was $k1" }
        require(b in 0f..1f) { "b must be in [0, 1], was $b" }
    }
}
