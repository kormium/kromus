package io.github.kromus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextIndexTest {

    @Test
    fun ranksMatchingDocumentFirst() {
        val index = TextIndex<String>()
        index.add("a", "structured concurrency in kotlin coroutines")
        index.add("b", "sourdough bread baking at home")
        index.add("c", "a guide to gradle build configuration")

        val hits = index.search("kotlin coroutines", k = 3)
        assertEquals("a", hits.first().key)
    }

    @Test
    fun rareTermOutweighsCommonTerm() {
        val index = TextIndex<String>()
        // "data" is common (low idf); "coroutines" is rare (high idf).
        index.add("common1", "data data data pipeline")
        index.add("common2", "data warehouse and data lake")
        index.add("rare", "data coroutines")

        val hits = index.search("data coroutines", k = 3)
        assertEquals("rare", hits.first().key, "the doc with the rare query term should rank first")
    }

    @Test
    fun removeAndReAdd() {
        val index = TextIndex<Int>()
        index.add(1, "alpha beta gamma")
        index.add(2, "beta gamma delta")
        assertEquals(2, index.size)

        assertTrue(index.remove(1))
        assertFalse(index.remove(1))
        assertEquals(1, index.size)
        assertTrue(index.search("alpha", k = 5).isEmpty())

        index.add(2, "alpha only now") // replace doc 2
        val hits = index.search("alpha", k = 5).map { it.key }
        assertEquals(listOf(2), hits)
    }

    @Test
    fun emptyAndNonMatchingQueries() {
        val index = TextIndex<Int>()
        index.add(1, "hello world")
        assertTrue(index.search("   ??? ", k = 5).isEmpty(), "query with no terms yields nothing")
        assertTrue(index.search("absent", k = 5).isEmpty(), "no matching term yields nothing")
        assertTrue(TextIndex<Int>().search("anything", k = 5).isEmpty(), "empty index yields nothing")
    }

    @Test
    fun customStopwordsAreIgnored() {
        val index = TextIndex<String>(analyzer = Analyzer.standard(stopwords = setOf("the", "a", "of")))
        index.add("x", "the state of the art")
        // Only "state" and "art" are indexed; a stopword-only query matches nothing.
        assertTrue(index.search("the a of", k = 5).isEmpty())
        assertEquals("x", index.search("art", k = 5).first().key)
    }
}
