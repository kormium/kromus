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
 * Fit more on device with binary quantization (~32× smaller), then recover accuracy with a
 * full-precision re-rank (two-phase search).
 *
 * Run: `./gradlew :samples:quantization:run`
 */
fun main() {
    val dim = 128
    val rng = Random(1)

    // 15 clusters of 60 points each — embedding-like data.
    val data = ArrayList<FloatArray>()
    val centers = List(15) { FloatArray(dim) { rng.nextFloat() * 2f - 1f } }
    for (center in centers) repeat(60) {
        data.add(FloatArray(dim) { center[it] + (rng.nextFloat() * 2f - 1f) * 0.10f })
    }

    val binary = VectorIndex<Int>(dim, Metric.Cosine, HnswConfig(quantization = Quantization.Binary))
    val float32 = VectorIndex<Int>(dim, Metric.Cosine)
    data.forEachIndexed { i, v -> binary.add(i, v); float32.add(i, v) }

    // Vector data itself: float32 is dim*4 bytes/vector, binary is dim/8 — about 32× smaller.
    val floatVectorBytes = data.size * dim * 4
    val binaryVectorBytes = data.size * ((dim + 7) / 8)
    println("vector data : float32 = $floatVectorBytes bytes,  binary = $binaryVectorBytes bytes  (~${floatVectorBytes / binaryVectorBytes}× smaller)")
    // The full serialized index also carries the (identical) HNSW graph, so the total ratio is smaller.
    val floatPayload = float32.encodeToByteArray(KeyCodec.int).size
    val binaryPayload = binary.encodeToByteArray(KeyCodec.int).size
    println("full payload: float32 = $floatPayload bytes,  binary = $binaryPayload bytes  (graph included)\n")

    // Query near cluster 3.
    val query = FloatArray(dim) { centers[3][it] + (rng.nextFloat() * 2f - 1f) * 0.10f }

    val coarse = binary.search(query, k = 50).map { it.key }        // fast, approximate
    val reranked = rerank(query, coarse, k = 5, Metric.Cosine) { data[it] }  // exact top-5

    println("binary top-5   : ${binary.search(query, k = 5).map { it.key }}")
    println("re-ranked top-5: ${reranked.map { it.key }}")
    println("\n(all keys ${3 * 60}..${4 * 60 - 1} belong to the query's cluster)")
}
