package io.github.kromus

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisitBudgetTest {
    private val dim = 24

    /** 1000 entries where only every hundredth carries `g=7` — the shape that makes a filter costly. */
    private fun selectiveIndex(config: HnswConfig = HnswConfig()): VectorIndex<Int> {
        val index = VectorIndex<Int>(dim, config = config)
        val rng = Random(17)
        repeat(1000) { index.add(it, randomVector(dim, rng), mapOf("g" to (it % 100).toString())) }
        return index
    }

    @Test
    fun unboundedSearchStillFillsTheResultUnderASelectiveFilter() {
        val index = selectiveIndex()
        val hits = index.search(randomVector(dim, Random(2)), k = 10, efSearch = 64) { it["g"] == "7" }
        assertEquals(10, hits.size, "with no budget the traversal keeps going until it finds k matches")
        assertTrue(hits.all { it.key % 100 == 7 })
    }

    @Test
    fun budgetBoundsTheTraversalAndTruncatesTheResult() {
        val index = selectiveIndex()
        val hits = index.search(
            randomVector(dim, Random(2)),
            k = 10,
            efSearch = 64,
            maxVisited = 100,
            filter = { it["g"] == "7" },
        )
        // Only 10 of 1000 entries match, so 100 visits cannot turn up all of them: the query stops
        // early instead of walking the whole graph, and says so by returning fewer hits.
        assertTrue(hits.size < 10, "a budget must cut the search short, got ${hits.size} hits")
        assertTrue(hits.all { it.key % 100 == 7 }, "whatever it does return must still match the filter")
    }

    @Test
    fun budgetIsConfigurablePerIndex() {
        val index = selectiveIndex(HnswConfig(maxVisited = 100))
        val hits = index.search(randomVector(dim, Random(2)), k = 10, efSearch = 64) { it["g"] == "7" }
        assertTrue(hits.size < 10, "the config default applies when the call does not override it")

        val unbounded = index.search(
            randomVector(dim, Random(2)),
            k = 10,
            efSearch = 64,
            maxVisited = 0,
            filter = { it["g"] == "7" },
        )
        assertEquals(10, unbounded.size, "an explicit 0 lifts the configured budget")
    }

    @Test
    fun budgetDoesNotDisturbOrdinarySearches() {
        val index = selectiveIndex()
        val query = randomVector(dim, Random(2))
        val unbounded = index.search(query, k = 10, efSearch = 64)
        val budgeted = index.search(query, k = 10, efSearch = 64, maxVisited = 5000)
        assertEquals(unbounded, budgeted, "a budget above what the search needs changes nothing")
        assertEquals(10, budgeted.size)
    }

    @Test
    fun budgetIsNeverSmallerThanTheCandidateList() {
        val index = selectiveIndex()
        // A budget below ef would starve an unfiltered query of results; it is raised to ef instead.
        val hits = index.search(randomVector(dim, Random(2)), k = 10, efSearch = 64, maxVisited = 1)
        assertEquals(10, hits.size)
    }

    @Test
    fun budgetAppliesThroughHybridSearch() {
        val hybrid = HybridIndex<Int>(dim, hnswConfig = HnswConfig(maxVisited = 100))
        val rng = Random(17)
        repeat(1000) { hybrid.add(it, randomVector(dim, rng), "doc $it", mapOf("g" to (it % 100).toString())) }
        val hits = hybrid.searchVector(randomVector(dim, Random(2)), k = 10) { it["g"] == "7" }
        assertTrue(hits.size < 10)
    }

    private fun randomVector(dim: Int, rng: Random): FloatArray =
        FloatArray(dim) { rng.nextFloat() * 2f - 1f }
}
