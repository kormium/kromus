package io.github.kromus.concurrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hammers the guarded indexes from many real threads. Run against a bare `VectorIndex` these would
 * corrupt it — a torn HNSW graph, a desynchronized key mapping, or an `IndexOutOfBoundsException`
 * from the id-indexed arrays; through the wrapper the outcome must be exactly the single-threaded
 * one.
 */
class ConcurrentIndexStressTest {
    private val dimensions = 8

    /**
     * A cheap deterministic embedding: a point on a circle, in the first two dimensions. The step is
     * a fraction of a turn small enough that the keys used here never wrap onto each other — two keys
     * a full turn apart would be the same vector under Cosine, and top-1 would be ambiguous.
     */
    private fun vectorFor(i: Int): FloatArray {
        val angle = (i * (2.0 * PI / 2048.0)).toFloat()
        return FloatArray(dimensions) { d ->
            when (d) {
                0 -> cos(angle)
                1 -> sin(angle)
                else -> 0f
            }
        }
    }

    @Test
    fun parallelWritersAndReadersLeaveAConsistentVectorIndex() = runBlocking(Dispatchers.Default) {
        val index = ConcurrentVectorIndex<Int>(dimensions = dimensions)
        val writers = 4
        val perWriter = 250
        val searchesFailed = AtomicBoolean(false)
        val stop = AtomicBoolean(false)

        val searchers = List(4) {
            launch {
                while (isActive && !stop.get()) {
                    // Must never throw and must never return a key that was never added.
                    val hits = index.search(vectorFor(7), k = 5)
                    if (hits.any { it.key < 0 || it.key >= writers * perWriter }) {
                        searchesFailed.set(true)
                    }
                    yield()
                }
            }
        }

        List(writers) { w ->
            async {
                for (i in 0 until perWriter) {
                    val key = w * perWriter + i
                    index.add(key, vectorFor(key), mapOf("writer" to w.toString()))
                }
            }
        }.awaitAll()

        stop.set(true)
        searchers.forEach { it.join() }

        assertEquals(writers * perWriter, index.size())
        assertTrue(!searchesFailed.get(), "a concurrent search returned a key that was never added")
        for (key in 0 until writers * perWriter) {
            assertTrue(index.contains(key), "key $key went missing")
        }
        // The graph is still navigable and ranks sanely: querying a key's own vector finds it.
        assertEquals(11, index.search(vectorFor(11), k = 1).single().key)
    }

    @Test
    fun parallelMutationsLeaveAConsistentHybridIndex() = runBlocking(Dispatchers.Default) {
        val index = ConcurrentHybridIndex<Int>(dimensions = dimensions)
        val total = 400

        List(4) { w ->
            async {
                for (key in w until total step 4) {
                    index.add(key, vectorFor(key), "document number $key")
                }
            }
        }.awaitAll()
        assertEquals(total, index.size())

        // Remove half from four coroutines at once, searching throughout.
        val stop = AtomicBoolean(false)
        val searcher = launch {
            while (isActive && !stop.get()) {
                index.search(vectorFor(3), text = "document", k = 10)
                yield()
            }
        }
        List(4) { w ->
            async {
                for (key in w until total step 4) {
                    if (key % 2 == 0) index.remove(key)
                }
            }
        }.awaitAll()
        stop.set(true)
        searcher.join()

        assertEquals(total / 2, index.size())
        assertTrue(index.contains(7))
        assertTrue(!index.contains(8))
        assertEquals(listOf(7), index.searchText("number 7", k = 1).map { it.key })
    }

    @Test
    fun batchedAddIsAtomicForSearchers() = runBlocking(Dispatchers.Default) {
        val index = ConcurrentVectorIndex<Int>(dimensions = dimensions)
        val batch = List(200) { VectorEntry(it, vectorFor(it)) }
        val partialSeen = AtomicBoolean(false)
        val stop = AtomicBoolean(false)

        val watcher = launch {
            while (isActive && !stop.get()) {
                val size = index.size()
                if (size != 0 && size != batch.size) partialSeen.set(true)
                yield()
            }
        }
        index.addAll(batch)
        stop.set(true)
        watcher.join()

        assertEquals(batch.size, index.size())
        assertTrue(!partialSeen.get(), "a searcher observed a half-applied batch")
    }
}
