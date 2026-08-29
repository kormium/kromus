package io.github.kromus

import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClusteredIndexTest {

    private val dim = 32

    private fun corpus(n: Int, seed: Int, clusters: Int = 12): List<FloatArray> {
        val rng = Random(seed)
        val centers = List(clusters) { FloatArray(dim) { rng.nextFloat() * 2f - 1f } }
        return List(n) { i ->
            val c = centers[i % clusters]
            FloatArray(dim) { c[it] + (rng.nextFloat() - 0.5f) * 0.2f }
        }
    }

    private fun normalized(v: FloatArray): FloatArray {
        var n = 0f
        for (x in v) n += x * x
        val inv = 1f / sqrt(n)
        return FloatArray(v.size) { v[it] * inv }
    }

    /** Exact nearest neighbours by cosine, the thing an approximate index is measured against. */
    private fun exact(data: List<FloatArray>, query: FloatArray, k: Int): List<Int> {
        val q = normalized(query)
        return data.indices
            .sortedBy { i ->
                val v = normalized(data[i])
                var dot = 0f
                for (d in 0 until dim) dot += q[d] * v[d]
                1f - dot
            }
            .take(k)
    }

    private fun entries(data: List<FloatArray>) = data.mapIndexed { i, v -> ClusterEntry(i, v) }

    @Test
    fun probingEveryClusterMatchesExactSearch() {
        // The strongest correctness check available: with nothing skipped, an approximate index has
        // no licence to be approximate. Any disagreement here is a bug in the distance path, not a
        // recall trade.
        val data = corpus(400, 1)
        val index = ClusteredIndex.build(dim, entries(data), config = ClusterConfig(clusters = 16))

        val rng = Random(2)
        repeat(20) {
            val q = FloatArray(dim) { rng.nextFloat() * 2f - 1f }
            assertEquals(
                exact(data, q, 10),
                index.search(q, 10, nprobe = 16).map { it.key },
                "exhaustive probing must reproduce exact search",
            )
        }
    }

    @Test
    fun recallRisesWithNprobe() {
        val data = corpus(1200, 3, clusters = 20)
        val index = ClusteredIndex.build(dim, entries(data), config = ClusterConfig(clusters = 34))

        val rng = Random(4)
        val queries = List(40) { FloatArray(dim) { rng.nextFloat() * 2f - 1f } }
        val truth = queries.map { exact(data, it, 10).toSet() }

        fun recallAt(nprobe: Int): Double {
            var hits = 0
            queries.forEachIndexed { i, q ->
                hits += index.search(q, 10, nprobe = nprobe).count { it.key in truth[i] }
            }
            return hits / (10.0 * queries.size)
        }

        val low = recallAt(1)
        val mid = recallAt(4)
        val high = recallAt(16)

        assertTrue(low <= mid + 1e-9, "recall must not fall as more clusters are probed: $low -> $mid")
        assertTrue(mid <= high + 1e-9, "recall must not fall as more clusters are probed: $mid -> $high")
        // And probing more must actually buy something on clustered data, or the index is not working.
        assertTrue(high > low, "probing 16 clusters should beat probing 1: $low -> $high")
        assertTrue(high > 0.9, "clustered data at nprobe=16 should recall well, was $high")
    }

    @Test
    fun theLayoutIsDeterministic() {
        // The clustering decides the byte layout, so the same input has to produce the same one — on
        // this run and on every other target.
        val data = corpus(300, 5)

        fun build() = ClusteredIndex.build(dim, entries(data), config = ClusterConfig(clusters = 12))
        val a = build()
        val b = build()

        assertContentEquals(a.keys.toList(), b.keys.toList(), "entry order must not vary between builds")
        val q = data[7]
        assertEquals(a.search(q, 10).map { it.key }, b.search(q, 10).map { it.key })
    }

    @Test
    fun clustersAreContiguousAndCoverEveryEntry() {
        // The property the whole design rests on: a cluster is a run, not a scatter.
        val data = corpus(500, 6)
        val index = ClusteredIndex.build(dim, entries(data), config = ClusterConfig(clusters = 20))

        assertEquals(0, index.clusterStarts.first())
        assertEquals(index.size, index.clusterStarts.last())
        for (c in 0 until index.clusterCount) {
            assertTrue(index.clusterStarts[c] <= index.clusterStarts[c + 1], "cluster $c runs backwards")
        }
        assertEquals(data.size, index.size)
    }

    @Test
    fun filtersApplyToCandidates() {
        val data = corpus(300, 7)
        val index = ClusteredIndex.build(
            dim,
            data.mapIndexed { i, v -> ClusterEntry(i, v, mapOf("even" to "${i % 2 == 0}")) },
            config = ClusterConfig(clusters = 12),
        )
        val hits = index.search(data[0], 10, nprobe = 12, filter = { it["even"] == "true" })
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.key % 2 == 0 }, "a filter must exclude everything it rejects")
    }

    @Test
    fun handlesCorporaSmallerThanTheClusterCount() {
        val data = corpus(3, 8)
        val index = ClusteredIndex.build(dim, entries(data), config = ClusterConfig(clusters = 50))
        assertEquals(3, index.size)
        assertEquals(exact(data, data[1], 3), index.search(data[1], 3, nprobe = 50).map { it.key })
    }

    @Test
    fun anEmptyIndexSearchesToNothing() {
        val index = ClusteredIndex.build<Int>(dim, emptyList())
        assertEquals(0, index.size)
        assertTrue(index.search(FloatArray(dim), 5).isEmpty())
    }

    @Test
    fun scoresMatchWhatTheGraphIndexReports() {
        // Both index types must mean the same thing by "score", or the two are not comparable.
        val data = corpus(200, 9)
        val graph = VectorIndex<Int>(dim, Metric.Cosine)
        data.forEachIndexed { i, v -> graph.add(i, v) }
        val clustered = ClusteredIndex.build(dim, entries(data), config = ClusterConfig(clusters = 10))

        val q = data[3]
        val fromGraph = graph.search(q, 5).associate { it.key to it.score }
        for (hit in clustered.search(q, 5, nprobe = 10)) {
            val other = fromGraph[hit.key] ?: continue
            assertTrue(
                kotlin.math.abs(hit.score - other) < 1e-4f,
                "score for ${hit.key}: clustered ${hit.score} vs graph $other",
            )
        }
    }

    @Test
    fun aRoundTripReproducesEverything() {
        val data = corpus(400, 21)
        val index = ClusteredIndex.build(
            dim,
            data.mapIndexed { i, v -> ClusterEntry(i, v, mapOf("bucket" to "${i % 3}")) },
            config = ClusterConfig(clusters = 16, nprobe = 5),
        )
        val blob = index.encodeToByteArray(KeyCodec.int)
        val reloaded = decodeClusteredIndex(blob, KeyCodec.int)

        assertEquals(index.size, reloaded.size)
        assertContentEquals(index.keys.toList(), reloaded.keys.toList())
        assertEquals(index.config, reloaded.config)
        assertEquals(mapOf("bucket" to "1"), reloaded.attributesOf(1))

        val rng = Random(22)
        repeat(15) {
            val q = FloatArray(dim) { rng.nextFloat() * 2f - 1f }
            assertEquals(
                index.search(q, 10).map { it.key to it.score },
                reloaded.search(q, 10).map { it.key to it.score },
                "a reloaded index must rank and score identically",
            )
        }
    }

    @Test
    fun encodingIsByteStable() {
        // The clustering decides the layout, so if it were not reproducible the bytes would not be
        // either — and an index could no longer be compared to a cached copy by digest.
        val data = corpus(250, 23)

        fun encode() = ClusteredIndex
            .build(dim, entries(data), config = ClusterConfig(clusters = 12))
            .encodeToByteArray(KeyCodec.int)
        assertContentEquals(encode(), encode())

        // And across a reload, which is where the guarantee is easiest to lose.
        val first = encode()
        assertContentEquals(first, decodeClusteredIndex(first, KeyCodec.int).encodeToByteArray(KeyCodec.int))
    }

    @Test
    fun quantizedClustersRoundTripToo() {
        val data = corpus(300, 24)
        for (q in listOf(Quantization.Int8, Quantization.Binary)) {
            val config = ClusterConfig(clusters = 12, quantization = q)
            val index = ClusteredIndex.build(dim, entries(data), config = config)
            val reloaded = decodeClusteredIndex(index.encodeToByteArray(KeyCodec.int), KeyCodec.int)
            val probe = data[5]
            assertEquals(
                index.search(probe, 8, nprobe = 12).map { it.key },
                reloaded.search(probe, 8, nprobe = 12).map { it.key },
                "$q round trip",
            )
        }
    }

    @Test
    fun theProbeCountIsMeasuredFromTheCorpus() {
        // The point of measuring rather than defaulting: how many clusters a query must open is a
        // property of the data, and the two ends of that are far apart. A blind number is right for
        // one and quietly wrong for the other.
        val tight = ClusteredIndex.build(dim, entries(corpus(1500, 41, clusters = 15)))
        val diffuse = ClusteredIndex.build(dim, entries(scattered(1500, 42)))

        assertTrue(
            tight.nprobe < diffuse.nprobe,
            "a cleanly clustered corpus should need fewer probes than a diffuse one: " +
                "${tight.nprobe} vs ${diffuse.nprobe}",
        )
        assertTrue(tight.estimatedRecall >= 0.95f, "tight corpus fell short: ${tight.estimatedRecall}")
    }

    @Test
    fun theMeasuredProbeCountActuallyReachesItsTarget() {
        // The measurement predicts recall on a sample; this checks the prediction against queries it
        // never saw, because a self-fulfilling estimate would be worse than none.
        val data = scattered(1200, 43)
        val index = ClusteredIndex.build(dim, entries(data), config = ClusterConfig(targetRecall = 0.9f))

        val rng = Random(44)
        val queries = List(40) { FloatArray(dim) { rng.nextFloat() * 2f - 1f } }
        var hits = 0
        queries.forEach { q ->
            val truth = exact(data, q, 10).toSet()
            hits += index.search(q, 10).count { it.key in truth }
        }
        val actual = hits / (10.0 * queries.size)
        assertTrue(actual >= 0.8, "measured nprobe=${index.nprobe} should hold up on unseen queries, got $actual")
    }

    @Test
    fun anExplicitProbeCountIsLeftAlone() {
        val index = ClusteredIndex.build(dim, entries(corpus(400, 45)), config = ClusterConfig(nprobe = 3))
        assertEquals(3, index.nprobe)
        assertTrue(index.estimatedRecall.isNaN(), "nothing was measured, so there is nothing to report")
    }

    @Test
    fun theMeasuredProbeCountSurvivesARoundTrip() {
        val index = ClusteredIndex.build(dim, entries(scattered(600, 46)))
        val reloaded = decodeClusteredIndex(index.encodeToByteArray(KeyCodec.int), KeyCodec.int)
        assertEquals(index.nprobe, reloaded.nprobe)
        assertEquals(index.estimatedRecall, reloaded.estimatedRecall)
    }

    /** A corpus with no cluster structure to find — uniform noise, the hard end for this index. */
    private fun scattered(n: Int, seed: Int): List<FloatArray> {
        val rng = Random(seed)
        return List(n) { FloatArray(dim) { rng.nextFloat() * 2f - 1f } }
    }

    @Test
    fun aStreamedIndexAnswersIdenticallyToOneThatHoldsItsVectors() {
        // The streamed path reads distances out of raw bytes rather than out of arrays. That is a
        // second implementation of the same arithmetic, and a second implementation that disagrees
        // anywhere returns quietly wrong neighbours instead of failing — so it is held to matching
        // exactly, score for score, across quantizations and metrics.
        for (quantization in listOf(Quantization.None, Quantization.Int8)) {
            for (metric in Metric.entries) {
                val data = corpus(500, 51)
                val index = ClusteredIndex.build(
                    dim,
                    data.mapIndexed { i, v -> ClusterEntry(i, v, mapOf("odd" to "${i % 2 == 1}")) },
                    metric = metric,
                    config = ClusterConfig(clusters = 16, nprobe = 4, quantization = quantization),
                )
                val blob = index.encodeToByteArray(KeyCodec.int)
                val resident = decodeClusteredIndex(blob, KeyCodec.int)
                val streamed = viewClusteredIndex(blob, KeyCodec.int)

                val rng = Random(52)
                repeat(15) {
                    val q = FloatArray(dim) { rng.nextFloat() * 2f - 1f }
                    assertEquals(
                        resident.search(q, 10).map { it.key to it.score },
                        streamed.search(q, 10).map { it.key to it.score },
                        "$quantization/$metric",
                    )
                }
                // Filters read attributes, which a streamed index still holds.
                val filtered = streamed.search(data[0], 10, filter = { it["odd"] == "true" })
                assertTrue(filtered.all { it.key % 2 == 1 })
            }
        }
    }

    @Test
    fun aStreamedSearcherReusesItsBufferAcrossQueries() {
        val data = corpus(400, 53)
        val index = ClusteredIndex.build(dim, entries(data), config = ClusterConfig(clusters = 12, nprobe = 3))
        val streamed = viewClusteredIndex(index.encodeToByteArray(KeyCodec.int), KeyCodec.int)

        val searcher = streamed.searcher()
        val q = data[9]
        val expected = searcher.search(q, 10).map { it.key }
        // Repeated use has to be idempotent: a buffer reused between queries is a buffer that can
        // carry the previous one's contents into the next.
        repeat(5) { assertEquals(expected, searcher.search(q, 10).map { it.key }) }
    }

    @Test
    fun binaryCannotBeStreamedAndSaysSo() {
        val data = corpus(200, 54)
        val index = ClusteredIndex.build(
            dim,
            entries(data),
            config = ClusterConfig(clusters = 8, quantization = Quantization.Binary),
        )
        val blob = index.encodeToByteArray(KeyCodec.int)
        val failure = assertFailsWith<KromusFormatException> { viewClusteredIndex(blob, KeyCodec.int) }
        assertTrue("Binary" in failure.message!!, failure.message!!)
        // ...and loads normally, which is the whole reason refusing is acceptable.
        assertEquals(200, decodeClusteredIndex(blob, KeyCodec.int).size)
    }
}
