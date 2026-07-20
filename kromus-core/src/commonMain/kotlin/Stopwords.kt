package io.github.kromus

/** Ready-made stop-word sets for [Analyzer.standard]. */
public object Stopwords {
    /** A compact set of very common English function words. */
    public val english: Set<String> = setOf(
        "a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "from", "has", "have", "he",
        "in", "is", "it", "its", "of", "on", "or", "that", "the", "then", "there", "these", "they",
        "this", "to", "was", "were", "will", "with", "you", "your", "i", "we", "our", "not", "no",
    )
}
