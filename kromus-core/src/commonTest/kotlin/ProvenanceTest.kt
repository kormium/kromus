package io.github.kromus

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An index is only meaningful together with whatever produced its vectors and tokenized its text, and
 * neither travels in the bytes. Getting that pairing wrong does not throw — it returns plausible
 * nonsense — which is why the mismatch is worth refusing loudly.
 */
class ProvenanceTest {

    private val dim = 12

    private fun vec(seed: Int) = Random(seed).let { r -> FloatArray(dim) { r.nextFloat() } }

    @Test
    fun aMatchingProvenanceLoads() {
        val index = VectorIndex<Int>(dim, Metric.Cosine)
        repeat(20) { index.add(it, vec(it)) }
        val bytes = index.encodeToByteArray(KeyCodec.int, provenance = "minilm-l6-v2/mean/l2")

        val loaded = decodeVectorIndex(bytes, KeyCodec.int, expect = "minilm-l6-v2/mean/l2")
        assertEquals(index.size, loaded.size)
    }

    @Test
    fun aDifferentModelIsRefused() {
        val index = VectorIndex<Int>(dim, Metric.Cosine)
        repeat(20) { index.add(it, vec(it)) }
        val bytes = index.encodeToByteArray(KeyCodec.int, provenance = "minilm-l6-v2/mean/l2")

        val failure = assertFailsWith<KromusFormatException> {
            decodeVectorIndex(bytes, KeyCodec.int, expect = "e5-small/cls/l2")
        }
        // The message has to name both sides, or it sends the reader to the wrong file.
        assertTrue("minilm-l6-v2/mean/l2" in failure.message!!, failure.message!!)
        assertTrue("e5-small/cls/l2" in failure.message!!, failure.message!!)
    }

    @Test
    fun anIndexBuiltWithoutProvenanceIsRefusedWhenOneIsExpected() {
        // The dangerous direction: an old asset that recorded nothing, loaded by a client that now
        // pins a model. Silently accepting it would defeat the point.
        val index = VectorIndex<Int>(dim, Metric.Cosine)
        repeat(10) { index.add(it, vec(it)) }
        val bytes = index.encodeToByteArray(KeyCodec.int)

        assertFailsWith<KromusFormatException> {
            decodeVectorIndex(bytes, KeyCodec.int, expect = "minilm-l6-v2/mean/l2")
        }
        // ...and stays loadable when nothing is expected, so the guard is opt-in.
        assertEquals(10, decodeVectorIndex(bytes, KeyCodec.int).size)
    }

    @Test
    fun provenanceCanBeReadWithoutDecoding() {
        val index = VectorIndex<Int>(dim, Metric.Cosine)
        repeat(5) { index.add(it, vec(it)) }
        assertEquals(
            "corpus-2026-08-29/minilm-l6-v2",
            provenanceOf(index.encodeToByteArray(KeyCodec.int, provenance = "corpus-2026-08-29/minilm-l6-v2")),
        )
        assertNull(provenanceOf(index.encodeToByteArray(KeyCodec.int)))
    }

    @Test
    fun textAndHybridCarryItToo() {
        val text = TextIndex<Int>()
        text.add(0, "kotlin coroutines")
        val textBytes = text.encodeToByteArray(KeyCodec.int, provenance = "analyzer:standard+stemmer")
        assertFailsWith<KromusFormatException> {
            decodeTextIndex(textBytes, KeyCodec.int, expect = "analyzer:whitespace")
        }
        assertEquals(1, decodeTextIndex(textBytes, KeyCodec.int, expect = "analyzer:standard+stemmer").size)

        val hybrid = HybridIndex<Int>(dimensions = dim)
        hybrid.add(0, vec(1), "kotlin coroutines")
        val hybridBytes = hybrid.encodeToByteArray(KeyCodec.int, provenance = "v1")
        assertFailsWith<KromusFormatException> { decodeHybridIndex(hybridBytes, KeyCodec.int, expect = "v2") }
        assertEquals(1, decodeHybridIndex(hybridBytes, KeyCodec.int, expect = "v1").size)
    }

    @Test
    fun provenanceSurvivesADeltaChain() {
        val index = VectorIndex<Int>(dim, Metric.Cosine)
        repeat(20) { index.add(it, vec(it)) }
        val base = index.encodeToByteArray(KeyCodec.int, provenance = "pinned")
        index.add(99, vec(99))
        val delta = index.encodeDelta(KeyCodec.int)!!

        assertEquals(21, decodeVectorIndex(base, listOf(delta), KeyCodec.int, expect = "pinned").size)
        assertFailsWith<KromusFormatException> {
            decodeVectorIndex(base, listOf(delta), KeyCodec.int, expect = "other")
        }
    }
}
