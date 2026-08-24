package io.github.kromus

/**
 * A growable list of ints backed by a primitive array — the adjacency-list primitive of the HNSW
 * graph. A `MutableList<Int>` would box every neighbour, which on a large index costs more memory
 * than the vectors themselves (each node keeps up to `2 * m` links on layer 0 alone).
 */
internal class IntArrayList(
    initialCapacity: Int = 4,
) {
    var data: IntArray = IntArray(initialCapacity)
        private set

    var size: Int = 0
        private set

    operator fun get(index: Int): Int = data[index]

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(if (data.size == 0) 4 else data.size * 2)
        data[size++] = value
    }

    /** Replaces the contents with the first [count] elements of [source]. */
    fun setAll(source: IntArray, count: Int) {
        if (data.size < count) data = IntArray(count)
        source.copyInto(data, 0, 0, count)
        size = count
    }

    fun toIntArray(): IntArray = data.copyOf(size)
}
