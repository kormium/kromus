package io.github.kromus

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BinaryQuantizationTest {

    private val binary = HnswConfig(quantization = Quantization.Binary)

    @Test
    fun binaryRetrievesTheRightCluster() {
        // Binary quantization is a coarse filter: it can't resolve the fine ordering inside a tight
        // cluster, but it should reliably return neighbours from the *correct* cluster. So we measure
        // cluster purity rather than exact top-k overlap (which is what a full-precision re-rank is for).
        val dim = 128 // binary works best in higher dimensions
        val perCluster = 75
        val rng = Random(42)
        val data = ArrayList<FloatArray>()
        val centers = (0 until 20).map { randomVector(dim, rng) }
        for (center in centers) repeat(perCluster) { data.add(jitter(center, 0.10f, rng)) }

        val index = VectorIndex<Int>(dim, Metric.Cosine, binary)
        data.forEachIndexed { i, v -> index.add(i, v) }

        var purity = 0.0
        val queries = 50
        repeat(queries) {
            val ci = rng.nextInt(centers.size)
            val query = jitter(centers[ci], 0.10f, rng)
            val hits = index.search(query, k = 10, efSearch = 256)
            val sameCluster = hits.count { it.key / perCluster == ci }
            purity += sameCluster.toDouble() / hits.size
        }
        purity /= queries
        assertTrue(purity >= 0.9, "binary cluster purity was $purity, expected >= 0.9")
    }

    @Test
    fun binaryPersistenceRoundTrip() {
        val dim = 96
        val rng = Random(9)
        val index = VectorIndex<String>(dim, Metric.Cosine, binary)
        repeat(300) { index.add("k$it", randomVector(dim, rng)) }
        listOf("k5", "k100", "k299").forEach { index.remove(it) }

        val restored = decodeVectorIndex(index.encodeToByteArray(KeyCodec.string), KeyCodec.string)
        assertEquals(index.size, restored.size)
        repeat(15) {
            val q = randomVector(dim, rng)
            val expected = index.search(q, 10, efSearch = 128)
            val actual = restored.search(q, 10, efSearch = 128)
            assertEquals(expected.map { it.key }, actual.map { it.key })
            for (i in expected.indices) {
                assertTrue(kotlin.math.abs(expected[i].score - actual[i].score) <= 1e-4f)
            }
        }
    }

    @Test
    fun binaryMemoryFootprintIsMuchSmaller() {
        // ~1 bit/dim vs 32 bit/dim -> the encoded vector payload is roughly 32x smaller than float32.
        val dim = 512
        val rng = Random(1)
        val bin = VectorIndex<Int>(dim, Metric.Cosine, binary)
        val f32 = VectorIndex<Int>(dim, Metric.Cosine)
        repeat(200) {
            val v = randomVector(dim, rng)
            bin.add(it, v)
            f32.add(it, v)
        }
        val binBytes = bin.encodeToByteArray(KeyCodec.int).size
        val f32Bytes = f32.encodeToByteArray(KeyCodec.int).size
        assertTrue(binBytes * 4 < f32Bytes, "binary ($binBytes) should be far smaller than float32 ($f32Bytes)")
    }

    @Test
    fun binaryHybridRoundTrips() {
        val dim = 64
        val rng = Random(2)
        val index = HybridIndex<String>(dimensions = dim, hnswConfig = binary)
        repeat(120) { index.add("d$it", randomVector(dim, rng), "doc $it topic ${it % 5}") }
        val restored = decodeHybridIndex(index.encodeToByteArray(KeyCodec.string), KeyCodec.string)
        assertEquals(index.size, restored.size)
        assertEquals(Quantization.Binary, restored.hnswConfig.quantization)
    }

    private fun randomVector(dim: Int, rng: Random): FloatArray =
        FloatArray(dim) { rng.nextFloat() * 2f - 1f }

    private fun jitter(base: FloatArray, scale: Float, rng: Random): FloatArray =
        FloatArray(base.size) { base[it] + (rng.nextFloat() * 2f - 1f) * scale }
}
