package io.github.kromus

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuantizationTest {

    private val int8 = HnswConfig(quantization = Quantization.Int8)

    @Test
    fun int8KeepsHighRecall() {
        val dim = 48
        val rng = Random(42)
        val data = ArrayList<FloatArray>()
        val centers = (0 until 20).map { randomVector(dim, rng) }
        for (center in centers) repeat(75) { data.add(jitter(center, 0.12f, rng)) }

        val index = VectorIndex<Int>(dim, Metric.Cosine, int8)
        data.forEachIndexed { i, v -> index.add(i, v) }

        val k = 10
        var recall = 0.0
        val queries = 50
        repeat(queries) {
            val query = jitter(centers[rng.nextInt(centers.size)], 0.12f, rng)
            val expected = bruteForceTopK(data, query, k).toSet()
            val actual = index.search(query, k, efSearch = 128).map { it.key }.toSet()
            recall += expected.intersect(actual).size.toDouble() / k
        }
        recall /= queries
        // Quantization costs a few points of recall (full-precision is ~0.9+ on this data); it
        // should still stay well above 0.80.
        assertTrue(recall >= 0.80, "int8 recall@$k was $recall, expected >= 0.80")
    }

    @Test
    fun int8ReturnsSelfAsTopHitMostly() {
        val dim = 32
        val rng = Random(3)
        val index = VectorIndex<Int>(dim, Metric.Cosine, int8)
        val vectors = (0 until 200).map { it to randomVector(dim, rng) }
        vectors.forEach { (k, v) -> index.add(k, v) }

        val selfHits = vectors.count { (key, v) -> index.search(v, k = 1, efSearch = 64).first().key == key }
        assertTrue(selfHits >= 190, "expected most vectors to retrieve themselves, got $selfHits/200")
    }

    @Test
    fun int8PersistenceRoundTrip() {
        val dim = 24
        val rng = Random(9)
        val index = VectorIndex<String>(dim, Metric.Cosine, int8)
        repeat(300) { index.add("k$it", randomVector(dim, rng)) }
        listOf("k5", "k100", "k299").forEach { index.remove(it) }

        val restored = decodeVectorIndex(index.encodeToByteArray(KeyCodec.string), KeyCodec.string)
        assertEquals(index.size, restored.size)
        // Codes round-trip verbatim (bit-exact on JVM/Native); ranking is identical, and scores match
        // to a small tolerance — Kotlin/JS rounds the Float*Int distance path slightly differently
        // between two independently built indexes.
        repeat(15) {
            val q = randomVector(dim, rng)
            val expected = index.search(q, 10, efSearch = 80)
            val actual = restored.search(q, 10, efSearch = 80)
            assertEquals(expected.map { it.key }, actual.map { it.key })
            for (i in expected.indices) {
                assertTrue(kotlin.math.abs(expected[i].score - actual[i].score) <= 1e-4f)
            }
        }
    }

    @Test
    fun int8HybridWorks() {
        val dim = 16
        val rng = Random(1)
        val index = HybridIndex<String>(dimensions = dim, hnswConfig = int8)
        repeat(120) { index.add("d$it", randomVector(dim, rng), "doc $it topic ${it % 5}") }
        val restored = decodeHybridIndex(index.encodeToByteArray(KeyCodec.string), KeyCodec.string)
        assertEquals(index.size, restored.size)
        assertEquals(Quantization.Int8, restored.hnswConfig.quantization)
    }

    private fun randomVector(dim: Int, rng: Random): FloatArray =
        FloatArray(dim) { rng.nextFloat() * 2f - 1f }

    private fun jitter(base: FloatArray, scale: Float, rng: Random): FloatArray =
        FloatArray(base.size) { base[it] + (rng.nextFloat() * 2f - 1f) * scale }

    private fun bruteForceTopK(data: List<FloatArray>, query: FloatArray, k: Int): List<Int> {
        val q = normalized(query)
        return data.indices
            .map { it to dot(q, normalized(data[it])) }
            .sortedByDescending { it.second }
            .take(k)
            .map { it.first }
    }

    private fun normalized(v: FloatArray): FloatArray {
        var s = 0f
        for (x in v) s += x * x
        if (s == 0f) return v
        val inv = 1f / kotlin.math.sqrt(s)
        return FloatArray(v.size) { v[it] * inv }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (i in a.indices) d += a[i] * b[i]
        return d
    }
}
