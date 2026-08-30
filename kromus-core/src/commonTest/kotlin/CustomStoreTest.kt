package io.github.kromus

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A quantizer written from outside the library, to check that the seam is real.
 *
 * Four bits per component — the step between int8 and binary that kromus does not ship — packed two
 * to a byte with one scale per vector. It is deliberately written using only public API: if this
 * compiles and round-trips, so does anisotropic quantization, product quantization, or anything else
 * somebody wants to try without waiting for the library to add it.
 */
private class Int4Store(
    override val dimensions: Int,
    override val metric: Metric,
) : VectorStore {
    private val codes = ArrayList<ByteArray>()
    private val scales = ArrayList<Float>()
    private val packed = (dimensions + 1) / 2

    override val size: Int get() = codes.size

    override val strideBytes: Int get() = packed + 4

    override fun add(prepared: FloatArray): Int {
        var maxAbs = 0f
        for (x in prepared) if (abs(x) > maxAbs) maxAbs = abs(x)
        val scale = if (maxAbs == 0f) 1f else maxAbs / 7f
        val inv = 1f / scale
        val code = ByteArray(packed)
        for (i in 0 until dimensions) {
            val q = (prepared[i] * inv).roundToInt().coerceIn(-7, 7) + 8 // 0..15
            val at = i / 2
            code[at] = if (i % 2 == 0) q.toByte() else (code[at].toInt() or (q shl 4)).toByte()
        }
        codes.add(code)
        scales.add(scale)
        return codes.size - 1
    }

    private fun valueAt(code: ByteArray, i: Int, scale: Float): Float {
        val raw = code[i / 2].toInt() and 0xFF
        val nibble = if (i % 2 == 0) raw and 0x0F else (raw ushr 4) and 0x0F
        return (nibble - 8) * scale
    }

    override fun distanceToQuery(query: FloatArray, id: Int): Float =
        metricDistance(query, reconstruct(id), metric)

    override fun distanceBetween(a: Int, b: Int): Float =
        metricDistance(reconstruct(a), reconstruct(b), metric)

    override fun reconstruct(id: Int): FloatArray {
        val code = codes[id]
        val scale = scales[id]
        return FloatArray(dimensions) { valueAt(code, it, scale) }
    }

    override fun writeVector(id: Int, out: ByteWriter) {
        for (b in codes[id]) out.byte(b.toInt())
        out.float(scales[id])
    }

    override fun readVector(from: ByteReader) {
        codes.add(ByteArray(packed) { from.byte().toByte() })
        scales.add(from.float())
    }
}

class CustomStoreTest {

    private val dim = 32
    private val factory = VectorStoreFactory { d, m -> Int4Store(d, m) }

    private fun data(n: Int, seed: Int): List<FloatArray> {
        val rng = Random(seed)
        return List(n) { FloatArray(dim) { rng.nextFloat() * 2f - 1f } }
    }

    @Test
    fun anIndexCanBeBuiltOnAQuantizerTheLibraryDoesNotShip() {
        val v = data(400, 1)
        val index = FlatIndex.build(dim, v.mapIndexed { i, x -> IvfEntry(i, x) }, store = factory)
        assertEquals(400, index.size)

        // Four bits should still rank sensibly: the nearest neighbour of a corpus point is itself.
        for (i in listOf(0, 40, 399)) {
            assertEquals(i, index.search(v[i], 1).single().key)
        }
    }

    @Test
    fun aCustomStorePersistsThroughTheSameFormat() {
        val v = data(300, 2)
        val index = FlatIndex.build(dim, v.mapIndexed { i, x -> IvfEntry(i, x) }, store = factory)
        val blob = index.encodeToByteArray(KeyCodec.int)

        // Nothing in the bytes names the quantizer, so the same factory has to be supplied to read it.
        val reloaded = decodeFlatIndex(blob, KeyCodec.int, store = factory)
        val q = v[7]
        assertEquals(
            index.search(q, 10).map { it.key to it.score },
            reloaded.search(q, 10).map { it.key to it.score },
        )
        // And the format's own guarantees still hold over it.
        assertContentEquals(blob, reloaded.encodeToByteArray(KeyCodec.int))
    }

    @Test
    fun fourBitsLandBetweenTheBuiltInQuantizers() {
        // Not a claim about int4 in general — just that a custom store behaves like a point on the
        // same curve, which is what tells us the seam carries real information rather than noise.
        val v = data(600, 3)
        val entries = v.mapIndexed { i, x -> IvfEntry(i, x) }
        val exact = FlatIndex.build(dim, entries)
        val custom = FlatIndex.build(dim, entries, store = factory)
        val binary = FlatIndex.build(dim, entries, quantization = Quantization.Binary)

        val queries = data(30, 4)

        fun recall(index: FlatIndex<Int>): Double {
            var hits = 0
            queries.forEach { q ->
                val truth = exact.search(q, 10).map { it.key }.toSet()
                hits += index.search(q, 10).count { it.key in truth }
            }
            return hits / (10.0 * queries.size)
        }

        val four = recall(custom)
        val one = recall(binary)
        assertTrue(four > one, "four bits should beat one: $four vs $one")
        assertTrue(four > 0.7, "four bits should retain most neighbours, got $four")
    }

    @Test
    fun theSameQuantizerWorksUnderHnswAndIvf() {
        // The seam is not a flat-index feature: a graph and an inverted file hold a store too, and
        // neither knows what is inside it.
        val v = data(500, 5)

        val graph = VectorIndex<Int>(dim, store = factory)
        v.forEachIndexed { i, x -> graph.add(i, x) }
        assertEquals(500, graph.size)
        assertEquals(11, graph.search(v[11], 1).single().key)

        val ivf = IvfIndex.build(dim, v.mapIndexed { i, x -> IvfEntry(i, x) }, store = factory)
        assertEquals(500, ivf.size)
        assertEquals(11, ivf.search(v[11], 1).single().key)

        // And both survive the round trip on the same terms: supply the factory again when reading.
        val graphBlob = graph.encodeToByteArray(KeyCodec.int)
        val graphBack = decodeVectorIndex(graphBlob, KeyCodec.int, store = factory)
        assertEquals(
            graph.search(v[3], 10).map { it.key },
            graphBack.search(v[3], 10).map { it.key },
        )
        assertContentEquals(graphBlob, graphBack.encodeToByteArray(KeyCodec.int))

        val ivfBlob = ivf.encodeToByteArray(KeyCodec.int)
        val ivfBack = decodeIvfIndex(ivfBlob, KeyCodec.int, store = factory)
        assertEquals(
            ivf.search(v[3], 10).map { it.key },
            ivfBack.search(v[3], 10).map { it.key },
        )
        assertContentEquals(ivfBlob, ivfBack.encodeToByteArray(KeyCodec.int))
    }

    @Test
    fun compactingRebuildsOnTheSameQuantizer() {
        // compact() throws the graph away and builds a new one. If it reached for the built-in store
        // the vectors would silently change precision underneath the caller.
        val v = data(200, 6)
        val index = VectorIndex<Int>(dim, store = factory)
        v.forEachIndexed { i, x -> index.add(i, x) }
        repeat(100) { index.remove(it * 2) }

        val before = index.search(v[7], 5).map { it.key }
        assertEquals(100, index.compact())
        assertEquals(before, index.search(v[7], 5).map { it.key })
        assertEquals(100, index.size)
    }
}
