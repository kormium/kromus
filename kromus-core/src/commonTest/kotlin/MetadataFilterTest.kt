package io.github.kromus

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetadataFilterTest {

    @Test
    fun vectorFilterReturnsOnlyMatchingAndFillsK() {
        val dim = 32
        val rng = Random(1)
        val index = VectorIndex<Int>(dim, Metric.Cosine)
        repeat(500) { i ->
            index.add(i, randomVector(dim, rng), mapOf("type" to if (i % 2 == 0) "a" else "b"))
        }

        val hits = index.search(randomVector(dim, rng), k = 10, efSearch = 128) { it["type"] == "a" }
        assertEquals(10, hits.size, "filtered traversal should still return k matches")
        assertTrue(hits.all { it.key % 2 == 0 }, "only type=a (even) keys may be returned")
    }

    @Test
    fun vectorFilterCappedByAvailableMatches() {
        val dim = 16
        val rng = Random(2)
        val index = VectorIndex<Int>(dim, Metric.Cosine)
        repeat(300) { i ->
            index.add(i, randomVector(dim, rng), mapOf("tag" to if (i < 5) "rare" else "common"))
        }
        val hits = index.search(randomVector(dim, rng), k = 10, efSearch = 200) { it["tag"] == "rare" }
        assertEquals(5, hits.size, "cannot return more than the matching entries")
        assertTrue(hits.all { it.key < 5 })
    }

    @Test
    fun textFilterRestrictsResults() {
        val index = TextIndex<Int>()
        index.add(1, "kotlin coroutines guide", mapOf("type" to "doc"))
        index.add(2, "kotlin coroutines faq", mapOf("type" to "faq"))
        index.add(3, "kotlin flow tutorial", mapOf("type" to "doc"))

        val docs = index.search("kotlin", k = 10) { it["type"] == "doc" }.map { it.key }.toSet()
        assertEquals(setOf(1, 3), docs)
    }

    @Test
    fun hybridFilterAppliesToBothModalities() {
        val dim = 16
        val rng = Random(4)
        val index = HybridIndex<String>(dimensions = dim)
        repeat(60) { i ->
            val lang = if (i % 3 == 0) "en" else "fr"
            index.add("d$i", randomVector(dim, rng), "document $i topic ${i % 4}", mapOf("lang" to lang))
        }
        val hits = index.search(randomVector(dim, rng), "document topic", k = 10) { it["lang"] == "en" }
        assertTrue(hits.isNotEmpty())
        // every returned key must be an "en" doc (i % 3 == 0)
        assertTrue(hits.all { it.key.removePrefix("d").toInt() % 3 == 0 })
    }

    @Test
    fun attributesSurvivePersistence() {
        val dim = 24
        val rng = Random(7)
        val index = VectorIndex<Int>(dim, Metric.Cosine)
        repeat(200) { i -> index.add(i, randomVector(dim, rng), mapOf("g" to (i % 4).toString())) }

        val restored = decodeVectorIndex(index.encodeToByteArray(KeyCodec.int), KeyCodec.int)
        val q = randomVector(dim, rng)
        val filter: MetadataFilter = { it["g"] == "2" }
        assertEquals(
            index.search(q, 10, 128, filter),
            restored.search(q, 10, 128, filter),
            "filtered search must match after reload (attributes persisted)",
        )
        assertTrue(restored.search(q, 10, 128, filter).all { it.key % 4 == 2 })
    }

    @Test
    fun textAttributesSurvivePersistence() {
        val index = TextIndex<Int>()
        index.add(1, "alpha beta", mapOf("k" to "x"))
        index.add(2, "alpha gamma", mapOf("k" to "y"))
        val restored = decodeTextIndex(index.encodeToByteArray(KeyCodec.int), KeyCodec.int)
        val hits = restored.search("alpha", 10) { it["k"] == "x" }.map { it.key }
        assertEquals(listOf(1), hits)
    }

    private fun randomVector(dim: Int, rng: Random): FloatArray =
        FloatArray(dim) { rng.nextFloat() * 2f - 1f }
}
