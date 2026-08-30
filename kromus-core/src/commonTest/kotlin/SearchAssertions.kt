package io.github.kromus

import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Two searches must return the same neighbours in the same order, and scores that agree to float
 * precision.
 *
 * Order is held exactly, because the order is what a caller consumes. Scores are held to a tolerance
 * for one reason: on Kotlin/JS a `Float` is a JS number — a double — until it crosses a Float32
 * boundary such as a `FloatArray` or a `DataView`. Two implementations of the same arithmetic
 * therefore round at different points and disagree in the last few bits there, while agreeing
 * bit-for-bit on every other target. A tolerance of [EPSILON] is far below any difference that could
 * change a ranking, so a real disagreement still fails here — as does any disagreement at all in the
 * keys.
 */
internal const val EPSILON = 1e-5f

internal fun <K> assertSameResults(
    expected: List<SearchResult<K>>,
    actual: List<SearchResult<K>>,
    message: String = "",
) {
    assertContentEquals(expected.map { it.key }, actual.map { it.key }, "$message: ranking")
    for (i in expected.indices) {
        val e = expected[i].score
        val a = actual[i].score
        assertTrue(
            abs(e - a) <= EPSILON * maxOf(1f, abs(e)),
            "$message: score at $i for key ${expected[i].key} — expected $e, got $a",
        )
    }
}

/** As [assertSameResults], for a float read back from a file rather than recomputed. */
internal fun assertSameFloat(expected: Float, actual: Float, message: String = "") {
    assertTrue(
        abs(expected - actual) <= EPSILON * maxOf(1f, abs(expected)),
        "$message: expected $expected, got $actual",
    )
}
