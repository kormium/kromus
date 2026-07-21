package io.github.kromus.samples.quickstart

import io.github.kromus.KeyCodec
import io.github.kromus.Metric
import io.github.kromus.VectorIndex
import io.github.kromus.decodeVectorIndex
import io.github.kromus.encodeToByteArray

/**
 * The 60-second tour: build a vector index, search it, persist it.
 *
 * Run: `./gradlew :samples:quickstart:run`
 *
 * The vectors here are hand-made 3-D "topic" coordinates — axes [programming, cooking, music] — so the
 * demo is self-contained. In a real app you'd get them from an embedding model (see `kromus-onnx`).
 */
fun main() {
    val index = VectorIndex<String>(dimensions = 3, metric = Metric.Cosine)

    index.add("kotlin-coroutines-guide", floatArrayOf(1f, 0f, 0f))
    index.add("structured-concurrency", floatArrayOf(0.9f, 0.1f, 0f))
    index.add("sourdough-recipe", floatArrayOf(0f, 1f, 0f))
    index.add("jazz-history", floatArrayOf(0f, 0f, 1f))

    println("Query: something about programming\n")
    val query = floatArrayOf(1f, 0.05f, 0f)
    for (hit in index.search(query, k = 3)) {
        println("  ${format(hit.score)}  ${hit.key}")
    }

    // Building the graph is the expensive part; persist it and reload instantly next run.
    val bytes = index.encodeToByteArray(KeyCodec.string)
    val reloaded = decodeVectorIndex(bytes, KeyCodec.string)
    println("\nPersisted ${index.size} vectors to ${bytes.size} bytes, reloaded ${reloaded.size}.")
}

private fun format(score: Float): String =
    ((score * 1000).toInt() / 1000.0).toString().padEnd(5, ' ')
