package io.github.kromus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RrfTest {

    @Test
    fun consensusAcrossListsWins() {
        // "b" is 2nd in both lists; "a" and "x" top only one list each.
        val listA = listOf("a", "b", "c")
        val listB = listOf("x", "b", "y")
        val fused = Rrf.fuse(listOf(listA, listB), limit = 3).map { it.key }
        assertEquals("b", fused.first(), "an item ranked well in both lists should win")
    }

    @Test
    fun singleListPreservesOrder() {
        val fused = Rrf.fuse(listOf(listOf(3, 1, 2)), limit = 3).map { it.key }
        assertEquals(listOf(3, 1, 2), fused)
    }

    @Test
    fun scoresAreDescendingAndRespectLimit() {
        val fused = Rrf.fuse(listOf(listOf("a", "b", "c", "d")), limit = 2)
        assertEquals(2, fused.size)
        assertTrue(fused[0].score >= fused[1].score)
    }

    @Test
    fun deterministicTieBreak() {
        // Disjoint lists -> every key has the same rank-1 contribution; ties break by first-seen order.
        val fused = Rrf.fuse(listOf(listOf("a"), listOf("b")), limit = 2).map { it.key }
        assertEquals(listOf("a", "b"), fused)
    }
}
