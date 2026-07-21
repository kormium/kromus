package io.github.kromus.samples.hybrid

import io.github.kromus.HybridIndex

/**
 * Why hybrid beats either retriever alone — and metadata filters.
 *
 * Run: `./gradlew :samples:hybrid:run`
 *
 * Vectors are hand-made 3-D topic coordinates [programming, cooking, music]. Watch how:
 * - vector search finds the *semantically* related doc even though it shares no query words,
 * - BM25 finds the doc with the exact keyword,
 * - the hybrid returns both.
 */
fun main() {
    val index = HybridIndex<String>(dimensions = 3)

    // (topic vector, document text, attributes)
    index.add("concurrency", floatArrayOf(1f, 0f, 0f), "Structured concurrency and thread pools", mapOf("lang" to "en"))
    index.add("cheatsheet", floatArrayOf(0f, 1f, 0f), "Kotlin coroutines cheat sheet", mapOf("lang" to "en"))
    index.add("jazz", floatArrayOf(0f, 0f, 1f), "Eine Geschichte des Jazz", mapOf("lang" to "de"))

    val queryVector = floatArrayOf(1f, 0f, 0f) // semantically: programming / concurrency
    val queryText = "coroutines"               // exact keyword

    println("vector-only : ${index.searchVector(queryVector, k = 3).map { it.key }}")
    println("text-only   : ${index.searchText(queryText, k = 3).map { it.key }}")
    println("hybrid      : ${index.search(queryVector, queryText, k = 3).map { it.key }}")
    println("hybrid (en) : ${index.search(queryVector, queryText, k = 3) { it["lang"] == "en" }.map { it.key }}")
}
