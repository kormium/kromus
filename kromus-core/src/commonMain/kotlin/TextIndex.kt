package io.github.kromus

import kotlin.math.ln

/**
 * An in-memory, embeddable full-text index mapping caller keys of type [K] to documents, ranked with
 * [Okapi BM25](https://en.wikipedia.org/wiki/Okapi_BM25).
 *
 * Like [VectorIndex] it is pure-Kotlin, zero-dependency and behaves identically on every KMP target.
 * Unlike the vector layer, removals are exact — a removed document's postings are physically dropped,
 * so the index never accumulates tombstones.
 *
 * ```
 * val index = TextIndex<String>()
 * index.add("doc-1", "Structured concurrency in Kotlin coroutines")
 * val hits = index.search("kotlin coroutines", k = 10) // BM25-ranked, best first
 * ```
 *
 * Keys are unique: re-[add]ing replaces the document. Not thread-safe.
 *
 * @property analyzer tokenizer used for both indexing and querying; see [Analyzer].
 * @property config BM25 tuning; see [Bm25Config].
 */
public class TextIndex<K>(
    public val analyzer: Analyzer = Analyzer.standard(),
    public val config: Bm25Config = Bm25Config(),
) {
    private class Doc(val termFreqs: Map<String, Int>, val length: Int)

    private val docs = HashMap<K, Doc>()
    // term -> (key -> term frequency in that document)
    private val postings = HashMap<String, HashMap<K, Int>>()
    private var totalLength = 0L

    /** Number of indexed documents. */
    public val size: Int get() = docs.size

    public operator fun contains(key: K): Boolean = docs.containsKey(key)

    /** Indexes [text] under [key], replacing any existing document for that key. */
    public fun add(key: K, text: String) {
        remove(key)
        val tokens = analyzer.analyze(text)
        val termFreqs = HashMap<String, Int>()
        for (t in tokens) termFreqs[t] = (termFreqs[t] ?: 0) + 1

        docs[key] = Doc(termFreqs, tokens.size)
        totalLength += tokens.size
        for ((term, f) in termFreqs) {
            postings.getOrPut(term) { HashMap() }[key] = f
        }
    }

    /** Removes [key] and drops its postings entirely. @return true if [key] was present. */
    public fun remove(key: K): Boolean {
        val doc = docs.remove(key) ?: return false
        totalLength -= doc.length
        for (term in doc.termFreqs.keys) {
            val p = postings[term] ?: continue
            p.remove(key)
            if (p.isEmpty()) postings.remove(term)
        }
        return true
    }

    /**
     * Returns up to [k] documents ranked by BM25 relevance to [query], best first. Empty if the query
     * has no indexable terms or nothing matches. [SearchResult.score] is the BM25 score (higher =
     * more relevant); its scale is corpus-dependent and not comparable across indexes.
     */
    public fun search(query: String, k: Int): List<SearchResult<K>> {
        require(k >= 1) { "k must be >= 1, was $k" }
        val n = docs.size
        if (n == 0) return emptyList()

        val queryTerms = analyzer.analyze(query)
        if (queryTerms.isEmpty()) return emptyList()

        val avgdl = if (totalLength == 0L) 1.0 else totalLength.toDouble() / n
        val k1 = config.k1.toDouble()
        val b = config.b.toDouble()

        val scores = HashMap<K, Double>()
        for (term in queryTerms.toHashSet()) {
            val p = postings[term] ?: continue
            val df = p.size
            val idf = ln(1.0 + (n - df + 0.5) / (df + 0.5))
            for ((key, f) in p) {
                val dl = docs[key]!!.length
                val tf = f.toDouble()
                val norm = tf * (k1 + 1.0) / (tf + k1 * (1.0 - b + b * dl / avgdl))
                scores[key] = (scores[key] ?: 0.0) + idf * norm
            }
        }

        return scores.entries
            .sortedByDescending { it.value }
            .take(k)
            .map { SearchResult(it.key, it.value.toFloat()) }
    }
}
