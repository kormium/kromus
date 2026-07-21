package io.github.kromus.samples.quickstart

import io.github.kromus.KeyCodec
import io.github.kromus.Metric
import io.github.kromus.VectorIndex
import io.github.kromus.decodeVectorIndex
import io.github.kromus.encodeToByteArray
import io.github.kromus.samples.common.ToyEmbedder

/**
 * Semantic search in a nutshell: find things by *meaning*, not just matching words.
 *
 * Run: `./gradlew :samples:quickstart:run`
 */
fun main() {
    val embedder = ToyEmbedder() // stands in for a real embedding model (see kromus-onnx)
    val index = VectorIndex<String>(dimensions = embedder.dimensions, metric = Metric.Cosine)

    // Index a few articles by their titles.
    val titles = listOf(
        "Kotlin coroutines guide",
        "Sourdough bread from scratch",
        "A history of jazz",
        "Backpacking in the Alps",
    )
    for (title in titles) index.add(title, embedder.embed(title))

    // Ask a plain-English question that shares NO words with any title.
    val question = "how do I write asynchronous code?"
    println("Search: \"$question\"\n")
    for (hit in index.search(embedder.embed(question), k = 3)) {
        println("  ${percent(hit.score)}  ${hit.key}")
    }
    println("\n→ It found the coroutines guide from the *meaning* — not one word overlaps with the query.")

    // Building the index is the costly part; persist it and reload instantly next run.
    val bytes = index.encodeToByteArray(KeyCodec.string)
    val reloaded = decodeVectorIndex(bytes, KeyCodec.string)
    println("Persisted ${index.size} items to ${bytes.size} bytes; reloaded ${reloaded.size}.")
}

private fun percent(score: Float): String = "${(score * 100).toInt()}%".padStart(4)
