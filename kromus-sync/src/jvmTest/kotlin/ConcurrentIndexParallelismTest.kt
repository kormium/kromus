package io.github.kromus.sync

import io.github.kromus.Metric
import io.github.kromus.VectorIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wrappers' guarantees are only observable against real threads, so these are JVM-only.
 *
 * Searches run in parallel and writes run alone. What made that possible was moving a traversal's
 * working state — visited marks, candidate heaps, layer buffers — off the index and into a scratch a
 * `searcher()` owns. While it lived on the index it was reused between calls, which is what makes a
 * query allocate almost nothing and equally what made two concurrent queries corrupt each other:
 * unguarded, most threw and the rest quietly returned the wrong neighbours.
 *
 * These tests hold the wrappers to both halves: results stay correct under concurrency, and readers
 * genuinely overlap rather than queueing.
 */
class ConcurrentIndexParallelismTest {

    private val dim = 48

    private fun corpus(n: Int, seed: Int): List<FloatArray> {
        val rng = Random(seed)
        return List(n) { FloatArray(dim) { rng.nextFloat() * 2f - 1f } }
    }

    @Test
    fun concurrentSearchesThroughTheWrapperAlwaysAgreeWithTheTruth() = runBlocking {
        val data = corpus(2000, 1)
        val bare = VectorIndex<Int>(dim, Metric.Cosine)
        data.forEachIndexed { i, v -> bare.add(i, v) }

        val queries = corpus(120, 2)
        val truth = queries.map { q -> bare.search(q, 10).map { it.key } }

        val guarded = bare.concurrent()
        withContext(Dispatchers.Default) {
            val results = (0 until 8).map { worker ->
                async {
                    var checked = 0
                    repeat(240) { r ->
                        val qi = (worker * 240 + r) % queries.size
                        assertEquals(truth[qi], guarded.search(queries[qi], 10).map { it.key })
                        checked++
                    }
                    checked
                }
            }.awaitAll()
            assertEquals(8 * 240, results.sum())
        }
    }

    @Test
    fun searchesStayCorrectWhileAWriterIsRunning() = runBlocking {
        val data = corpus(1500, 3)
        val guarded = VectorIndex<Int>(dim, Metric.Cosine).concurrent()
        data.take(500).forEachIndexed { i, v -> guarded.add(i, v) }

        val queries = corpus(60, 4)
        withContext(Dispatchers.Default) {
            val writer = launch {
                data.drop(500).forEachIndexed { i, v -> guarded.add(500 + i, v) }
            }
            val readers = (0 until 6).map {
                async {
                    repeat(200) { r ->
                        val hits = guarded.search(queries[r % queries.size], 10)
                        // Under a writer the corpus changes, so the identity of the neighbours is not
                        // fixed — what must hold is that every search returns a well-formed result of
                        // live keys in descending score, which corruption does not.
                        assertTrue(hits.size <= 10)
                        assertEquals(hits.map { it.score }.sortedDescending(), hits.map { it.score })
                        assertEquals(hits.map { it.key }.distinct(), hits.map { it.key })
                    }
                }
            }
            writer.join()
            readers.awaitAll()
        }
        assertEquals(1500, guarded.size())
    }

    @Test
    fun aWriterIsNotStarvedByASteadyStreamOfSearches() = runBlocking {
        // kotlinx Mutex is fair (FIFO), so a writer queued behind readers is served in turn rather
        // than pushed back indefinitely. This is what makes a plain Mutex acceptable despite
        // serializing reads: the sync coroutine keeping the index fresh still makes progress.
        val data = corpus(800, 5)
        val guarded = VectorIndex<Int>(dim, Metric.Cosine).concurrent()
        data.take(400).forEachIndexed { i, v -> guarded.add(i, v) }
        val query = corpus(1, 6).first()

        withContext(Dispatchers.Default) {
            coroutineScope {
                val readers = (0 until 6).map {
                    launch { repeat(400) { guarded.search(query, 10) } }
                }
                val written = async {
                    var n = 0
                    data.drop(400).forEachIndexed { i, v ->
                        guarded.add(400 + i, v)
                        n++
                    }
                    n
                }
                assertEquals(400, written.await())
                readers.forEach { it.join() }
            }
        }
        assertEquals(800, guarded.size())
    }

