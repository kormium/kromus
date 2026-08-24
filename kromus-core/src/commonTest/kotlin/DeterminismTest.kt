package io.github.kromus

import kotlin.test.Test
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
}
