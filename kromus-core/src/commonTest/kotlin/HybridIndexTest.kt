package io.github.kromus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HybridIndexTest {

    @Test
    fun fusionRecoversWhatEitherRetrieverAloneMisses() {
        val index = HybridIndex<String>(dimensions = 4)

        // semantic match: vector aligned with the query, but text shares no query token
        index.add("semantic", floatArrayOf(1f, 0f, 0f, 0f), "banana fruit smoothie recipe")
        // keyword match: vector orthogonal to the query (vector search misses it), text has the token
        index.add("keyword", floatArrayOf(0f, 0f, 0f, 1f), "kotlin coroutines tutorial")
        index.add("filler1", floatArrayOf(0f, 1f, 0f, 0f), "weather forecast today")
        index.add("filler2", floatArrayOf(0f, 0f, 1f, 0f), "cooking pasta at home")

        val queryVector = floatArrayOf(1f, 0f, 0f, 0f)
        val queryText = "kotlin"

        // Each retriever alone finds only its own side.
        assertEquals("semantic", index.searchVector(queryVector, k = 1).first().key)
        assertEquals("keyword", index.searchText(queryText, k = 1).first().key)

        // Hybrid recovers both.
        val hybrid = index.search(queryVector, queryText, k = 2).map { it.key }
        assertTrue("semantic" in hybrid, "vector-side hit must survive fusion")
        assertTrue("keyword" in hybrid, "keyword-side hit must survive fusion")
    }

    @Test
    fun addRemoveContainsAndSize() {
        val index = HybridIndex<Int>(dimensions = 3)
        index.add(1, floatArrayOf(1f, 0f, 0f), "alpha")
        index.add(2, floatArrayOf(0f, 1f, 0f), "beta")
        assertEquals(2, index.size)
        assertTrue(1 in index)

        assertTrue(index.remove(1))
        assertFalse(1 in index)
        assertEquals(1, index.size)

        val hits = index.search(floatArrayOf(1f, 0f, 0f), "alpha", k = 5).map { it.key }
        assertFalse(1 in hits, "removed entry must not appear from either modality")
    }

    @Test
    fun reAddReplacesBothModalities() {
        val index = HybridIndex<String>(dimensions = 2)
        index.add("x", floatArrayOf(1f, 0f), "first text")
        index.add("x", floatArrayOf(0f, 1f), "second text")
        assertEquals(1, index.size)
        assertTrue(index.searchText("first", k = 5).isEmpty(), "old text must be gone")
        assertEquals("x", index.searchText("second", k = 5).first().key)
    }
}
