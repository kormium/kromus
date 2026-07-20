package io.github.kromus

/**
 * A compact binary heap of `(id, key)` pairs kept in parallel primitive arrays — no per-element
 * boxing. Configured once as a min-heap or a max-heap. Used for the two candidate structures in
 * HNSW's layer search (a min-heap of the frontier to expand, a max-heap of the best results found).
 */
internal class FloatHeap(private val minHeap: Boolean, initialCapacity: Int = 16) {
    private var ids = IntArray(initialCapacity)
    private var keys = FloatArray(initialCapacity)

    var size: Int = 0
        private set

    fun isEmpty(): Boolean = size == 0

    fun peekId(): Int = ids[0]

    fun peekKey(): Float = keys[0]

    /** True when [a]'s key should sit above [b]'s (closer to the root) for this heap's ordering. */
    private fun higher(a: Int, b: Int): Boolean =
        if (minHeap) keys[a] < keys[b] else keys[a] > keys[b]

    fun push(id: Int, key: Float) {
        if (size == ids.size) grow()
        ids[size] = id
        keys[size] = key
        siftUp(size)
        size++
    }

    /** Removes and returns the id at the root (smallest for a min-heap, largest for a max-heap). */
    fun pop(): Int {
        val root = ids[0]
        size--
        if (size > 0) {
            ids[0] = ids[size]
            keys[0] = keys[size]
            siftDown(0)
        }
        return root
    }

    private fun siftUp(from: Int) {
        var i = from
        while (i > 0) {
            val parent = (i - 1) / 2
            if (higher(i, parent)) {
                swap(i, parent)
                i = parent
            } else {
                break
            }
        }
    }

    private fun siftDown(from: Int) {
        var i = from
        while (true) {
            val left = 2 * i + 1
            val right = left + 1
            var best = i
            if (left < size && higher(left, best)) best = left
            if (right < size && higher(right, best)) best = right
            if (best == i) break
            swap(i, best)
            i = best
        }
    }

    private fun swap(a: Int, b: Int) {
        val ti = ids[a]; ids[a] = ids[b]; ids[b] = ti
        val tk = keys[a]; keys[a] = keys[b]; keys[b] = tk
    }

    private fun grow() {
        val n = ids.size * 2
        ids = ids.copyOf(n)
        keys = keys.copyOf(n)
    }
}
