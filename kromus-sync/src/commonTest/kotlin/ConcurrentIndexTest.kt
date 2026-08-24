package io.github.kromus.sync

import io.github.kromus.HybridIndex
import io.github.kromus.TextIndex
import io.github.kromus.VectorIndex
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConcurrentIndexTest {
    @Test
    fun interleavedWritersAndReadersSeeAConsistentIndex() = runTest {
        val index = VectorIndex<Int>(dimensions = 8).concurrent()
        val rng = Random(2)
        val vectors = List(200) { FloatArray(8) { rng.nextFloat() } }

        coroutineScope {
            val writers = (0 until 200).map { key ->
                async { index.add(key, vectors[key]) }
            }
            val readers = (0 until 50).map {
                async { index.search(vectors[0], k = 5) }
            }
            writers.awaitAll()
            readers.awaitAll()
        }

        assertEquals(200, index.size())
        assertEquals(5, index.search(vectors[0], k = 5).size)
    }

    @Test
    fun exposesTheOrdinaryOperations() = runTest {
        val index = VectorIndex<String>(dimensions = 4).concurrent()
        index.add("a", floatArrayOf(1f, 0f, 0f, 0f), mapOf("g" to "x"))
        index.add("b", floatArrayOf(0f, 1f, 0f, 0f), mapOf("g" to "y"))

        assertEquals(2, index.size())
        assertEquals(listOf("a"), index.search(floatArrayOf(1f, 0f, 0f, 0f), k = 1).map { it.key })
        assertTrue(index.updateAttributes("b", mapOf("g" to "x")))
        assertEquals(
            setOf("a", "b"),
            index.search(floatArrayOf(1f, 0f, 0f, 0f), k = 5, filter = { it["g"] == "x" }).map { it.key }.toSet(),
        )
        assertTrue(index.remove("a"))
        assertEquals(1, index.size())
        assertEquals(1, index.tombstones())
        assertEquals(1, index.compact())
        assertEquals(0, index.tombstones())
    }

    @Test
    fun escapeHatchReachesAnythingNotWrapped() = runTest {
        val index = TextIndex<String>().concurrent()
        index.add("a", "kotlin coroutines")
        // Not every core operation is mirrored; `use` covers the rest under the same lock.
        assertEquals(setOf("a"), index.use { it.keys.toSet() })
    }

    @Test
    fun hybridWrapperCoversBothModalities() = runTest {
        val index = HybridIndex<String>(dimensions = 4).concurrent()
        index.add("a", floatArrayOf(1f, 0f, 0f, 0f), "kotlin coroutines guide")
        index.add("b", floatArrayOf(0f, 1f, 0f, 0f), "sourdough troubleshooting")

        assertEquals(listOf("a"), index.searchText("coroutines", k = 3).map { it.key })
        assertEquals(listOf("a"), index.searchVector(floatArrayOf(1f, 0f, 0f, 0f), k = 1).map { it.key })
        assertTrue(index.search(floatArrayOf(1f, 0f, 0f, 0f), text = "coroutines", k = 2).isNotEmpty())
        assertEquals(2, index.size())
    }
}
