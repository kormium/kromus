package io.github.kromus

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A source that records what was asked of it.
 *
 * It reads from an array, so it is not a file — what it measures is the *access pattern*, which is
 * the part that decides whether a file-backed index is viable. If opening an index reads the whole
 * source, no file implementation can save anything.
 */
private class CountingSource(private val bytes: ByteArray) : ByteSource {
    var bytesRead: Int = 0
        private set
    var reads: Int = 0
        private set
    var largestRead: Int = 0
        private set
    var closed: Boolean = false
        private set

    override val size: Int get() = bytes.size

    override fun read(offset: Int, length: Int, into: ByteArray, at: Int) {
        checkRange(offset, length, size)
        bytes.copyInto(into, at, offset, offset + length)
        bytesRead += length
        reads += 1
        if (length > largestRead) largestRead = length
    }

    override fun close() {
        closed = true
    }
}

/** A source that hands back less than it was asked for — the failure the interface warns about. */
private class ShortSource(private val bytes: ByteArray, private val shortBy: Int) : ByteSource {
    override val size: Int get() = bytes.size

    override fun read(offset: Int, length: Int, into: ByteArray, at: Int) {
        val give = if (length > shortBy) length - shortBy else length
        bytes.copyInto(into, at, offset, offset + give)
    }
}

class ByteSourceTest {

    private val dim = 24

    private fun corpus(n: Int, seed: Int): List<FloatArray> {
        val rng = Random(seed)
        val centres = List(8) { FloatArray(dim) { rng.nextFloat() * 2f - 1f } }
        return List(n) {
            val c = centres[it % centres.size]
            FloatArray(dim) { d -> c[d] + (rng.nextFloat() * 2f - 1f) * 0.3f }
        }
    }

    private fun index(n: Int = 800, seed: Int = 1): IvfIndex<Int> =
        IvfIndex.build(
            dim,
            corpus(n, seed).mapIndexed { i, v -> IvfEntry(i, v) },
            config = IvfConfig(clusters = 32, nprobe = 2),
        )

    @Test
    fun openingAnIndexReadsTheHeaderAndTheSmallSectionsOnly() {
        val built = index()
        val blob = built.encodeToByteArray(KeyCodec.int)
        val source = CountingSource(blob)

        val opened = openIvfIndex(source, KeyCodec.int)
        assertEquals(built.size, opened.size)

        // The vector section is the bulk, and opening must not touch it. Comparing against the size of
        // that section rather than against a number chosen to pass keeps the assertion meaningful as
        // the corpus or the layout changes: opening has to cost less than the vectors alone.
        val vectors = built.storedVectors * built.strideBytes
        assertTrue(
            source.bytesRead < vectors,
            "opening read ${source.bytesRead} of ${blob.size} byte(s), which is more than the " +
                "$vectors-byte vector section it was supposed to leave alone",
        )
        assertTrue(
            source.largestRead < vectors,
            "one read took ${source.largestRead} byte(s) — the whole vector section in a single call",
        )
    }

    @Test
    fun aQueryReadsClusterSizedRunsRatherThanTheWholeIndex() {
        val data = corpus(800, 1)
        val built = index()
        val blob = built.encodeToByteArray(KeyCodec.int)
        val source = CountingSource(blob)
        val opened = openIvfIndex(source, KeyCodec.int)

        val afterOpen = source.bytesRead
        val searcher = opened.searcher()
        repeat(10) { searcher.search(data[it * 7], 10) }
        val perQuery = (source.bytesRead - afterOpen) / 10

        // Two clusters of an even split would be a sixteenth of the corpus; k-means splits unevenly,
        // so the bound is loose on purpose. What it rules out is a scan of everything.
        val vectors = built.storedVectors * built.strideBytes
        assertTrue(
            perQuery < vectors / 4,
            "a query read $perQuery byte(s) of a $vectors-byte vector region at nprobe=2",
        )
        assertTrue(source.largestRead <= vectors, "no read should exceed the vector region")

        // The point of the whole arrangement, in one number: opening and answering ten queries costs
        // less than reading the file would have.
        assertTrue(
            source.bytesRead < blob.size,
            "${source.bytesRead} byte(s) read over ten queries against a ${blob.size}-byte file",
        )
    }

    @Test
    fun aStreamedIndexAnswersAsTheResidentOneDoes() {
        val data = corpus(800, 1)
        val built = index()
        val blob = built.encodeToByteArray(KeyCodec.int)
        val streamed = openIvfIndex(CountingSource(blob), KeyCodec.int)
        val resident = decodeIvfIndex(blob, KeyCodec.int)

        repeat(10) {
            val q = data[it * 11]
            assertSameResults(resident.search(q, 10), streamed.search(q, 10), "query $it")
        }
    }

