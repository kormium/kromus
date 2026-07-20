package io.github.kromus.kemus

import io.github.kemus.InMemoryPersistence
import io.github.kemus.Kemus
import io.github.kromus.HybridIndex
import io.github.kromus.KeyCodec
import io.github.kromus.Metric
import io.github.kromus.VectorIndex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class KemusStorageTest {

    private fun randomVector(dim: Int, rng: Random) = FloatArray(dim) { rng.nextFloat() * 2f - 1f }

    @Test
    fun vectorIndexRoundTripsThroughKemus() = runTest {
        val dim = 32
        val rng = Random(1)
        val index = VectorIndex<Int>(dim, Metric.Cosine)
        repeat(200) { index.add(it, randomVector(dim, rng)) }

        val store = Kemus()
        assertNull(loadVectorIndex(store, "idx", KeyCodec.int))
        index.saveTo(store, "idx", KeyCodec.int)

        val loaded = loadVectorIndex(store, "idx", KeyCodec.int)!!
        assertEquals(index.size, loaded.size)
        repeat(10) {
            val q = randomVector(dim, rng)
            assertEquals(index.search(q, 10), loaded.search(q, 10))
        }
    }

    @Test
    fun indexSurvivesKemusPersistence() = runTest {
        val dim = 16
        val rng = Random(2)
        val index = HybridIndex<String>(dimensions = dim)
        repeat(80) { index.add("d$it", randomVector(dim, rng), "doc $it topic ${it % 4}") }

        // Save into a persistent kemus, then reopen the store from its AOF and read the index back.
        val p = InMemoryPersistence()
        val first = Kemus.open(p)
        index.saveTo(first, "hybrid", KeyCodec.string)

        val second = Kemus.open(p)
        val loaded = loadHybridIndex(second, "hybrid", KeyCodec.string)!!
        assertEquals(index.size, loaded.size)
        val q = randomVector(dim, rng)
        assertTrue(loaded.search(q, "topic 2", k = 5).isNotEmpty())
        assertEquals(
            index.searchVector(q, 5).map { it.key },
            loaded.searchVector(q, 5).map { it.key },
        )
    }
}
