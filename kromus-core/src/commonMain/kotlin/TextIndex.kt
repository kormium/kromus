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
    private class Doc(
        /** Insertion order of this document — the deterministic tie-break for equal BM25 scores. */
        val ordinal: Int,
        var termFreqs: Map<String, Int>,
        val length: Int,
        var attributes: Map<String, String>,
    )

    // LinkedHashMap so iteration follows insertion order: persistence writes documents in that order
    // and reloading reproduces the same ordinals, which keeps ranking reproducible across platforms.
    private val docs = LinkedHashMap<K, Doc>()

    // term -> (key -> term frequency in that document)
    private val postings = HashMap<String, HashMap<K, Int>>()
    private var totalLength = 0L
    private var nextOrdinal = 0

    /** Number of indexed documents. */
    public val size: Int get() = docs.size

    /** The indexed keys, in insertion order. Read-only; do not retain across edits. */
    public val keys: Set<K> get() = docs.keys

    public operator fun contains(key: K): Boolean = docs.containsKey(key)

    /**
     * Indexes [text] under [key], replacing any existing document for that key. Optional [attributes]
     * are stored with the document and can restrict later searches (see the `filter` of [search]).
     */
    public fun add(key: K, text: String, attributes: Map<String, String> = emptyMap()) {
        remove(key)
        val tokens = analyzer.analyze(text)
        val termFreqs = HashMap<String, Int>()
        for (t in tokens) termFreqs[t] = (termFreqs[t] ?: 0) + 1

        docs[key] = Doc(nextOrdinal++, termFreqs, tokens.size, attributes)
        totalLength += tokens.size
        for ((term, f) in termFreqs) {
            postings.getOrPut(term) { HashMap() }[key] = f
        }
    }

    /**
     * Replaces the [attributes][add] stored for [key], leaving the document and its postings alone.
     *
     * @return true if [key] was present.
     */
    public fun updateAttributes(key: K, attributes: Map<String, String>): Boolean {
        val doc = docs[key] ?: return false
        doc.attributes = attributes
        return true
    }

    /** Removes every document. */
    public fun clear() {
        docs.clear()
        postings.clear()
        totalLength = 0L
        nextOrdinal = 0
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
    public fun search(query: String, k: Int, filter: MetadataFilter? = null): List<SearchResult<K>> {
        require(k >= 1) { "k must be >= 1, was $k" }
        val n = docs.size
        if (n == 0) return emptyList()

        val queryTerms = analyzer.analyze(query)
        if (queryTerms.isEmpty()) return emptyList()

        val avgdl = if (totalLength == 0L) 1.0 else totalLength.toDouble() / n
        val k1 = config.k1.toDouble()
        val b = config.b.toDouble()

        // LinkedHashSet, not a HashSet: each document's score accumulates its terms' contributions
        // in query order, and floating-point addition is not associative — a hash-ordered loop would
        // produce last-bit differences between platforms, and with them different tie-breaks.
        val scores = HashMap<K, Double>()
        for (term in LinkedHashSet(queryTerms)) {
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
        if (scores.isEmpty()) return emptyList()

        // Bounded selection instead of sorting every scored document: one common term can put the
        // whole corpus in the map, and k is typically tiny next to it.
        val top = TopK<K>(if (k < scores.size) k else scores.size)
        for ((key, score) in scores) {
            val doc = docs[key]!!
            if (filter != null && !filter(doc.attributes)) continue
            top.offer(key, score, doc.ordinal)
        }
        return top.toSortedList()
    }

    // --- persistence support (accessed by Persistence.kt) ---

    /** One entry per indexed document, in insertion order, carrying everything needed to rebuild it. */
    internal fun snapshot(): List<TextEntry<K>> =
        docs.map { (key, doc) -> TextEntry(key, doc.termFreqs, doc.length, doc.attributes) }

    /** Reinserts a pre-tokenized document, rebuilding postings without re-running the analyzer. */
    internal fun loadDoc(key: K, termFreqs: Map<String, Int>, length: Int, attributes: Map<String, String>) {
        docs[key] = Doc(nextOrdinal++, termFreqs, length, attributes)
        totalLength += length
        for ((term, f) in termFreqs) {
            postings.getOrPut(term) { HashMap() }[key] = f
        }
    }
}

internal class TextEntry<K>(
    val key: K,
    val termFreqs: Map<String, Int>,
    val length: Int,
    val attributes: Map<String, String>,
)
