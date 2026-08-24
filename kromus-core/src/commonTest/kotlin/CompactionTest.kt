package io.github.kromus

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompactionTest {
    @Test
    fun tracksTombstonesFromRemovalsAndReplacements() {
        val index = VectorIndex<Int>(dimensions = 8)
        val rng = Random(7)
        repeat(50) { index.add(it, randomVector(8, rng)) }
        assertEquals(0, index.tombstones, "a freshly built index has nothing to reclaim")

        index.remove(3)
        index.add(4, randomVector(8, rng)) // replacing a key retires its old vector too
        assertEquals(2, index.tombstones)
        assertEquals(49, index.size)
    }

    @Test
    fun compactReclaimsEverySlotAndKeepsResults() {
        val dim = 16
        val rng = Random(11)
        val index = VectorIndex<Int>(dimensions = dim)
        repeat(300) { index.add(it, randomVector(dim, rng)) }
        for (key in 0 until 300 step 3) index.remove(key)
        for (key in 1 until 100) index.add(key, randomVector(dim, rng)) // churn: replacements

        val before = index.tombstones
        assertTrue(before > 0)
        val queries = List(20) { randomVector(dim, rng) }
        val expected = queries.map { index.search(it, k = 10, efSearch = 200) }

        val reclaimed = index.compact()

        assertEquals(before, reclaimed)
        assertEquals(0, index.tombstones)
        assertEquals(10, expected.first().size)
        // A compacted graph is rebuilt, so a hit at the edge of the candidate list may shift.
        for ((i, query) in queries.withIndex()) {
            val after = index.search(query, k = 10, efSearch = 200)
            val overlap = after.map { it.key }.intersect(expected[i].map { it.key }.toSet()).size
            assertTrue(overlap >= 8, "compaction must preserve the ranking, overlap was $overlap")
        }
    }

    @Test
    fun compactMatchesAFreshBuildOfTheLiveEntriesByteForByte() {
        val dim = 12
        val rng = Random(23)
        val churned = VectorIndex<Int>(dim)
        val vectors = HashMap<Int, FloatArray>()
        val removed = (0 until 120 step 4).toSet()
        val replaced = (1 until 40 step 3).toList()

        repeat(120) { key ->
            val v = randomVector(dim, rng)
            churned.add(key, v, mapOf("k" to key.toString()))
            vectors[key] = v
        }
        for (key in removed) {
            churned.remove(key)
            vectors.remove(key)
        }
        for (key in replaced) {
            val v = randomVector(dim, rng) // a replacement moves the key to the end of the id space
            churned.add(key, v, mapOf("k" to key.toString()))
            vectors[key] = v
        }

        // The equivalent fresh build inserts the live keys in the order of their current vectors:
        // the untouched ones first, then the replaced ones in replacement order.
        val order = (0 until 120).filter { it !in removed && it !in replaced } + replaced
        assertEquals(vectors.keys, order.toSet(), "test setup: the expected live set")
        val fresh = VectorIndex<Int>(dim)
        for (key in order) fresh.add(key, vectors.getValue(key), mapOf("k" to key.toString()))

        churned.compact()

        assertEquals(fresh.size, churned.size)
        assertContentEquals(
            fresh.encodeToByteArray(KeyCodec.int),
            churned.encodeToByteArray(KeyCodec.int),
            "a compacted full-precision index must be identical to a fresh build of the same entries",
        )
    }

    @Test
    fun compactKeepsQuantizedVectorsBitExact() {
        val dim = 16
        for (quantization in listOf(Quantization.Int8, Quantization.Binary)) {
            val rng = Random(31)
            val index = VectorIndex<Int>(dim, config = HnswConfig(quantization = quantization))
            repeat(100) { index.add(it, randomVector(dim, rng)) }
            for (key in 0 until 100 step 5) index.remove(key)

            val before = index.keys.associateWith { index.vectorOf(it)!! }
            index.compact()

            assertEquals(0, index.tombstones, "quantization=$quantization")
            assertEquals(before.keys, index.keys, "quantization=$quantization")
            for ((key, vector) in before) {
                // Compaction reinserts stored vectors, so nothing is re-quantized: the codes survive
                // a rebuild untouched. (The graph itself is rebuilt from those stored vectors, which
                // is why only a full-precision index comes out byte-identical to a fresh build.)
                assertContentEquals(vector, index.vectorOf(key), "key=$key, quantization=$quantization")
            }
        }
    }

    @Test
    fun compactIsANoOpWithoutTombstones() {
        val index = VectorIndex<Int>(dimensions = 8)
        val rng = Random(3)
        repeat(40) { index.add(it, randomVector(8, rng)) }
        val before = index.encodeToByteArray(KeyCodec.int)

        assertEquals(0, index.compact())

        assertContentEquals(before, index.encodeToByteArray(KeyCodec.int))
    }

    @Test
    fun compactKeepsAttributesWithTheirEntries() {
        val index = VectorIndex<Int>(dimensions = 8)
        val rng = Random(5)
        repeat(60) { index.add(it, randomVector(8, rng), mapOf("g" to (it % 3).toString())) }
        for (key in 0 until 60 step 2) index.remove(key)

        index.compact()

        val hits = index.search(randomVector(8, rng), k = 10, efSearch = 128) { it["g"] == "1" }
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.key % 3 == 1 }, "attributes must follow their entry through a rebuild")
    }

    @Test
    fun hybridCompactionReclaimsTheVectorSide() {
        val index = HybridIndex<String>(dimensions = 8)
        val rng = Random(9)
        repeat(30) { index.add("doc-$it", randomVector(8, rng), "document number $it") }
        repeat(10) { index.remove("doc-$it") }

        assertEquals(10, index.tombstones)
        assertEquals(10, index.compact())
        assertEquals(0, index.tombstones)
        assertEquals(20, index.size)
        assertTrue(index.searchText("document", k = 5).isNotEmpty())
    }

    private fun randomVector(dim: Int, rng: Random): FloatArray =
        FloatArray(dim) { rng.nextFloat() * 2f - 1f }
}
