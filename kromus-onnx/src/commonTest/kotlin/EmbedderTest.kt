package io.github.kromus.onnx

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** A deterministic OnnxSession — token i's vector is all `(i+1)` — so the pipeline is testable without a real model. */
private class FakeOnnxSession(private val hidden: Int) : OnnxSession {
    override suspend fun run(inputIds: IntArray, attentionMask: IntArray, tokenTypeIds: IntArray): OnnxOutput {
        val s = inputIds.size
        val flat = FloatArray(s * hidden)
        for (i in 0 until s) for (j in 0 until hidden) flat[i * hidden + j] = (i + 1).toFloat()
        return OnnxOutput(flat, s, hidden)
    }
}

class EmbedderTest {

    private val vocab: Map<String, Int> = mapOf(
        "[PAD]" to 0, "[UNK]" to 1, "[CLS]" to 2, "[SEP]" to 3,
        "kotlin" to 4, "search" to 5, "engine" to 6, "##s" to 7,
        "," to 8, "." to 9, "un" to 10, "##aff" to 11, "##able" to 12,
        "hello" to 13, "world" to 14,
    )
    private val tok = WordPieceTokenizer(vocab)

    @Test
    fun tokenizesWordsAndSpecials() {
        assertContentEquals(intArrayOf(2, 4, 5, 3), tok.tokenize("Kotlin search", 256).inputIds)
    }

    @Test
    fun splitsIntoWordPieces() {
        assertContentEquals(intArrayOf(2, 4, 7, 3), tok.tokenize("kotlins", 256).inputIds)
        assertContentEquals(intArrayOf(2, 10, 11, 12, 3), tok.tokenize("unaffable", 256).inputIds)
    }

    @Test
    fun splitsPunctuationAndFallsBackToUnknown() {
        assertContentEquals(intArrayOf(2, 13, 8, 14, 9, 3), tok.tokenize("hello, world.", 256).inputIds)
        assertContentEquals(intArrayOf(2, 1, 3), tok.tokenize("qwxyz", 256).inputIds) // no matchable piece -> [UNK]
    }

    @Test
    fun truncatesToMaxTokens() {
        // three pieces, but maxTokens = 4 leaves room for only 2 between [CLS]/[SEP]
        assertContentEquals(intArrayOf(2, 4, 5, 3), tok.tokenize("kotlin search engine", 4).inputIds)
    }

    @Test
    fun maskAndTypesMatchLength() {
        val t = tok.tokenize("kotlin search", 256)
        assertContentEquals(intArrayOf(1, 1, 1, 1), t.attentionMask)
        assertContentEquals(intArrayOf(0, 0, 0, 0), t.tokenTypeIds)
    }

    @Test
    fun meanPoolingAveragesTokenVectors() = runTest {
        val embedder = OnnxTextEmbedder(
            FakeOnnxSession(hidden = 8), tok, dimensions = 8,
            EmbedderConfig(pooling = Pooling.Mean, normalize = false),
        )
        // "kotlin search" -> 4 tokens; mean of (1,2,3,4) = 2.5 in every dimension
        val v = embedder.embed("kotlin search")
        assertEquals(8, v.size)
        for (x in v) assertTrue(abs(x - 2.5f) < 1e-5f, "expected 2.5, was $x")
    }

    @Test
    fun clsPoolingTakesFirstToken() = runTest {
        val embedder = OnnxTextEmbedder(
            FakeOnnxSession(hidden = 8), tok, dimensions = 8,
            EmbedderConfig(pooling = Pooling.Cls, normalize = false),
        )
        val v = embedder.embed("kotlin search")
        for (x in v) assertTrue(abs(x - 1f) < 1e-5f, "expected 1 ([CLS] vector), was $x")
    }

    @Test
    fun normalizationYieldsUnitVectors() = runTest {
        val embedder = OnnxTextEmbedder(FakeOnnxSession(hidden = 8), tok, dimensions = 8, EmbedderConfig())
        val v = embedder.embed("kotlin search")
        var norm = 0f
        for (x in v) norm += x * x
        assertTrue(abs(sqrt(norm) - 1f) < 1e-5f, "expected unit norm, was ${sqrt(norm)}")
    }

    @Test
    fun embedAllMapsEach() = runTest {
        val embedder = OnnxTextEmbedder(FakeOnnxSession(hidden = 4), tok, dimensions = 4)
        val out = embedder.embedAll(listOf("hello world", "kotlin search"))
        assertEquals(2, out.size)
        assertEquals(4, out[0].size)
    }

    @Test
    fun vocabTextLoaderAssignsLineNumbers() {
        val t = WordPieceTokenizer.fromVocabText("[PAD]\n[UNK]\n[CLS]\n[SEP]\nkotlin\n")
        assertContentEquals(intArrayOf(2, 4, 3), t.tokenize("kotlin", 256).inputIds)
    }
}
