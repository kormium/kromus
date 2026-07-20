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
         * any in [stopwords], and optionally applies a [stemmer]. Good for most Latin-script corpora.
         */
        public fun standard(
            stopwords: Set<String> = emptySet(),
            minTokenLength: Int = 1,
            stemmer: Stemmer? = null,
        ): Analyzer = StandardAnalyzer(stopwords, minTokenLength, stemmer)

        /**
         * A character n-gram analyzer: lowercases and emits every [n]-character window within each
         * whitespace-separated run (runs shorter than [n] are emitted whole). Useful for languages
         * without word boundaries (CJK) and for substring/fuzzy matching. Use the same [n] for
         * indexing and querying.
         */
        public fun ngram(n: Int): Analyzer = NgramAnalyzer(n)
    }
}

internal class StandardAnalyzer(
    private val stopwords: Set<String>,
    private val minTokenLength: Int,
    private val stemmer: Stemmer?,
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
        if (term.length < minTokenLength || term in stopwords) return
        out.add(stemmer?.stem(term) ?: term)
    }
}

internal class NgramAnalyzer(private val n: Int) : Analyzer {
    init {
        require(n >= 1) { "n must be >= 1, was $n" }
    }

    override fun analyze(text: String): List<String> {
        val out = ArrayList<String>()
        val run = StringBuilder()
        fun flush() {
            if (run.isEmpty()) return
            if (run.length <= n) {
                out.add(run.toString())
            } else {
                for (i in 0..run.length - n) out.add(run.substring(i, i + n))
            }
            run.setLength(0)
        }
        for (ch in text) {
            if (ch.isWhitespace()) flush() else run.append(ch.lowercaseChar())
        }
        flush()
        return out
    }
}
