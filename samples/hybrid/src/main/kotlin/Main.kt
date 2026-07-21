package io.github.kromus.samples.hybrid

import io.github.kromus.HybridIndex
import io.github.kromus.samples.common.ToyEmbedder

/**
 * Why you want *both* kinds of search — and why fusing them wins.
 *
 * - Vector search understands **meaning** (great for questions), but can't match an exact code like
 *   `E-4021` — a code has no "meaning" to embed.
 * - Keyword search (BM25) nails **exact tokens** (codes, names, IDs), but misses paraphrases.
 * - Hybrid runs both and merges the rankings, so you get each one's strength.
 *
 * Run: `./gradlew :samples:hybrid:run`
 */
fun main() {
    val embedder = ToyEmbedder()
    val index = HybridIndex<String>(dimensions = embedder.dimensions)

    // (key, vector-from-meaning, the searchable text)
    add(index, embedder, "Kotlin coroutines guide", "A guide to Kotlin coroutines and asynchronous programming")
    add(index, embedder, "Error E-4021 on startup", "How to fix error E-4021 that appears on startup")
    add(index, embedder, "Sourdough bread basics", "Baking sourdough bread at home")

    val byMeaning = "how do I write async code?" // a question — no doc contains these words
    val exactCode = "E-4021"                     // an exact code — embeddings can't match it

    println("Ask a question (vector search understands meaning):")
    println("  \"$byMeaning\"")
    println("   → ${index.searchVector(embedder.embed(byMeaning), k = 1).map { it.key }}")

    println("\nSearch an exact code (keyword search / BM25):")
    println("  \"$exactCode\"")
    println("   → ${index.searchText(exactCode, k = 1).map { it.key }}   (vector search alone can't find this)")

    println("\nHybrid — the question AND the code together:")
    println("   → ${index.search(embedder.embed(byMeaning), exactCode, k = 2).map { it.key }}")
}

private fun add(index: HybridIndex<String>, embedder: ToyEmbedder, key: String, text: String) {
    index.add(key, embedder.embed(text), text)
}
