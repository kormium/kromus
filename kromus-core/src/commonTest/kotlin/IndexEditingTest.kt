package io.github.kromus

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndexEditingTest {
    @Test
    fun updateAttributesChangesFilteringWithoutTouchingTheGraph() {
        val index = VectorIndex<Int>(dimensions = 8)
        val rng = Random(4)
        repeat(50) { index.add(it, randomVector(8, rng), mapOf("state" to "draft")) }
        val query = randomVector(8, rng)
        assertTrue(index.search(query, k = 5) { it["state"] == "published" }.isEmpty())

        assertTrue(index.updateAttributes(7, mapOf("state" to "published")))

        assertEquals(
            listOf(7),
            index.search(query, k = 5, efSearch = 128) { it["state"] == "published" }.map { it.key },
        )
        assertEquals(0, index.tombstones, "changing metadata must not retire the entry's vector")
        assertEquals(50, index.size)
    }

    @Test
    fun updateAttributesReportsAMissingKey() {
        val index = VectorIndex<Int>(dimensions = 4)
        assertEquals(false, index.updateAttributes(1, mapOf("a" to "b")))
    }

    @Test
    fun hybridUpdateAttributesReachesBothModalities() {
        val index = HybridIndex<String>(dimensions = 8)
        val rng = Random(6)
        index.add("a", randomVector(8, rng), "kotlin coroutines", mapOf("lang" to "en"))
        index.add("b", randomVector(8, rng), "kotlin coroutines", mapOf("lang" to "en"))

        assertTrue(index.updateAttributes("a", mapOf("lang" to "fr")))

        assertEquals(listOf("b"), index.searchText("kotlin", k = 5) { it["lang"] == "en" }.map { it.key })
        assertEquals(listOf("a"), index.searchVector(randomVector(8, rng), k = 5) { it["lang"] == "fr" }.map { it.key })
        assertEquals(0, index.tombstones)
    }

    @Test
    fun vectorOfReturnsTheStoredVector() {
        val index = VectorIndex<String>(dimensions = 4, metric = Metric.Cosine)
        index.add("a", floatArrayOf(3f, 0f, 0f, 0f))

        val stored = index.vectorOf("a")!!
        // Cosine normalizes on insert, so the stored form is the unit vector.
        assertTrue(abs(1f - sqrt(stored.sumOf { (it * it).toDouble() }.toFloat())) < 1e-5f)
        assertContentEquals(floatArrayOf(1f, 0f, 0f, 0f), stored)
        assertNull(index.vectorOf("missing"))
    }

    @Test
    fun vectorOfFeedsRerankFromTheIndexItself() {
        val dim = 16
        val index = VectorIndex<Int>(dim)
        val rng = Random(8)
        repeat(100) { index.add(it, randomVector(dim, rng)) }
        val query = randomVector(dim, rng)

        val coarse = index.search(query, k = 30, efSearch = 64).map { it.key }
        val exact = rerank(query, coarse, k = 5) { index.vectorOf(it) }

        assertEquals(5, exact.size)
        assertEquals(coarse.take(1), exact.take(1).map { it.key }, "the nearest hit must survive a re-rank")
    }

    @Test
    fun keysTrackLiveEntries() {
        val index = VectorIndex<Int>(dimensions = 4)
        val rng = Random(10)
        repeat(5) { index.add(it, randomVector(4, rng)) }
        index.remove(2)
        assertEquals(setOf(0, 1, 3, 4), index.keys.toSet())

        val text = TextIndex<Int>()
        repeat(5) { text.add(it, "doc $it") }
        text.remove(2)
        assertEquals(setOf(0, 1, 3, 4), text.keys.toSet())

        val hybrid = HybridIndex<Int>(dimensions = 4)
        repeat(5) { hybrid.add(it, randomVector(4, rng), "doc $it") }
        hybrid.remove(2)
        assertEquals(setOf(0, 1, 3, 4), hybrid.keys.toSet())
    }

    @Test
    fun clearEmptiesEveryIndex() {
        val rng = Random(12)
        val vector = VectorIndex<Int>(dimensions = 4)
        repeat(20) { vector.add(it, randomVector(4, rng)) }
        vector.remove(1)
        vector.clear()
        assertEquals(0, vector.size)
        assertEquals(0, vector.tombstones)
        assertTrue(vector.search(randomVector(4, rng), k = 3).isEmpty())
        vector.add(99, randomVector(4, rng))
        assertEquals(1, vector.size)

        val text = TextIndex<Int>()
        repeat(20) { text.add(it, "doc $it") }
        text.clear()
        assertEquals(0, text.size)
        assertTrue(text.search("doc", k = 3).isEmpty())

        val hybrid = HybridIndex<Int>(dimensions = 4)
        repeat(20) { hybrid.add(it, randomVector(4, rng), "doc $it") }
        hybrid.clear()
        assertEquals(0, hybrid.size)
        assertTrue(hybrid.searchText("doc", k = 3).isEmpty())
    }

    private fun randomVector(dim: Int, rng: Random): FloatArray =
        FloatArray(dim) { rng.nextFloat() * 2f - 1f }
}
