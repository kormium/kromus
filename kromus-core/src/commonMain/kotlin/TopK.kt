package io.github.kromus

/**
 * Selects the best [capacity] of an arbitrary number of scored keys in `O(n log k)` without sorting
 * the whole candidate set — the ranking step of [TextIndex], where the score map can hold every
 * document that shares a single query term.
 *
 * Ranking is by descending score, ties broken by ascending [ordinal] (the document's insertion
 * order), so the outcome never depends on hash iteration order and is identical on every platform.
 */
internal class TopK<K>(
    private val capacity: Int,
) {
    private val keys = arrayOfNulls<Any?>(capacity)
    private val scores = DoubleArray(capacity)
    private val ordinals = IntArray(capacity)

    /** Number of entries held; a min-heap over [worse], so slot 0 is the weakest kept entry. */
    private var size = 0

    /** True when the entry at [a] ranks below the one at [b] (weaker score, or later ordinal). */
    private fun worse(a: Int, b: Int): Boolean =
        if (scores[a] != scores[b]) scores[a] < scores[b] else ordinals[a] > ordinals[b]

    fun offer(key: K, score: Double, ordinal: Int) {
        if (capacity == 0) return
        if (size < capacity) {
            keys[size] = key
            scores[size] = score
            ordinals[size] = ordinal
            siftUp(size)
            size++
            return
        }
        // Full: replace the weakest entry only if the newcomer beats it.
        val weakerThanRoot = if (score != scores[0]) score < scores[0] else ordinal > ordinals[0]
        if (weakerThanRoot) return
        keys[0] = key
        scores[0] = score
        ordinals[0] = ordinal
        siftDown(0)
    }

    /** Drains the heap into a best-first list. */
    @Suppress("UNCHECKED_CAST")
    fun toSortedList(): List<SearchResult<K>> {
        val out = arrayOfNulls<SearchResult<K>>(size)
        var i = size - 1
        while (size > 0) {
            out[i--] = SearchResult(keys[0] as K, scores[0].toFloat())
            size--
            if (size > 0) {
                keys[0] = keys[size]
                scores[0] = scores[size]
                ordinals[0] = ordinals[size]
                siftDown(0)
            }
        }
        return (out as Array<SearchResult<K>>).asList()
    }

    private fun siftUp(from: Int) {
        var i = from
        while (i > 0) {
            val parent = (i - 1) / 2
            if (!worse(i, parent)) break
            swap(i, parent)
            i = parent
        }
    }

    private fun siftDown(from: Int) {
        var i = from
        while (true) {
            val left = 2 * i + 1
            val right = left + 1
            var weakest = i
            if (left < size && worse(left, weakest)) weakest = left
            if (right < size && worse(right, weakest)) weakest = right
            if (weakest == i) break
            swap(i, weakest)
            i = weakest
        }
    }

    private fun swap(a: Int, b: Int) {
        val tk = keys[a]; keys[a] = keys[b]; keys[b] = tk
        val ts = scores[a]; scores[a] = scores[b]; scores[b] = ts
        val to = ordinals[a]; ordinals[a] = ordinals[b]; ordinals[b] = to
    }
}
