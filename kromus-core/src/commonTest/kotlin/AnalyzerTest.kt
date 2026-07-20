package io.github.kromus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyzerTest {

    @Test
    fun englishLightStemmer() {
        val s = Stemmer.englishLight()
        assertEquals("cat", s.stem("cats"))
        assertEquals("box", s.stem("boxes"))
        assertEquals("study", s.stem("studies"))
        assertEquals("walk", s.stem("walked"))
        assertEquals("quick", s.stem("quickly"))
        assertEquals("run", s.stem("run")) // short words untouched
    }

    @Test
    fun stemmingUnifiesVariantsInSearch() {
        val index = TextIndex<Int>(analyzer = Analyzer.standard(stemmer = Stemmer.englishLight()))
        index.add(1, "the cats chased boxes")
        index.add(2, "unrelated content here")

        assertEquals(1, index.search("cat", 5).first().key, "query 'cat' should match doc with 'cats'")
        assertEquals(1, index.search("box", 5).first().key, "query 'box' should match doc with 'boxes'")
    }

    @Test
    fun englishStopwordsAreRemoved() {
        val analyzer = Analyzer.standard(stopwords = Stopwords.english)
        assertEquals(listOf("cat", "dog"), analyzer.analyze("the cat and the dog"))
    }

    @Test
    fun ngramTokenization() {
        assertEquals(listOf("hel", "ell", "llo"), Analyzer.ngram(3).analyze("hello"))
        assertEquals(listOf("ab"), Analyzer.ngram(3).analyze("ab"), "runs shorter than n are emitted whole")
        assertEquals(listOf("本語"), Analyzer.ngram(2).analyze("本語"))
    }

    @Test
    fun ngramEnablesBoundaryFreeSearch() {
        // No whitespace between characters (CJK-style); character bigrams still match.
        val index = TextIndex<Int>(analyzer = Analyzer.ngram(2))
        index.add(1, "日本語")
        index.add(2, "中国語")
        val hits = index.search("本語", 5).map { it.key }
        assertTrue(1 in hits && 2 !in hits, "bigram 本語 should match only doc 1")
    }

    @Test
    fun ngramAnalyzerSurvivesPersistenceWhenReloadedWithSameAnalyzer() {
        val index = TextIndex<Int>(analyzer = Analyzer.ngram(2))
        index.add(1, "日本語")
        val restored = decodeTextIndex(index.encodeToByteArray(KeyCodec.int), KeyCodec.int, Analyzer.ngram(2))
        assertEquals(listOf(1), restored.search("日本", 5).map { it.key })
    }
}
