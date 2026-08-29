package io.github.kromus

/**
 * The mutable working set of one graph traversal: the candidate heaps, the visited marks, and the
 * buffer a layer search writes its result into.
 *
 * This exists as an object rather than as fields on [Hnsw] so that a traversal's state belongs to the
 * *traversal* and not to the index. Keeping it on the index is what makes a query allocate almost
 * nothing — the arrays and heaps are reused across calls — but it also means two traversals running at
 * once overwrite each other's marks and heaps, which is not a race that fails loudly: most concurrent
 * searches throw, and the rest quietly return the wrong neighbours.
 *
 * One scratch may be used by one traversal at a time. An index holds its own for the ordinary
 * single-threaded path, so nothing changes there; a caller that wants searches on several threads
 * gives each of them one of its own.
 */
internal class SearchScratch {
    /** Nodes still worth expanding, nearest first. */
    val frontier: FloatHeap = FloatHeap(minHeap = true)

    /** The best `ef` results so far, farthest first, so the worst is the one to drop. */
    val best: FloatHeap = FloatHeap(minHeap = false)

    // Visited set as marks plus a monotonic epoch: O(1) test and mark, no per-node boxing, and no
    // clearing between traversals (a stale mark simply predates the current epoch).
    private var visitMarks = IntArray(0)
    private var visitEpoch = 0

    /** Output of the most recent layer search: ids ascending by distance, with those distances. */
    var layerIds: IntArray = IntArray(0)
        private set
    var layerDists: FloatArray = FloatArray(0)
        private set
    var layerCount: Int = 0

    /** Distance of the node last returned by a greedy descent, so callers need not recompute it. */
    var greedyDistance: Float = 0f

    fun ensureLayerCapacity(needed: Int) {
        if (layerIds.size >= needed) return
        layerIds = IntArray(needed)
        layerDists = FloatArray(needed)
    }

    /** Opens a new visited epoch, resizing (and, on the rare wrap, clearing) the mark array. */
    fun beginVisits(nodeCount: Int) {
        if (visitMarks.size < nodeCount) visitMarks = visitMarks.copyOf(maxOf(nodeCount, visitMarks.size * 2))
        if (visitEpoch == Int.MAX_VALUE) {
            visitMarks.fill(0)
            visitEpoch = 0
        }
        visitEpoch++
    }

    /** Marks [id] visited in the current epoch; false if it was already visited. */
    fun visit(id: Int): Boolean {
        if (visitMarks[id] == visitEpoch) return false
        visitMarks[id] = visitEpoch
        return true
    }
}
