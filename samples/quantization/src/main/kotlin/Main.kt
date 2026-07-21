package io.github.kromus.samples.quantization

import io.github.kromus.HnswConfig
import io.github.kromus.KeyCodec
import io.github.kromus.Metric
import io.github.kromus.Quantization
import io.github.kromus.VectorIndex
import io.github.kromus.encodeToByteArray
import io.github.kromus.rerank
import kotlin.random.Random

/**
 * Fitting a lot of vectors on a phone.
 *
 * Real embeddings are big — often 384 numbers each. With thousands of documents that adds up.
 * "Binary quantization" keeps just the sign of each number (1 bit instead of 32), shrinking the
 * vectors ~32×. That makes search a bit rough, so you do it in two steps: a fast rough search to get
 * candidates, then re-score those few candidates with the exact vectors. You get small *and* accurate.
 *
 * Run: `./gradlew :samples:quantization:run`
 */
fun main() {
    val dimensions = 384
    val rng = Random(1)

    // Stand in for 750 document embeddings from a real model, in 15 topical clusters.
    val embeddings = ArrayList<FloatArray>()
    val clusterCenters = List(15) { FloatArray(dimensions) { rng.nextFloat() * 2f - 1f } }
    for (center in clusterCenters) repeat(50) {
        embeddings.add(FloatArray(dimensions) { center[it] + (rng.nextFloat() * 2f - 1f) * 0.10f })
    }

    val binary = VectorIndex<Int>(dimensions, Metric.Cosine, HnswConfig(quantization = Quantization.Binary))
    embeddings.forEachIndexed { id, vector -> binary.add(id, vector) }

    val floatVectorKb = embeddings.size * dimensions * 4 / 1024
    val binaryVectorKb = embeddings.size * ((dimensions + 7) / 8) / 1024
    println("Vector memory: full precision ≈ ${floatVectorKb} KB,  binary ≈ ${binaryVectorKb} KB  (~${floatVectorKb / binaryVectorKb}× smaller)\n")

    // A query near cluster #3 (its documents are ids 150..199).
    val query = FloatArray(dimensions) { clusterCenters[3][it] + (rng.nextFloat() * 2f - 1f) * 0.10f }

    val roughTop5 = binary.search(query, k = 5).map { it.key }
    val candidates = binary.search(query, k = 50).map { it.key }
    val exactTop5 = rerank(query, candidates, k = 5, Metric.Cosine) { embeddings[it] }.map { it.key }

    println("Step 1 — fast rough search (binary):     $roughTop5")
    println("Step 2 — re-ranked with exact vectors:   $exactTop5")
    println("\n(cluster #3 = document ids 150..199 — the re-ranked hits should all land in that range)")
}