    @Test
    fun aSourceThatUnderfillsItsBufferIsCaughtByTheChecksum() {
        // The interface says a read must fill exactly what was asked for, because nothing downstream
        // can tell vector data from whatever was in the buffer before. For every section that carries
        // one, the checksum is the backstop — and this is what it catches.
        val blob = index().encodeToByteArray(KeyCodec.int)
        val failure = assertFailsWith<KromusFormatException> {
            openIvfIndex(ShortSource(blob, shortBy = 1), KeyCodec.int)
        }
        assertTrue(
            "checksum" in failure.message!! || "truncated" in failure.message!!,
            "expected a checksum or truncation complaint, got: ${failure.message}",
        )
    }

    @Test
    fun aFlatIndexOpensFromASourceToo() {
        val data = corpus(400, 3)
        val built = FlatIndex.build(dim, data.mapIndexed { i, v -> IvfEntry(i, v) })
        val blob = built.encodeToByteArray(KeyCodec.int)
        val opened = openFlatIndex(CountingSource(blob), KeyCodec.int)

        assertEquals(built.size, opened.size)
        assertSameResults(built.search(data[5], 10), opened.search(data[5], 10), "flat")
    }

    @Test
    fun aSliceAddressesFromZeroAndRefusesWhatLiesOutsideIt() {
        val bytes = ByteArray(100) { it.toByte() }
        val slice = ByteArraySource(bytes).slice(40, 20)

        assertEquals(20, slice.size)
        val into = ByteArray(4)
        slice.read(0, 4, into)
        assertEquals(40, into[0].toInt())
        slice.read(16, 4, into)
        assertEquals(56, into[0].toInt())

        assertFailsWith<KromusFormatException> { slice.read(18, 4, into) }
        assertFailsWith<KromusFormatException> { slice.read(-1, 4, into) }
    }

    @Test
    fun aWindowedArraySourceReadsOnlyItsOwnBytes() {
        val bytes = ByteArray(50) { it.toByte() }
        val source = ByteArraySource(bytes, base = 10, size = 20)
        val into = ByteArray(20)
        source.read(0, 20, into)
        assertEquals(10, into[0].toInt())
        assertEquals(29, into[19].toInt())
        assertFailsWith<KromusFormatException> { source.read(0, 21, into) }
    }

    @Test
    fun anIndexKeepsAnsweringUntilItsSourceIsClosed() {
        // close() is the caller's to make, not the index's: an index does not know whether the source
        // is shared. What is asserted here is only that nothing closes it behind the caller's back.
        val data = corpus(400, 5)
        val blob = index(400, 5).encodeToByteArray(KeyCodec.int)
        val source = CountingSource(blob)
        val opened = openIvfIndex(source, KeyCodec.int)

        repeat(3) { opened.search(data[it], 5) }
        assertTrue(!source.closed, "the index must not close a source it does not own")
        source.close()
        assertTrue(source.closed)
    }

    @Test
    fun theHeaderIsFoundEvenWhenProvenanceOutgrowsTheProbe() {
        // The header's length cannot be computed before part of it is read, so the reader probes and
        // grows. A provenance longer than the probe is the case that exercises the growth.
        val long = "model-" + "x".repeat(2000)
        val blob = index(200, 7).encodeToByteArray(KeyCodec.int, provenance = long)
        val opened = openIvfIndex(CountingSource(blob), KeyCodec.int, expect = long)
        assertEquals(200, opened.size)

        val wrong = assertFailsWith<KromusFormatException> {
            openIvfIndex(ByteArraySource(blob), KeyCodec.int, expect = "something else")
        }
        assertTrue("provenance" in wrong.message!!)
    }

    @Test
    fun binaryQuantizationIsRefusedRatherThanScannedFromBytes() {
        val blob = IvfIndex.build(
            dim,
            corpus(300, 9).mapIndexed { i, v -> IvfEntry(i, v) },
            config = IvfConfig(clusters = 8, quantization = Quantization.Binary),
        ).encodeToByteArray(KeyCodec.int)

        val failure = assertFailsWith<KromusFormatException> {
            openIvfIndex(ByteArraySource(blob), KeyCodec.int)
        }
        assertTrue("Binary" in failure.message!!)
    }

    @Test
    fun everySectionIsReadFromTheSourceWhereAnArrayWouldBeWindowed() {
        // Two paths through ContainerReader — a window onto an array, and a read from a source — must
        // produce the same index. A divergence here is the kind that returns plausible neighbours.
        val data = corpus(500, 11)
        val entries = data.mapIndexed { i, v -> IvfEntry(i, v, mapOf("mod" to "${i % 4}")) }
        val built = IvfIndex.build(dim, entries, config = IvfConfig(clusters = 16))
        val blob = built.encodeToByteArray(KeyCodec.int)

        val fromArray = decodeIvfIndex(blob, KeyCodec.int)
        val fromSource = openIvfIndex(CountingSource(blob), KeyCodec.int)

        assertEquals(fromArray.keys.toList(), fromSource.keys.toList())
        assertEquals(fromArray.nprobe, fromSource.nprobe)
        assertEquals(mapOf("mod" to "2"), fromSource.attributesOf(2))
        assertTrue(abs(fromArray.estimatedRecall - fromSource.estimatedRecall) <= EPSILON)
    }
}
