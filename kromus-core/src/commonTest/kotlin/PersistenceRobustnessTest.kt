package io.github.kromus

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PersistenceRobustnessTest {
    private fun vectorIndex(): VectorIndex<Int> {
        val index = VectorIndex<Int>(dimensions = 8)
        val rng = Random(19)
        repeat(30) { index.add(it, FloatArray(8) { rng.nextFloat() }, mapOf("g" to (it % 3).toString())) }
        return index
    }

    @Test
    fun rejectsBytesThatAreNotAnIndex() {
        val e = assertFailsWith<KromusFormatException> {
            decodeVectorIndex("this is not an index at all".encodeToByteArray(), KeyCodec.int)
        }
        assertTrue(e.message!!.contains("not a kromus index"), e.message!!)
    }

    @Test
    fun rejectsAnEmptyOrTinyBlob() {
        assertFailsWith<KromusFormatException> { decodeVectorIndex(ByteArray(0), KeyCodec.int) }
        assertFailsWith<KromusFormatException> { decodeTextIndex(byteArrayOf(1, 2, 3), KeyCodec.int) }
    }

    @Test
    fun rejectsAnIndexOfTheWrongKind() {
        val text = TextIndex<Int>().apply { add(1, "hello world") }
        val e = assertFailsWith<KromusFormatException> {
            decodeVectorIndex(text.encodeToByteArray(KeyCodec.int), KeyCodec.int)
        }
        assertTrue(e.message!!.contains("expected a kromus vector index"), e.message!!)
        assertTrue(e.message!!.contains("text"), e.message!!)
    }

    @Test
    fun rejectsAnOlderFormatVersionWithAnActionableMessage() {
        val bytes = vectorIndex().encodeToByteArray(KeyCodec.int)
        bytes[5] = (bytes[5] - 1).toByte() // the version byte follows the 4-byte magic and the kind

        val e = assertFailsWith<KromusFormatException> { decodeVectorIndex(bytes, KeyCodec.int) }
        assertTrue(e.message!!.contains("cannot be read by this build"), e.message!!)
        assertTrue(e.message!!.contains("rebuild"), e.message!!)
    }

    @Test
    fun rejectsTruncatedBytesInsteadOfCrashing() {
        val bytes = vectorIndex().encodeToByteArray(KeyCodec.int)
        for (cut in listOf(7, 20, 100, bytes.size / 2, bytes.size - 1)) {
            val e = assertFailsWith<KromusFormatException>("truncating to $cut bytes must be reported") {
                decodeVectorIndex(bytes.copyOf(cut), KeyCodec.int)
            }
            assertTrue(e.message!!.isNotEmpty())
        }
    }

    @Test
    fun aSingleFlippedByteIsCaughtByTheSectionChecksum() {
        // What sections buy that a flat stream could not: corruption is *located*. A flipped bit in a
        // vector used to change a result silently — every value in range is a plausible value, so
        // nothing about the bytes said they had changed.
        val bytes = vectorIndex().encodeToByteArray(KeyCodec.int)
        val target = bytes.size - 20
        bytes[target] = (bytes[target].toInt() xor 0x01).toByte()

        val e = assertFailsWith<KromusFormatException> { decodeVectorIndex(bytes, KeyCodec.int) }
        assertTrue(e.message!!.contains("checksum"), e.message!!)
    }

    @Test
    fun rejectsACraftedNodeCountInsteadOfAllocatingForIt() {
        // A checksum catches a damaged file. It cannot catch a crafted one: a producer with a bug
        // writes an absurd count and a checksum that matches it perfectly. The bounds guards are what
        // stand between that and an allocation nobody asked for, so they need testing directly.
        val e = assertFailsWith<KromusFormatException> {
            decodeVectorIndex(craftedVectorIndex(nodeCount = 0x7F000000), KeyCodec.int)
        }
        assertTrue(e.message!!.contains("section"), e.message!!)
    }

    @Test
    fun rejectsACraftedLevelInsteadOfAllocatingForIt() {
        // A level is a count in disguise: level + 1 adjacency lists follow it. Two bytes bound it to
        // 65 535, which is still far more than a small file can describe.
        val e = assertFailsWith<KromusFormatException> {
            decodeVectorIndex(craftedVectorIndex(firstLevel = 0xFFFF), KeyCodec.int)
        }
        assertTrue(e.message!!.contains("neighbour") || e.message!!.contains("truncated"), e.message!!)
    }

    /**
     * Rebuilds a real index's container with one declared quantity replaced — right magic, right
     * sections, checksums that match. This is the file a buggy writer produces, and the one no
     * checksum can ever catch.
     */
    private fun craftedVectorIndex(nodeCount: Int = -1, firstLevel: Int = -1): ByteArray {
        val real = vectorIndex().encodeToByteArray(KeyCodec.int)
        val reader = ContainerReader(real, KIND_VECTOR, vectorFormatVersion(real), expect = null)
        val c = ContainerWriter(KIND_VECTOR, vectorFormatVersion(real), provenance = null)
        for (tag in listOf("CNFG", "LVLS", "DELT", "ADJC", "VECT", "ENTR")) {
            val body = reader.sectionBytes(tag)
            c.section(tag) {
                when {
                    // The node count sits after dimensions, metric, m, efC, efS, seed, quantization
                    // and maxVisited: 4 + 1 + 4 + 4 + 4 + 8 + 1 + 4.
                    tag == "CNFG" && nodeCount >= 0 -> {
                        raw(body.copyOfRange(0, 30))
                        int(nodeCount)
                        raw(body.copyOfRange(34, body.size))
                    }
                    tag == "LVLS" && firstLevel >= 0 -> {
                        short(firstLevel)
                        raw(body.copyOfRange(2, body.size))
                    }
                    else -> raw(body)
                }
            }
        }
        return c.toByteArray()
    }

    /** The format version this build writes, read back off a blob it just produced. */
    private fun vectorFormatVersion(bytes: ByteArray): Int = bytes[5].toInt() and 0xFF

    @Test
    fun rejectsAGarbledInteriorWithoutAnIndexOutOfBounds() {
        val rng = Random(3)
        val original = vectorIndex().encodeToByteArray(KeyCodec.int)
        repeat(200) {
            val bytes = original.copyOf()
            bytes[rng.nextInt(6, bytes.size)] = rng.nextInt(256).toByte()
            try {
                decodeVectorIndex(bytes, KeyCodec.int)
            } catch (e: KromusFormatException) {
                // expected for most flips
            }
        }
    }

    @Test
    fun encodingIsByteStableAcrossRepeatedRuns() {
        val first = vectorIndex()
        val second = vectorIndex()
        assertContentEquals(
            first.encodeToByteArray(KeyCodec.int),
            second.encodeToByteArray(KeyCodec.int),
            "the same content must serialize to the same bytes, so an index can be content-hashed",
        )
        assertContentEquals(
            first.encodeToByteArray(KeyCodec.int),
            first.encodeToByteArray(KeyCodec.int),
            "encoding must not depend on hash iteration order",
        )
    }

    @Test
    fun textAndHybridEncodingIsByteStableToo() {
        fun buildText(): TextIndex<Int> = TextIndex<Int>().apply {
            repeat(40) { add(it, "kotlin coroutines document number $it", mapOf("g" to (it % 3).toString())) }
        }
        assertContentEquals(
            buildText().encodeToByteArray(KeyCodec.int),
            buildText().encodeToByteArray(KeyCodec.int),
        )

        fun buildHybrid(): HybridIndex<Int> = HybridIndex<Int>(dimensions = 8).apply {
            val rng = Random(21)
            repeat(30) { add(it, FloatArray(8) { rng.nextFloat() }, "document number $it") }
        }
        assertContentEquals(
            buildHybrid().encodeToByteArray(KeyCodec.int),
            buildHybrid().encodeToByteArray(KeyCodec.int),
        )
    }

    @Test
    fun repeatedStringsAreStoredOnce() {
        // Two corpora with identical record structure — same document count, same term count per
        // document, same term lengths — differing only in how much vocabulary they share. Pooling is
        // what makes the shared one far smaller.
        fun corpus(vocabularyPerDoc: Boolean): TextIndex<Int> = TextIndex<Int>().apply {
            repeat(500) { doc ->
                val terms = (0 until 5).joinToString(" ") { term ->
                    if (vocabularyPerDoc) "term${doc}x$term" else "sharedterm${term}xx"
                }
                add(doc, terms, mapOf("type" to "document"))
            }
        }

        val shared = corpus(vocabularyPerDoc = false).encodeToByteArray(KeyCodec.int).size
        val distinct = corpus(vocabularyPerDoc = true).encodeToByteArray(KeyCodec.int).size

        // The record structure itself is the floor both share, so the gap is the vocabulary: storing
        // it once instead of per document takes roughly half the file off a repetitive corpus.
        assertTrue(
            shared * 3 < distinct * 2,
            "a shared vocabulary should be stored once, not per document: $shared vs $distinct bytes",
        )
        assertEquals(
            500,
            decodeTextIndex(corpus(vocabularyPerDoc = false).encodeToByteArray(KeyCodec.int), KeyCodec.int).size,
        )
    }

    @Test
    fun maxVisitedSurvivesARoundTrip() {
        val index = VectorIndex<Int>(dimensions = 4, config = HnswConfig(maxVisited = 1234))
        index.add(1, floatArrayOf(1f, 0f, 0f, 0f))
        val restored = decodeVectorIndex(index.encodeToByteArray(KeyCodec.int), KeyCodec.int)
        assertEquals(1234, restored.config.maxVisited)
    }
}
