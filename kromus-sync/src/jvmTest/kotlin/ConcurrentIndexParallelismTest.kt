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
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wrappers' guarantees are only observable against real threads, so these are JVM-only.
 *
 * They pin down that the lock serializes *searches against each other*, not only searches against
 * writes. That is not an accident of the implementation and not an over-cautious choice: [VectorIndex]
 * searches are not re-entrant. `Hnsw` reuses its per-search scratch — the visited-mark array and its
 * epoch, the candidate heaps, the layer result buffers — across calls, which is what makes a query
 * allocate almost nothing, and also what makes two concurrent queries corrupt each other. Run
 * unguarded, most of them throw and the rest quietly return the wrong neighbours.
 *
 * So anything that lets readers in parallel has to give each search its own scratch first. Until then
 * a plain `Mutex` is the correct guard, and these tests fail if someone loosens it.
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
}
