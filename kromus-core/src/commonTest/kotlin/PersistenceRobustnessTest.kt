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
    fun rejectsACorruptedCountInsteadOfAllocatingForIt() {
        val bytes = vectorIndex().encodeToByteArray(KeyCodec.int)
        // The node count sits right after the header and the config block; blow it up to a number
        // no blob this size could hold.
        val offset = 6 + 4 + 1 + 4 + 4 + 4 + 8 + 1 + 4
        bytes[offset] = 0x7F
        bytes[offset + 1] = 0x00
        bytes[offset + 2] = 0x00
        bytes[offset + 3] = 0x00

        val e = assertFailsWith<KromusFormatException> { decodeVectorIndex(bytes, KeyCodec.int) }
        assertTrue(e.message!!.contains("count"), e.message!!)
    }

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
