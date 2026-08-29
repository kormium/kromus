package io.github.kromus

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IncrementalPersistenceTest {

    private fun vectors(n: Int, dim: Int, seed: Int): List<FloatArray> {
        val rng = Random(seed)
        return List(n) { FloatArray(dim) { rng.nextFloat() * 2f - 1f } }
    }

    private fun <K> assertSameRanking(expected: VectorIndex<K>, actual: VectorIndex<K>, dim: Int, seed: Int) {
        assertEquals(expected.size, actual.size, "live entry count")
        assertEquals(expected.keys, actual.keys, "live keys")
        val rng = Random(seed)
        repeat(20) {
            val q = FloatArray(dim) { rng.nextFloat() * 2f - 1f }
            assertEquals(
                expected.search(q, 10).map { it.key },
                actual.search(q, 10).map { it.key },
                "ranking for the same query must survive a delta replay",
            )
        }
    }

    @Test
    fun deltaReplayReproducesTheLiveIndex() {
        val dim = 24
        val data = vectors(240, dim, 1)
        val index = VectorIndex<Int>(dim, Metric.Cosine)
        data.take(200).forEachIndexed { i, v -> index.add(i, v) }

        val base = index.encodeToByteArray(KeyCodec.int)
        assertEquals(0, index.dirtyNodes, "a snapshot checkpoints the index")

        // A batch of every kind of edit a delta has to carry.
        data.drop(200).forEachIndexed { i, v -> index.add(200 + i, v) }
        index.remove(7)
        index.add(11, data[0])                                   // replacement: tombstone + new node
        index.updateAttributes(3, mapOf("state" to "reviewed"))  // touches no graph state at all

        val delta = assertNotNull(index.encodeDelta(KeyCodec.int), "there were changes")
        val reloaded = decodeVectorIndex(base, listOf(delta), KeyCodec.int)

        assertSameRanking(index, reloaded, dim, 99)
        assertEquals(index.tombstones, reloaded.tombstones)

        // The metadata edit has to survive too, and it is the one change that touches no graph state,
        // so nothing else in this test would notice if it were dropped. Read it back through a filter,
        // which is the only public way in.
        val reviewed = reloaded.search(data[3], k = 50, filter = { it["state"] == "reviewed" })
        assertEquals(listOf(3), reviewed.map { it.key }, "only the entry whose attributes were edited")
    }

    @Test
    fun aChainOfDeltasReplaysInOrder() {
        val dim = 16
        val data = vectors(150, dim, 2)
        val index = VectorIndex<Int>(dim, Metric.Cosine)
        data.take(60).forEachIndexed { i, v -> index.add(i, v) }

        val base = index.encodeToByteArray(KeyCodec.int)
        val deltas = ArrayList<ByteArray>()
        for (batch in 0 until 5) {
            for (i in 0 until 18) {
                val id = 60 + batch * 18 + i
                if (id < data.size) index.add(id, data[id])
            }
            index.remove(batch)
            deltas.add(assertNotNull(index.encodeDelta(KeyCodec.int)))
        }

        assertSameRanking(index, decodeVectorIndex(base, deltas, KeyCodec.int), dim, 7)
    }

    @Test
    fun aDeltaIsFarSmallerThanTheSnapshotItFollows() {
        val dim = 64
        val data = vectors(1200, dim, 3)
        val index = VectorIndex<Int>(dim, Metric.Cosine)
        data.take(1000).forEachIndexed { i, v -> index.add(i, v) }

        val base = index.encodeToByteArray(KeyCodec.int)
        repeat(5) { index.add(1000 + it, data[1000 + it]) }
        val delta = assertNotNull(index.encodeDelta(KeyCodec.int))

        // The point of the whole exercise: five inserts must not cost a rewrite of a thousand
        // vectors. The bound is deliberately loose — what matters is the order of magnitude.
        assertTrue(
            delta.size * 10 < base.size,
            "a five-entry delta (${delta.size} B) should be far below a 1000-entry snapshot (${base.size} B)",
        )
    }

    @Test
    fun encodingWithNothingChangedYieldsNoDelta() {
        val index = VectorIndex<Int>(8, Metric.Cosine)
        vectors(20, 8, 4).forEachIndexed { i, v -> index.add(i, v) }
        index.encodeToByteArray(KeyCodec.int)
        assertNull(index.encodeDelta(KeyCodec.int), "nothing changed since the snapshot")
    }

    @Test
    fun deltasAreDeterministic() {
        val dim = 12
        val data = vectors(80, dim, 5)

        fun run(): ByteArray {
            val index = VectorIndex<Int>(dim, Metric.Cosine)
            data.take(60).forEachIndexed { i, v -> index.add(i, v) }
            index.encodeToByteArray(KeyCodec.int)
            data.drop(60).forEachIndexed { i, v -> index.add(60 + i, v) }
            index.remove(2)
            return index.encodeDelta(KeyCodec.int)!!
        }

        assertContentEquals(run(), run(), "the same edits must produce byte-identical deltas")
    }

    @Test
    fun aDeltaFromAnotherHistoryIsRefused() {
        val dim = 10
        val data = vectors(40, dim, 6)

        val a = VectorIndex<Int>(dim, Metric.Cosine)
        data.take(30).forEachIndexed { i, v -> a.add(i, v) }
        val baseA = a.encodeToByteArray(KeyCodec.int)
        a.add(100, data[30])
        val deltaA = a.encodeDelta(KeyCodec.int)!!

        // A different index that happens to be the same shape.
        val b = VectorIndex<Int>(dim, Metric.Cosine)
        data.take(29).forEachIndexed { i, v -> b.add(i, v) }
        val baseB = b.encodeToByteArray(KeyCodec.int)

        assertFailsWith<KromusFormatException> { decodeVectorIndex(baseB, listOf(deltaA), KeyCodec.int) }
        // And the right base still works, so the check is not simply rejecting everything.
        decodeVectorIndex(baseA, listOf(deltaA), KeyCodec.int)
    }

    @Test
    fun deltasReplayedOutOfOrderAreRefused() {
        val dim = 10
        val data = vectors(60, dim, 7)
        val index = VectorIndex<Int>(dim, Metric.Cosine)
        data.take(40).forEachIndexed { i, v -> index.add(i, v) }
        val base = index.encodeToByteArray(KeyCodec.int)

        index.add(41, data[41])
        val first = index.encodeDelta(KeyCodec.int)!!
        index.add(42, data[42])
        val second = index.encodeDelta(KeyCodec.int)!!

        assertFailsWith<KromusFormatException> { decodeVectorIndex(base, listOf(second, first), KeyCodec.int) }
        assertFailsWith<KromusFormatException> { decodeVectorIndex(base, listOf(second), KeyCodec.int) }
        decodeVectorIndex(base, listOf(first, second), KeyCodec.int)
    }

    @Test
    fun compactionForcesAFullSnapshot() {
        val dim = 10
        val data = vectors(40, dim, 8)
        val index = VectorIndex<Int>(dim, Metric.Cosine)
        data.forEachIndexed { i, v -> index.add(i, v) }
        index.encodeToByteArray(KeyCodec.int)

        repeat(20) { index.remove(it) }
        index.compact()

        assertTrue(index.needsFullSnapshot, "compaction renumbers every id")
        assertFailsWith<IllegalStateException> { index.encodeDelta(KeyCodec.int) }

        // A fresh snapshot restores the chain.
        val base = index.encodeToByteArray(KeyCodec.int)
        index.add(500, data[0])
        val delta = assertNotNull(index.encodeDelta(KeyCodec.int))
        assertSameRanking(index, decodeVectorIndex(base, listOf(delta), KeyCodec.int), dim, 3)
    }

    @Test
    fun aFreshIndexHasNoSnapshotToChainFrom() {
        val index = VectorIndex<Int>(8, Metric.Cosine)
        vectors(10, 8, 9).forEachIndexed { i, v -> index.add(i, v) }
        assertTrue(index.needsFullSnapshot)
        assertFailsWith<IllegalStateException> { index.encodeDelta(KeyCodec.int) }
    }

    @Test
    fun foldingADeltaChainBackIntoASnapshotMatchesAFullEncode() {
        val dim = 14
        val data = vectors(120, dim, 10)
        val index = VectorIndex<Int>(dim, Metric.Cosine)
        data.take(80).forEachIndexed { i, v -> index.add(i, v) }
        val base = index.encodeToByteArray(KeyCodec.int)

        data.drop(80).forEachIndexed { i, v -> index.add(80 + i, v) }
        index.remove(5)
        val delta = index.encodeDelta(KeyCodec.int)!!

        val folded = decodeVectorIndex(base, listOf(delta), KeyCodec.int).encodeToByteArray(KeyCodec.int)
        val direct = index.encodeToByteArray(KeyCodec.int)
        assertContentEquals(direct, folded, "replaying a delta must land on the same state a full encode would")
    }

    // --- text ---

    private val corpus = listOf(
        "kotlin coroutines structured concurrency",
        "sourdough starter troubleshooting guide",
        "hierarchical navigable small world graphs",
        "bm25 ranking and inverted indexes",
        "vector quantization tradeoffs in practice",
        "multiplatform builds on the jvm and native",
        "reciprocal rank fusion for hybrid search",
        "embedding models on device",
    )

    @Test
    fun textDeltaReplayReproducesTheLiveIndex() {
        val index = TextIndex<Int>()
        corpus.forEachIndexed { i, t -> index.add(i, t) }
        val base = index.encodeToByteArray(KeyCodec.int)
        assertEquals(0, index.dirtyDocuments)

        index.add(100, "late arrival about kotlin and graphs")
        index.remove(2)
        index.add(3, "replaced: quantization and ranking")
        index.updateAttributes(5, mapOf("lang" to "kotlin"))

        val delta = assertNotNull(index.encodeDelta(KeyCodec.int))
        val reloaded = decodeTextIndex(base, listOf(delta), KeyCodec.int)

        assertEquals(index.keys, reloaded.keys)
        for (q in listOf("kotlin", "graphs", "ranking", "quantization", "device")) {
            assertEquals(
                index.search(q, 8).map { it.key },
                reloaded.search(q, 8).map { it.key },
                "ranking for \"$q\"",
            )
        }
        assertEquals(
            listOf(5),
            reloaded.search("multiplatform", 8, filter = { it["lang"] == "kotlin" }).map { it.key },
        )
    }

    @Test
    fun anAttributeEditDoesNotDisturbTheDocumentOrder() {
        // Ordinals break ties between equally scored documents. An attribute edit must not renumber
        // the document it touches, or identical scores would come back in a different order.
        val index = TextIndex<Int>()
        repeat(6) { index.add(it, "identical text for every document") }
        val base = index.encodeToByteArray(KeyCodec.int)
        val tiedBefore = index.search("identical", 6).map { it.key }

        index.updateAttributes(0, mapOf("flag" to "y"))
        index.updateAttributes(4, mapOf("flag" to "y"))
        val delta = assertNotNull(index.encodeDelta(KeyCodec.int))
        val reloaded = decodeTextIndex(base, listOf(delta), KeyCodec.int)

        assertEquals(tiedBefore, index.search("identical", 6).map { it.key }, "live index order")
        assertEquals(tiedBefore, reloaded.search("identical", 6).map { it.key }, "replayed order")
    }

    @Test
    fun textDeltasFoldBackIntoTheSameSnapshot() {
        val index = TextIndex<Int>()
        corpus.forEachIndexed { i, t -> index.add(i, t) }
        val base = index.encodeToByteArray(KeyCodec.int)

        index.add(50, "another document about coroutines")
        index.remove(1)
        val delta = index.encodeDelta(KeyCodec.int)!!

        val folded = decodeTextIndex(base, listOf(delta), KeyCodec.int).encodeToByteArray(KeyCodec.int)
        assertContentEquals(index.encodeToByteArray(KeyCodec.int), folded)
    }

    @Test
    fun aTextDeltaFromAnotherHistoryIsRefused() {
        val a = TextIndex<Int>()
        corpus.forEachIndexed { i, t -> a.add(i, t) }
        val baseA = a.encodeToByteArray(KeyCodec.int)
        a.add(9, "new")
        val deltaA = a.encodeDelta(KeyCodec.int)!!

        val b = TextIndex<Int>()
        corpus.dropLast(1).forEachIndexed { i, t -> b.add(i, t) }
        val baseB = b.encodeToByteArray(KeyCodec.int)

        assertFailsWith<KromusFormatException> { decodeTextIndex(baseB, listOf(deltaA), KeyCodec.int) }
    }

    // --- hybrid ---

    @Test
    fun hybridDeltaReplayReproducesTheLiveIndex() {
        val dim = 20
        val data = vectors(40, dim, 11)
        val index = HybridIndex<Int>(dimensions = dim)
        corpus.forEachIndexed { i, t -> index.add(i, data[i], t) }
        val base = index.encodeToByteArray(KeyCodec.int)
        assertEquals(0, index.dirtyNodes)

        index.add(20, data[20], "a late document about vectors and ranking")
        index.remove(1)
        index.updateAttributes(4, mapOf("tier" to "gold"))

        val delta = assertNotNull(index.encodeDelta(KeyCodec.int))
        val reloaded = decodeHybridIndex(base, listOf(delta), KeyCodec.int)

        assertEquals(index.keys, reloaded.keys)
        assertEquals(index.size, reloaded.size)
        for (i in listOf(0, 3, 20)) {
            assertEquals(
                index.search(data[i], "ranking vectors", k = 6).map { it.key },
                reloaded.search(data[i], "ranking vectors", k = 6).map { it.key },
                "hybrid ranking from entry $i",
            )
        }
    }

    @Test
    fun hybridDeltasFoldBackIntoTheSameSnapshot() {
        val dim = 18
        val data = vectors(40, dim, 12)
        val index = HybridIndex<Int>(dimensions = dim)
        corpus.forEachIndexed { i, t -> index.add(i, data[i], t) }
        val base = index.encodeToByteArray(KeyCodec.int)

        index.add(30, data[30], "folded document")
        index.remove(0)
        val delta = index.encodeDelta(KeyCodec.int)!!

        val folded = decodeHybridIndex(base, listOf(delta), KeyCodec.int).encodeToByteArray(KeyCodec.int)
        assertContentEquals(index.encodeToByteArray(KeyCodec.int), folded)
    }

    @Test
    fun hybridCompactionForcesAFullSnapshot() {
        val dim = 12
        val data = vectors(20, dim, 13)
        val index = HybridIndex<Int>(dimensions = dim)
        corpus.forEachIndexed { i, t -> index.add(i, data[i], t) }
        index.encodeToByteArray(KeyCodec.int)
        index.remove(0)
        index.remove(1)
        index.compact()

        assertTrue(index.needsFullSnapshot)
        assertFailsWith<IllegalStateException> { index.encodeDelta(KeyCodec.int) }
    }
}
