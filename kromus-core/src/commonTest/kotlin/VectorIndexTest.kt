package io.github.kromus

import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VectorIndexTest {

    @Test
    fun returnsInsertedVectorAsTopHit() {
        val index = VectorIndex<String>(dimensions = 8, metric = Metric.Cosine)
        val rng = Random(1)
        val vectors = (0 until 200).associate { "id-$it" to randomVector(8, rng) }
        vectors.forEach { (k, v) -> index.add(k, v) }

        assertEquals(200, index.size)
        for ((key, vector) in vectors) {
            val top = index.search(vector, k = 1, efSearch = 64).first()
            assertEquals(key, top.key, "querying a stored vector should return itself first")
        }
    }

    @Test
    fun highRecallAgainstBruteForce() {
        val dim = 48
        val rng = Random(42)
        // Clustered data (embedding-like): 20 centers, 75 points each.
        val data = ArrayList<FloatArray>()
        val centers = (0 until 20).map { randomVector(dim, rng) }
        for (center in centers) {
            repeat(75) { data.add(jitter(center, 0.12f, rng)) }
        }

        val index = VectorIndex<Int>(dimensions = dim, metric = Metric.Cosine)
        data.forEachIndexed { i, v -> index.add(i, v) }

        val k = 10
        var totalRecall = 0.0
        val queries = 50
        repeat(queries) {
            val query = jitter(centers[rng.nextInt(centers.size)], 0.12f, rng)
            val expected = bruteForceTopK(data, query, k).toSet()
            val actual = index.search(query, k, efSearch = 128).map { it.key }.toSet()
            totalRecall += expected.intersect(actual).size.toDouble() / k
        }
        val recall = totalRecall / queries
        assertTrue(recall >= 0.9, "recall@$k was $recall, expected >= 0.9")
    }

    @Test
    fun deterministicAcrossIdenticalBuilds() {
        val dim = 16
        val data = (0 until 300).map { it to randomVector(dim, Random(it.toLong())) }
        val query = randomVector(dim, Random(9999))

        fun build(): List<Int> {
            val index = VectorIndex<Int>(dim, Metric.Cosine, HnswConfig(seed = 7))
            data.forEach { (k, v) -> index.add(k, v) }
            return index.search(query, k = 10, efSearch = 64).map { it.key }
        }

        assertEquals(build(), build(), "same seed + same insert order must give identical results")
    }

    @Test
    fun removeExcludesKeyFromResults() {
        val dim = 12
        val rng = Random(3)
        val index = VectorIndex<Int>(dim, Metric.Cosine)
        val vectors = (0 until 100).map { it to randomVector(dim, rng) }
        vectors.forEach { (k, v) -> index.add(k, v) }

        val target = vectors[10]
        assertTrue(index.remove(target.first))
        assertFalse(index.remove(target.first), "removing twice returns false")
        assertEquals(99, index.size)
        assertFalse(target.first in index)

        val hits = index.search(target.second, k = 5, efSearch = 64).map { it.key }
        assertFalse(target.first in hits, "removed key must not appear in results")
    }

    @Test
    fun reAddReplacesVector() {
        val dim = 4
        val index = VectorIndex<String>(dim, Metric.Euclidean)
        index.add("a", floatArrayOf(1f, 0f, 0f, 0f))
        index.add("b", floatArrayOf(0f, 1f, 0f, 0f))
        index.add("a", floatArrayOf(0f, 1f, 0f, 0f)) // move "a" onto "b"

        assertEquals(2, index.size)
        val hits = index.search(floatArrayOf(0f, 1f, 0f, 0f), k = 2).map { it.key }
        assertTrue("a" in hits && "b" in hits)
    }

    @Test
    fun dotProductRanksByMagnitudeAndDirection() {
        val index = VectorIndex<String>(dimensions = 2, metric = Metric.DotProduct)
        index.add("small", floatArrayOf(1f, 0f))
        index.add("large", floatArrayOf(5f, 0f))
        val top = index.search(floatArrayOf(1f, 0f), k = 2)
        assertEquals("large", top.first().key, "larger magnitude wins under dot product")
    }

    @Test
    fun rejectsWrongDimensions() {
        val index = VectorIndex<Int>(dimensions = 3)
        assertFailsWith<IllegalArgumentException> { index.add(1, floatArrayOf(1f, 2f)) }
        index.add(1, floatArrayOf(1f, 2f, 3f))
        assertFailsWith<IllegalArgumentException> { index.search(floatArrayOf(1f), k = 1) }
        assertFailsWith<IllegalArgumentException> { index.search(floatArrayOf(1f, 2f, 3f), k = 0) }
    }

    @Test
    fun emptyIndexReturnsNoResults() {
        val index = VectorIndex<Int>(dimensions = 5)
        assertTrue(index.search(randomVector(5, Random(0)), k = 3).isEmpty())
    }

    // --- helpers ---

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
        val inv = 1f / sqrt(s)
        return FloatArray(v.size) { v[it] * inv }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (i in a.indices) d += a[i] * b[i]
        return d
    }
}
