package io.github.kromus

import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RerankTest {

    @Test
    fun rerankRecoversAccuracyLostToBinaryQuantization() {
        val dim = 128
        val perCluster = 75
        val rng = Random(42)
        val data = ArrayList<FloatArray>()
        val centers = (0 until 20).map { randomVector(dim, rng) }
        for (center in centers) repeat(perCluster) { data.add(jitter(center, 0.10f, rng)) }

        val index = VectorIndex<Int>(dim, Metric.Cosine, HnswConfig(quantization = Quantization.Binary))
        data.forEachIndexed { i, v -> index.add(i, v) }

        val k = 10
        var binaryRecall = 0.0
        var rerankRecall = 0.0
        val queries = 40
        repeat(queries) {
            val query = jitter(centers[rng.nextInt(centers.size)], 0.10f, rng)
            val truth = bruteForceTopK(data, query, k).toSet()

            val binaryHits = index.search(query, k, efSearch = 256).map { it.key }.toSet()
            binaryRecall += truth.intersect(binaryHits).size.toDouble() / k

            // Over-fetch coarse candidates, then re-rank by exact cosine against the original vectors.
            val coarse = index.search(query, k = 100, efSearch = 256).map { it.key }
            val reranked = rerank(query, coarse, k, Metric.Cosine) { data[it] }.map { it.key }.toSet()
            rerankRecall += truth.intersect(reranked).size.toDouble() / k
        }
        binaryRecall /= queries
        rerankRecall /= queries

        assertTrue(rerankRecall >= 0.9, "rerank recall@$k was $rerankRecall, expected >= 0.9")
        assertTrue(
            rerankRecall > binaryRecall + 0.2,
            "rerank ($rerankRecall) should clearly beat binary-only ($binaryRecall)",
        )
    }

    @Test
    fun ordersByExactSimilarity() {
        val vectors = mapOf(
            "a" to floatArrayOf(1f, 0f, 0f),
            "b" to floatArrayOf(0f, 1f, 0f),
            "c" to floatArrayOf(0.9f, 0.1f, 0f),
        )
        // Candidate order deliberately not by similarity; rerank must fix it.
        val out = rerank(floatArrayOf(1f, 0f, 0f), listOf("b", "c", "a"), k = 3) { vectors[it] }
        assertEquals(listOf("a", "c", "b"), out.map { it.key })
        assertTrue(out[0].score >= out[1].score && out[1].score >= out[2].score)
    }

    @Test
    fun dropsCandidatesWithoutAVectorAndDeduplicates() {
        val vectors = mapOf("a" to floatArrayOf(1f, 0f), "b" to floatArrayOf(0f, 1f))
        val out = rerank(floatArrayOf(1f, 0f), listOf("a", "a", "missing", "b"), k = 10) { vectors[it] }
        assertEquals(listOf("a", "b"), out.map { it.key }, "duplicates collapsed, missing dropped")
    }

    @Test
    fun respectsKAndValidates() {
        val vectors = (0 until 5).associateWith { floatArrayOf(it.toFloat(), 0f) }
        assertEquals(2, rerank(floatArrayOf(1f, 0f), vectors.keys.toList(), k = 2) { vectors[it] }.size)
        assertFailsWith<IllegalArgumentException> {
            rerank(floatArrayOf(1f, 0f), listOf(0), k = 0) { vectors[it] }
        }
        assertFailsWith<IllegalArgumentException> {
            rerank(floatArrayOf(1f, 0f), listOf(0), k = 1) { floatArrayOf(1f, 2f, 3f) }
        }
    }

    private fun randomVector(dim: Int, rng: Random) = FloatArray(dim) { rng.nextFloat() * 2f - 1f }

    private fun jitter(base: FloatArray, scale: Float, rng: Random) =
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
        val inv = 1f / sqrt(s)
        return FloatArray(v.size) { v[it] * inv }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (i in a.indices) d += a[i] * b[i]
        return d
    }
}
