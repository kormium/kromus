package io.github.kromus

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Searchers are documented to run at the same time, and only real threads can hold them to it.
 *
 * The quantized stores are where that promise is easiest to break without noticing. Binary
 * quantization compares a query against stored sign vectors through a lookup table built once per
 * query — scratch that lives on the store, which every searcher of an index shares. Two of them
 * building tables at once overwrite each other and read half of the other's, which is not a crash but
 * a distance that is merely plausible: the search returns, in order, with the wrong neighbours.
 */
class BinaryStoreConcurrencyTest {

    private val dim = 64

    private fun corpus(n: Int, seed: Int): List<FloatArray> {
        val rng = Random(seed)
        val centres = List(16) { FloatArray(dim) { rng.nextFloat() * 2f - 1f } }
        return List(n) { i ->
            val c = centres[i % centres.size]
            FloatArray(dim) { c[it] + (rng.nextFloat() - 0.5f) * 0.4f }
        }
    }

    private fun <K> assertAgreesUnderThreads(truth: List<List<K>>, queries: List<FloatArray>, of: () -> Searcher<K>) {
        val workers = 8
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val tasks = (0 until workers).map { w ->
                pool.submit<Int> {
                    val searcher = of()
                    var checked = 0
                    repeat(200) { r ->
                        val qi = (w * 200 + r) % queries.size
                        assertEquals(
                            truth[qi],
                            searcher.search(queries[qi], 10).map { it.key },
                            "query $qi came back differently under concurrency",
                        )
                        checked++
                    }
                    checked
                }
            }
            assertEquals(workers * 200, tasks.sumOf { it.get(60, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun searchersOfABinaryClusteredIndexAgreeWhenRunAtOnce() {
        val data = corpus(3000, 11)
        val index = IvfIndex.build(
            dim,
            data.mapIndexed { i, v -> IvfEntry(i, v) },
            config = IvfConfig(clusters = 24, nprobe = 4, quantization = Quantization.Binary),
        )
        val queries = corpus(40, 12)
        val truth = queries.map { q -> index.search(q, 10).map { it.key } }
        assertTrue(truth.all { it.size == 10 })

        assertAgreesUnderThreads(truth, queries) { index.searcher() }
    }

    @Test
    fun searchersOfABinaryFlatIndexAgreeWhenRunAtOnce() {
        val data = corpus(1500, 13)
        val index = FlatIndex.build(
            dim,
            data.mapIndexed { i, v -> IvfEntry(i, v) },
            quantization = Quantization.Binary,
        )
        val queries = corpus(40, 14)
        val truth = queries.map { q -> index.searcher().search(q, 10).map { it.key } }

        assertAgreesUnderThreads(truth, queries) { index.searcher() }
    }

    @Test
    fun searchersOfABinaryGraphIndexAgreeWhenRunAtOnce() {
        val index = VectorIndex<Int>(dim, Metric.Cosine, HnswConfig(quantization = Quantization.Binary))
        corpus(3000, 15).forEachIndexed { i, v -> index.add(i, v) }
        val queries = corpus(40, 16)
        val truth = queries.map { q -> index.searcher().search(q, 10).map { it.key } }

        assertAgreesUnderThreads(truth, queries) { index.searcher() }
    }
}
