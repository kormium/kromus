package io.github.kromus.concurrent

import io.github.kromus.KeyCodec
import io.github.kromus.MetadataFilter
import io.github.kromus.decodeVectorIndex
import io.github.kromus.encodeToByteArray
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behaviour of the guarded wrappers. Real contention needs real threads, so the parallelism
 * guarantees themselves are covered in `jvmTest` (`ReadWriteMutexConcurrencyTest`,
 * `ConcurrentIndexStressTest`); here we pin down that guarding an index does not change what it
 * does.
 */
class ConcurrentIndexTest {
    private fun v(vararg values: Float) = floatArrayOf(*values)

    @Test
    fun vectorIndexDelegates() = runTest {
        val index = ConcurrentVectorIndex<String>(dimensions = 2)
        assertEquals(0, index.size())

        index.add("a", v(1f, 0f))
        index.add("b", v(0f, 1f))

        assertEquals(2, index.size())
        assertTrue(index.contains("a"))
        assertFalse(index.contains("zzz"))
        assertEquals("a", index.search(v(1f, 0.1f), k = 1).single().key)

        assertTrue(index.remove("b"))
        assertFalse(index.remove("b"))
        assertEquals(1, index.size())
    }

    @Test
    fun vectorAddAllAppliesEveryEntry() = runTest {
        val index = ConcurrentVectorIndex<String>(dimensions = 2)
        index.addAll(
            listOf(
                VectorEntry("a", v(1f, 0f), mapOf("lang" to "kt")),
                VectorEntry("b", v(0f, 1f), mapOf("lang" to "js")),
            ),
        )
        assertEquals(2, index.size())

        val filter: MetadataFilter = { it["lang"] == "js" }
        assertEquals("b", index.search(v(1f, 0f), k = 2, filter = filter).single().key)
    }

    @Test
    fun textIndexDelegates() = runTest {
        val index = ConcurrentTextIndex<String>()
        index.addAll(
            listOf(
                TextEntry("a", "Kotlin coroutines guide"),
                TextEntry("b", "Sourdough starter troubleshooting"),
            ),
        )
        assertEquals(2, index.size())
        assertEquals("a", index.search("coroutines", k = 1).single().key)
        assertTrue(index.remove("a"))
        assertTrue(index.search("coroutines", k = 1).isEmpty())
    }

    @Test
    fun hybridIndexDelegates() = runTest {
        val index = ConcurrentHybridIndex<String>(dimensions = 2)
        index.add("a", v(1f, 0f), "Kotlin coroutines guide")
        index.add("b", v(0f, 1f), "error code E-4021")

        assertEquals(2, index.size())
        assertEquals("a", index.searchVector(v(1f, 0.1f), k = 1).single().key)
        assertEquals("b", index.searchText("E-4021", k = 1).single().key)

        val fused = index.search(v(1f, 0.1f), text = "E-4021", k = 2).map { it.key }
        assertEquals(setOf("a", "b"), fused.toSet())
    }

    @Test
    fun readGivesAccessToUnwrappedOperations() = runTest {
        val index = ConcurrentVectorIndex<String>(dimensions = 2)
        index.add("a", v(1f, 0f))
        index.add("b", v(0f, 1f))

        // Persistence isn't wrapped operation-by-operation; it goes through the shared-lock hatch.
        val bytes = index.read { it.encodeToByteArray(KeyCodec.string) }
        val restored = decodeVectorIndex(bytes, KeyCodec.string)

        assertEquals(2, restored.size)
        assertContentEquals(
            listOf("a"),
            restored.search(v(1f, 0.1f), k = 1).map { it.key },
        )
    }

    @Test
    fun writeGivesAccessToUnwrappedMutations() = runTest {
        val index = ConcurrentHybridIndex<String>(dimensions = 2)
        val added = index.write { raw ->
            raw.add("a", v(1f, 0f), "Kotlin coroutines guide")
            raw.size
        }
        assertEquals(1, added)
        assertEquals(1, index.size())
    }

    @Test
    fun guardedIndexCanWrapAnExistingOne() = runTest {
        val bare = io.github.kromus.VectorIndex<String>(dimensions = 2)
        bare.add("a", v(1f, 0f))

        val index = ConcurrentVectorIndex(bare)
        assertEquals(1, index.size())
        assertEquals(2, index.dimensions)
    }

    @Test
    fun mutexRunsBlocksAndReturnsTheirValue() = runTest {
        val lock = ReadWriteMutex()
        assertEquals(7, lock.read { 7 })
        assertEquals("x", lock.write { "x" })

        // The lock is reusable after a block throws — release runs in a finally.
        runCatching { lock.write { throw IllegalStateException("boom") } }
        assertEquals(1, lock.read { 1 })
    }
}
