package io.github.kromus

/**
 * Turns raw text into the sequence of terms that get indexed and queried. Supply your own to plug in
 * stemming, custom tokenization, CJK n-grams, synonyms, etc. — the same analyzer must be used for
 * indexing and querying a given [TextIndex].
 */
public fun interface Analyzer {
    public fun analyze(text: String): List<String>

    public companion object {
        /**
         * A dependency-free default analyzer: lowercases, splits on any non-letter/digit boundary
         * (Unicode-aware via [Char.isLetterOrDigit]), drops tokens shorter than [minTokenLength] and
         * any in [stopwords]. Good enough for most Latin-script corpora; swap in a richer [Analyzer]
         * for stemming or language-specific handling.
         */
        public fun standard(
            stopwords: Set<String> = emptySet(),
            minTokenLength: Int = 1,
        ): Analyzer = StandardAnalyzer(stopwords, minTokenLength)
    }
}

internal class StandardAnalyzer(
    private val stopwords: Set<String>,
    private val minTokenLength: Int,
) : Analyzer {
    override fun analyze(text: String): List<String> {
        val out = ArrayList<String>()
        val token = StringBuilder()
        for (ch in text) {
            if (ch.isLetterOrDigit()) {
                token.append(ch.lowercaseChar())
            } else if (token.isNotEmpty()) {
                emit(token, out)
                token.setLength(0)
            }
        }
        if (token.isNotEmpty()) emit(token, out)
        return out
    }

    private fun emit(token: StringBuilder, out: MutableList<String>) {
        val term = token.toString()
        if (term.length >= minTokenLength && term !in stopwords) out.add(term)
    }
}
