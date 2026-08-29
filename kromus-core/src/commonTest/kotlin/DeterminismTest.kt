package io.github.kromus

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DeterminismTest {
    @Test
    fun bm25TiesBreakByInsertionOrder() {
        // Identical documents score identically; without a stable tie-break the winner would depend
        // on hash iteration order and differ between platforms.
        val index = TextIndex<String>()
        for (key in listOf("d", "a", "c", "b")) index.add(key, "kotlin coroutines")

        assertEquals(
            listOf("d", "a", "c", "b"),
            index.search("kotlin coroutines", k = 4).map { it.key },
            "equal scores must resolve to insertion order",
        )
    }

    @Test
    fun bm25TieBreakSurvivesPersistence() {
        val index = TextIndex<String>()
        for (key in listOf("d", "a", "c", "b")) index.add(key, "kotlin coroutines")
        val restored = decodeTextIndex(index.encodeToByteArray(KeyCodec.string), KeyCodec.string)

        assertEquals(
            index.search("kotlin coroutines", k = 4).map { it.key },
            restored.search("kotlin coroutines", k = 4).map { it.key },
        )
    }

    @Test
    fun repeatedTermsInAQueryDoNotChangeTheRanking() {
        val index = TextIndex<String>()
        index.add("a", "kotlin kotlin coroutines")
        index.add("b", "kotlin flows")

        assertEquals(
            index.search("kotlin coroutines", k = 2).map { it.key },
            index.search("kotlin kotlin coroutines coroutines", k = 2).map { it.key },
            "a term contributes once per query, however often it is written",
        )
    }

    @Test
    fun topKSelectionAgreesWithAFullSort() {
        val index = TextIndex<Int>()
        repeat(300) { index.add(it, "term$it common ${"repeat ".repeat(it % 7)}") }

        val all = index.search("common", k = 300)
        val top10 = index.search("common", k = 10)

        assertEquals(all.take(10), top10, "bounded selection must agree with ranking the whole corpus")
        assertEquals(all.map { it.score }.sortedDescending(), all.map { it.score })
    }

    @Test
    fun reEncodingAReloadedIndexReproducesTheBytes() {
        // Byte-stability has to hold across a reload, not just between two encodes of the same live
        // object — comparing a rebuilt index against a cached one by digest is the whole point of the
        // guarantee. It is easy to lose: `add` and the decoder build the term/attribute maps
        // differently, so anything that leaned on their iteration order would encode identical content
        // into different bytes.
        val index = TextIndex<Int>()
        listOf(
            "kotlin coroutines structured concurrency",
            "hierarchical navigable small world graphs",
            "bm25 ranking and inverted indexes",
        ).forEachIndexed { i, t -> index.add(i, t, mapOf("lang" to "kotlin", "kind" to "doc$i")) }

        val first = index.encodeToByteArray(KeyCodec.int)
        val second = decodeTextIndex(first, KeyCodec.int).encodeToByteArray(KeyCodec.int)
        assertContentEquals(first, second, "text index")
    }

    @Test
    fun attributeMapImplementationDoesNotChangeTheBytes() {
        // Attributes come straight from the caller, so the encoding must not depend on which Map they
        // arrived in — two indexes holding equal content have to hash equal.
        val dim = 8
        val vector = FloatArray(dim) { it * 0.125f }
        val pairs = listOf("z" to "1", "a" to "2", "m" to "3")

        val viaHash = VectorIndex<Int>(dim, Metric.Cosine)
        viaHash.add(0, vector, HashMap(pairs.toMap()))

        val viaLinked = VectorIndex<Int>(dim, Metric.Cosine)
        viaLinked.add(
            0, vector,
            LinkedHashMap<String, String>().apply {
                pairs.reversed().forEach { put(it.first, it.second) }
            },
        )

        assertContentEquals(
            viaHash.encodeToByteArray(KeyCodec.int),
            viaLinked.encodeToByteArray(KeyCodec.int),
        )
    }
}
