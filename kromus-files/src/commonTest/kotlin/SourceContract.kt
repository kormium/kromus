package io.github.kromus.files

import io.github.kromus.ByteArraySource
import io.github.kromus.ByteSource
import io.github.kromus.IvfConfig
import io.github.kromus.IvfEntry
import io.github.kromus.IvfIndex
import io.github.kromus.KeyCodec
import io.github.kromus.KromusFormatException
import io.github.kromus.decodeIvfIndex
import io.github.kromus.encodeToByteArray
import io.github.kromus.openIvfIndex
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * One contract, checked against every source this module ships.
 *
 * Four implementations of the same three methods, on four platforms, is exactly the shape where one
 * of them quietly differs — an off-by-one in a range, a short read accepted, a base offset applied
 * twice. Each of those returns neighbours that look reasonable, so none of them shows up as a
 * failure anywhere except here. The platform tests differ only in how they get a file; what is
 * asserted about it is this.
 */
public object SourceContract {

    public const val DIMENSIONS: Int = 24

    /** The corpus the fixture is built from, so a test can query with a vector that is in the index. */
    public fun corpus(n: Int = 600, seed: Int = 4): List<FloatArray> {
        val rng = Random(seed)
        val centres = List(8) { FloatArray(DIMENSIONS) { rng.nextFloat() * 2f - 1f } }
        return List(n) {
            val c = centres[it % centres.size]
            FloatArray(DIMENSIONS) { d -> c[d] + (rng.nextFloat() * 2f - 1f) * 0.3f }
        }
    }

    /** A clustered index encoded to bytes — what a platform test writes to a file. */
    public fun blob(n: Int = 600, seed: Int = 4): ByteArray =
        IvfIndex.build(
            DIMENSIONS,
            corpus(n, seed).mapIndexed { i, v -> IvfEntry(i, v, mapOf("mod" to "${i % 3}")) },
            config = IvfConfig(clusters = 24, nprobe = 3),
        ).encodeToByteArray(KeyCodec.int)

    /**
     * Holds [source] to answering exactly as the same bytes do in memory.
     *
     * The reference is the resident index rather than a recorded expectation: what matters is not
     * which neighbours come back but that reading from a file changes none of them.
     */
    public fun readsLikeMemory(source: ByteSource, blob: ByteArray, queries: List<FloatArray>) {
        assertEquals(blob.size, source.size, "the source should span the whole file")

        val resident = decodeIvfIndex(blob, KeyCodec.int)
        val opened = openIvfIndex(source, KeyCodec.int)

        assertEquals(resident.size, opened.size)
        assertContentEquals(resident.keys.toList(), opened.keys.toList())
        assertEquals(resident.nprobe, opened.nprobe)
        assertEquals(mapOf("mod" to "1"), opened.attributesOf(1))

        for ((i, q) in queries.withIndex()) {
            val expected = resident.search(q, 10)
            val actual = opened.search(q, 10)
            assertContentEquals(
                expected.map { it.key },
                actual.map { it.key },
                "query $i ranked differently when read from this source",
            )
            for (j in expected.indices) {
                assertTrue(
                    abs(expected[j].score - actual[j].score) <= 1e-5f,
                    "query $i, position $j: ${expected[j].score} in memory, ${actual[j].score} here",
                )
            }
        }
    }

    /** Holds [source] to reading the same bytes an array does, at every offset a container uses. */
    public fun readsTheSameBytes(source: ByteSource, blob: ByteArray) {
        val reference = ByteArraySource(blob)
        val a = ByteArray(64)
        val b = ByteArray(64)

        // The head, the tail, and a scattering in between — a base offset applied twice shows up at
        // every offset but zero, which is the one a careless test would check.
        val offsets = listOf(0, 1, 7, blob.size / 3, blob.size / 2, blob.size - 64)
        for (offset in offsets) {
            source.read(offset, 64, a)
            reference.read(offset, 64, b)
            assertContentEquals(b, a, "bytes differ at offset $offset")
        }

        // Reading into the middle of a buffer: a source that ignores `at` overwrites from the front,
        // which nothing downstream would notice until a cluster came back wrong.
        val into = ByteArray(80) { -1 }
        source.read(16, 32, into, at = 24)
        assertTrue(into.take(24).all { it == (-1).toByte() }, "a read wrote before its destination offset")
        assertTrue(into.drop(56).all { it == (-1).toByte() }, "a read wrote past its length")
        for (i in 0 until 32) {
            assertEquals(blob[16 + i], into[24 + i], "byte $i landed wrong when read at an offset")
        }

        // A zero-length read is legal and must do nothing rather than fail.
        source.read(0, 0, a)
    }

    /** Holds [source] to refusing what lies outside it rather than reading whatever is nearby. */
    public fun refusesRangesOutsideItself(source: ByteSource) {
        val into = ByteArray(16)
        assertFailsWith<KromusFormatException>("a negative offset should be refused") {
            source.read(-1, 4, into)
        }
        assertFailsWith<KromusFormatException>("a negative length should be refused") {
            source.read(0, -1, into)
        }
        assertFailsWith<KromusFormatException>("a range past the end should be refused") {
            source.read(source.size - 4, 8, into)
        }
        assertFailsWith<KromusFormatException>("an offset past the end should be refused") {
            source.read(source.size + 1, 4, into)
        }
    }

    /** Holds [source] to refusing reads once closed, and to tolerating a second close. */
    public fun refusesReadsAfterClosing(source: ByteSource) {
        source.close()
        source.close() // idempotent, per the interface
        assertFailsWith<KromusFormatException>("a closed source should refuse to read") {
            source.read(0, 4, ByteArray(4))
        }
    }
}
