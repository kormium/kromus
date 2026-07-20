package io.github.kromus

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersistenceTest {

    @Test
    fun keyCodecRoundTrips() {
        val ints = listOf(0, 1, -1, 42, Int.MAX_VALUE, Int.MIN_VALUE)
        for (v in ints) assertEquals(v, KeyCodec.int.decode(KeyCodec.int.encode(v)))

        val longs = listOf(0L, -1L, 123456789012L, Long.MAX_VALUE, Long.MIN_VALUE)
        for (v in longs) assertEquals(v, KeyCodec.long.decode(KeyCodec.long.encode(v)))

        val strings = listOf("", "a", "hello world", "юникод ✓", "line\nbreak")
        for (v in strings) assertEquals(v, KeyCodec.string.decode(KeyCodec.string.encode(v)))
    }

    @Test
    fun vectorIndexSurvivesRoundTrip() {
        val dim = 32
        val rng = Random(7)
        val index = VectorIndex<Int>(dim, Metric.Cosine, HnswConfig(seed = 5))
        val vectors = (0 until 500).associateWith { randomVector(dim, rng) }
        vectors.forEach { (k, v) -> index.add(k, v) }
        // remove some to exercise deleted-node serialization
        listOf(3, 17, 100, 250, 499).forEach { index.remove(it) }

        val bytes = index.encodeToByteArray(KeyCodec.int)
        val restored = decodeVectorIndex(bytes, KeyCodec.int)

        assertEquals(index.size, restored.size)
        val queries = (0 until 20).map { randomVector(dim, rng) }
        for (q in queries) {
            assertEquals(
                index.search(q, k = 10, efSearch = 100),
                restored.search(q, k = 10, efSearch = 100),
                "restored index must return identical results",
            )
        }
    }

    @Test
    fun vectorIndexRoundTripAcrossMetrics() {
        val dim = 10
        for (metric in Metric.entries) {
            val rng = Random(metric.ordinal.toLong())
            val index = VectorIndex<String>(dim, metric)
            repeat(120) { index.add("k$it", randomVector(dim, rng)) }
            val restored = decodeVectorIndex(index.encodeToByteArray(KeyCodec.string), KeyCodec.string)
            val q = randomVector(dim, rng)
            assertEquals(index.search(q, 8), restored.search(q, 8), "metric=$metric")
        }
    }

    @Test
    fun textIndexSurvivesRoundTrip() {
        val index = TextIndex<Int>(analyzer = Analyzer.standard(stopwords = setOf("the", "a")))
        val docs = listOf(
            "the quick brown fox jumps",
            "structured concurrency in kotlin coroutines",
            "a guide to gradle build configuration",
            "sourdough bread baking at home",
            "kotlin multiplatform mobile development",
        )
        docs.forEachIndexed { i, text -> index.add(i, text) }
        index.remove(0)

        // The analyzer isn't serialized, so reload with the same one.
        val restored = decodeTextIndex(
            index.encodeToByteArray(KeyCodec.int),
            KeyCodec.int,
            Analyzer.standard(stopwords = setOf("the", "a")),
        )

        assertEquals(index.size, restored.size)
        for (query in listOf("kotlin coroutines", "gradle", "bread", "multiplatform")) {
            assertSameRanking(index.search(query, 5), restored.search(query, 5), "query=$query")
        }
    }

    @Test
    fun hybridIndexSurvivesRoundTrip() {
        val dim = 16
        val rng = Random(11)
        val index = HybridIndex<String>(dimensions = dim)
        repeat(150) {
            index.add("doc-$it", randomVector(dim, rng), "document number $it about topic ${it % 7}")
        }
        index.remove("doc-5")

        val restored = decodeHybridIndex(index.encodeToByteArray(KeyCodec.string), KeyCodec.string)

        assertEquals(index.size, restored.size)
        assertTrue("doc-0" in restored)
        val qv = randomVector(dim, rng)
        // Vector search is bit-exact across the round trip; RRF and BM25 are compared by ranking
        // (their scores can differ by ~1e-9 from float accumulation order — see assertSameRanking).
        assertEquals(index.searchVector(qv, 10), restored.searchVector(qv, 10))
        assertSameRanking(index.search(qv, "topic 3", k = 10), restored.search(qv, "topic 3", k = 10))
        assertSameRanking(index.searchText("document", 10), restored.searchText("document", 10))
    }

    private fun randomVector(dim: Int, rng: Random): FloatArray =
        FloatArray(dim) { rng.nextFloat() * 2f - 1f }

    /**
     * Asserts two result lists have the same keys in the same order, with scores equal up to a small
     * tolerance. Exact float equality is not guaranteed across two independently built indexes: the
     * ordering of hash-map iteration (and thus BM25 summation order) differs by platform, so scores
     * can diverge in their last digits while the ranking stays stable.
     */
    private fun <K> assertSameRanking(
        expected: List<SearchResult<K>>,
        actual: List<SearchResult<K>>,
        message: String = "",
    ) {
        assertEquals(expected.map { it.key }, actual.map { it.key }, "keys differ: $message")
        for (i in expected.indices) {
            val diff = kotlin.math.abs(expected[i].score - actual[i].score)
            assertTrue(
                diff <= 1e-3f * (1f + kotlin.math.abs(expected[i].score)),
                "score[$i] ${expected[i].score} vs ${actual[i].score}: $message",
            )
        }
    }
}