    @Test
    fun readersAreInsideTheLockAtTheSameTime() {
        // The property the readers-writer lock exists for, asserted structurally rather than by
        // timing. A stopwatch would be measuring the machine: six readers cannot outrun two cores, so
        // a speedup assertion says more about the runner than about the lock. Two readers rendezvous
        // *inside* read {} instead — under a lock that serializes them the second never arrives and
        // the latch times out.
        val guarded = VectorIndex<Int>(dim, Metric.Cosine).concurrent()
        corpus(50, 21).forEachIndexed { i, v -> runBlocking { guarded.add(i, v) } }

        val bothInside = CountDownLatch(2)
        val met = AtomicBoolean(false)

        runBlocking {
            // Dispatchers.IO, not Default: the blocks below park a thread on purpose, and Default is
            // sized to the cores, so on a small runner they would starve it rather than overlap.
            withContext(Dispatchers.IO) {
                repeat(2) {
                    launch {
                        guarded.read {
                            bothInside.countDown()
                            if (bothInside.await(10, TimeUnit.SECONDS)) met.set(true)
                        }
                    }
                }
            }
        }

        assertTrue(met.get(), "two readers must be able to hold the lock at once")
    }

    @Test
    fun aWriterExcludesReaders() {
        // The other half of the contract, and the reason the above is not simply "no lock at all".
        val guarded = VectorIndex<Int>(dim, Metric.Cosine).concurrent()
        corpus(50, 22).forEachIndexed { i, v -> runBlocking { guarded.add(i, v) } }

        val writerInside = CountDownLatch(1)
        val readerEntered = AtomicBoolean(false)

        runBlocking {
            withContext(Dispatchers.IO) {
                val writer = launch {
                    guarded.use {
                        writerInside.countDown()
                        // Hold the lock long enough that a reader admitted concurrently would be seen.
                        Thread.sleep(300)
                    }
                }
                writerInside.await(10, TimeUnit.SECONDS)
                val reader = launch {
                    guarded.read { readerEntered.set(true) }
                }
                // While the writer holds it, no reader may be inside.
                Thread.sleep(150)
                assertTrue(!readerEntered.get(), "a reader got in while a writer held the lock")
                writer.join()
                reader.join()
            }
        }
        assertTrue(readerEntered.get(), "the reader should get in once the writer is done")
    }

    @Test
    fun aCancelledWriterDoesNotStrandTheLock() {
        // The lock is handed over rather than raced for, which means a waiter can be made the holder
        // a moment before it is cancelled. Leaving it at that loses the lock for good: `holders` says
        // a writer has it, and that writer is never coming. Everything afterwards blocks forever.
        val guarded = VectorIndex<Int>(dim, Metric.Cosine).concurrent()
        corpus(30, 31).forEachIndexed { i, v -> runBlocking { guarded.add(i, v) } }
        val query = corpus(1, 32).first()

        runBlocking {
            withContext(Dispatchers.IO) {
                val holding = CountDownLatch(1)
                val release = CountDownLatch(1)

                // Occupy the lock so the next writer has to queue.
                val holder = launch {
                    guarded.use {
                        holding.countDown()
                        release.await(10, TimeUnit.SECONDS)
                    }
                }
                holding.await(10, TimeUnit.SECONDS)

                // Queue a writer, then cancel it — including in the window where the hand-off has
                // already named it the holder.
                repeat(20) {
                    val queued = launch { guarded.add(1000 + it, query) }
                    Thread.sleep(2)
                    queued.cancel()
                }

                release.countDown()
                holder.join()

                // If any of those cancellations kept the lock, this never returns.
                withTimeout(10_000) {
                    guarded.add(9999, query)
                    assertTrue(guarded.search(query, 5).isNotEmpty())
                }
            }
        }
    }
}
