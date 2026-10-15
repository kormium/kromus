package io.github.kromus

import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlatIndexTest {

    private val dim = 24

    private fun data(n: Int, seed: Int): List<FloatArray> {
        val rng = Random(seed)
        return List(n) { FloatArray(dim) { rng.nextFloat() * 2f - 1f } }
    }

    private fun entries(v: List<FloatArray>) = v.mapIndexed { i, x -> IvfEntry(i, x) }

    private fun exact(v: List<FloatArray>, query: FloatArray, k: Int): List<Int> {
        fun norm(x: FloatArray): FloatArray {
            var n = 0f
            for (e in x) n += e * e
            val inv = 1f / sqrt(n)
            return FloatArray(x.size) { x[it] * inv }
        }
        val q = norm(query)
        return v.indices.sortedBy { i ->
            val n = norm(v[i])
            var dot = 0f
            for (d in 0 until dim) dot += q[d] * n[d]
            1f - dot
        }.take(k)
    }

    @Test
    fun itIsExact() {
        // The whole reason it exists: no approximation, so it *is* the answer the others are compared
        // against. If this drifts, every recall figure in the project is measured against a lie.
        val v = data(500, 1)
        val index = FlatIndex.build(dim, entries(v))
        val rng = Random(2)
        repeat(25) {
            val q = FloatArray(dim) { rng.nextFloat() * 2f - 1f }
            assertEquals(exact(v, q, 10), index.search(q, 10).map { it.key })
        }
    }

    @Test
    fun itAgreesWithTheGraphAndTheIvfIndexWhenTheyAreExhaustive() {
        // Three implementations of the same question; the scores have to mean the same thing in all
        // three or they cannot be compared to each other at all.
        val v = data(300, 3)
        val flat = FlatIndex.build(dim, entries(v))
        val graph = VectorIndex<Int>(dim, Metric.Cosine)
        v.forEachIndexed { i, x -> graph.add(i, x) }
        val ivf = IvfIndex.build(dim, entries(v), config = IvfConfig(clusters = 8, nprobe = 8))

        val q = v[11]
        val fromFlat = flat.search(q, 5)
        assertEquals(fromFlat.map { it.key }, ivf.search(q, 5).map { it.key })
        for (hit in fromFlat) {
            val other = graph.search(q, 5).firstOrNull { it.key == hit.key } ?: continue
            assertTrue(kotlin.math.abs(hit.score - other.score) < 1e-4f, "${hit.key}: ${hit.score} vs ${other.score}")
        }
    }

    @Test
    fun aRoundTripReproducesEverything() {
        val v = data(200, 4)
        val index = FlatIndex.build(dim, v.mapIndexed { i, x -> IvfEntry(i, x, mapOf("mod" to "${i % 3}")) })
        val blob = index.encodeToByteArray(KeyCodec.int)
        val reloaded = decodeFlatIndex(blob, KeyCodec.int)

        assertEquals(index.size, reloaded.size)
        assertContentEquals(index.keys.toList(), reloaded.keys.toList())
        assertEquals(mapOf("mod" to "1"), reloaded.attributesOf(1))
        val q = v[7]
        assertEquals(index.search(q, 10).map { it.key to it.score }, reloaded.search(q, 10).map { it.key to it.score })
    }

    @Test
    fun encodingIsByteStable() {
        val v = data(150, 5)

        fun encode() = FlatIndex.build(dim, entries(v)).encodeToByteArray(KeyCodec.int)
        assertContentEquals(encode(), encode())
        val first = encode()
        assertContentEquals(first, decodeFlatIndex(first, KeyCodec.int).encodeToByteArray(KeyCodec.int))
    }

    @Test
    fun aStreamedScanAgreesWithAResidentOne() {
        for (quantization in listOf(Quantization.None, Quantization.Int8)) {
            for (metric in Metric.entries) {
                val v = data(1500, 6) // larger than one read batch, so the batching is exercised
                val index = FlatIndex.build(dim, entries(v), metric = metric, quantization = quantization)
                val blob = index.encodeToByteArray(KeyCodec.int)
                val resident = decodeFlatIndex(blob, KeyCodec.int)
                val streamed = openFlatIndex(ByteArraySource(blob), KeyCodec.int)
                val q = v[13]
                assertSameResults(
                    resident.search(q, 10),
                    streamed.search(q, 10),
                    "$quantization/$metric",
                )
            }
        }
    }

    @Test
    fun filtersApply() {
        val v = data(200, 7)
        val index = FlatIndex.build(dim, v.mapIndexed { i, x -> IvfEntry(i, x, mapOf("even" to "${i % 2 == 0}")) })
        val hits = index.search(v[0], 10, filter = { it["even"] == "true" })
        assertTrue(hits.isNotEmpty() && hits.all { it.key % 2 == 0 })
    }

    @Test
    fun anEmptyIndexSearchesToNothing() {
        assertTrue(FlatIndex.build<Int>(dim, emptyList()).search(FloatArray(dim), 5).isEmpty())
    }
}
