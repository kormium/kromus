package io.github.kromus.benchmarks

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A synthetic corpus shaped like real embeddings rather than uniform noise: vectors are drawn around
 * a set of cluster centroids and L2-normalized, so nearest neighbours are genuinely closer than the
 * rest and recall means something. Uniformly random vectors in high dimensions are all roughly
 * equidistant, which flatters an ANN index — every candidate looks as good as the true neighbour.
 *
 * Everything is seeded, so a run is reproducible and two runs are comparable.
 */
class Dataset(
    val vectors: List<FloatArray>,
    val queries: List<FloatArray>,
    val dimensions: Int,
) {
    companion object {
        /**
         * @param spread how far members drift from their centroid, as a multiple of the centroid's
         *   own length. Below 1 the clusters are tight and any index finds them; well above 1 the
         *   corpus is indistinguishable from uniform noise, where the tenth nearest neighbour is
         *   barely closer than a random point and recall collapses for *any* graph index. Real
         *   embedding corpora sit in between, which is where the default lands.
         */
        fun generate(
            count: Int,
            dimensions: Int,
            queries: Int,
            clusters: Int = 100,
            spread: Float = 1f,
            seed: Long = 1,
        ): Dataset {
            val rng = Random(seed)
            // Per-dimension noise is scaled by 1/sqrt(d) so `spread` means the same thing at every
            // dimension: a raw per-dimension sigma would swamp the unit-length centroid as d grows.
            val sigma = spread / sqrt(dimensions.toFloat())
            val centroids = List(clusters) { normalize(FloatArray(dimensions) { gaussian(rng) }) }
            val vectors = List(count) { i ->
                val centroid = centroids[i % clusters]
                normalize(FloatArray(dimensions) { centroid[it] + gaussian(rng) * sigma })
            }
            // Queries sit near the clusters too, but are not corpus members.
            val queryVectors = List(queries) {
                val centroid = centroids[rng.nextInt(clusters)]
                normalize(FloatArray(dimensions) { centroid[it] + gaussian(rng) * sigma * 1.3f })
            }
            return Dataset(vectors, queryVectors, dimensions)
        }

        private fun gaussian(rng: Random): Float {
            // Box-Muller; the tail beyond ±4σ is irrelevant here.
            val u1 = rng.nextDouble().coerceAtLeast(1e-12)
            val u2 = rng.nextDouble()
            return (sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * kotlin.math.PI * u2)).toFloat()
        }

        private fun normalize(v: FloatArray): FloatArray {
            var sum = 0f
            for (x in v) sum += x * x
            if (sum == 0f) return v
            val inv = 1f / sqrt(sum)
            return FloatArray(v.size) { v[it] * inv }
        }
    }

    /**
     * How much closer the true k-th neighbour is than an average corpus member, averaged over the
     * queries. Near 1 means the corpus is effectively noise and no approximate index can do well on
     * it; this is reported alongside the results so a recall number can be read in context.
     */
    fun contrast(k: Int): Double {
        var total = 0.0
        for (query in queries) {
            var sum = 0.0
            val scores = FloatArray(vectors.size)
            for (i in vectors.indices) {
                var dot = 0f
                val v = vectors[i]
                for (d in v.indices) dot += query[d] * v[d]
                scores[i] = dot
                sum += dot
            }
            val kth = scores.sortedDescending()[k - 1]
            val mean = sum / vectors.size
            total += (1.0 - mean) / (1.0 - kth).coerceAtLeast(1e-9)
        }
        return total / queries.size
    }

    /** Exact cosine top-[k] per query, by brute force — the ground truth recall is measured against. */
    fun groundTruth(k: Int): List<IntArray> = queries.map { query ->
        val scores = FloatArray(vectors.size)
        for (i in vectors.indices) {
            var dot = 0f
            val v = vectors[i]
            for (d in v.indices) dot += query[d] * v[d]
            scores[i] = dot
        }
        scores.indices
            .sortedByDescending { scores[it] }
            .take(k)
            .toIntArray()
    }
}

/** Fraction of the true top-k that a result list actually contains, averaged over the queries. */
fun recallAt(truth: List<IntArray>, results: List<List<Int>>, k: Int): Double {
    var hits = 0
    for (i in truth.indices) {
        val expected = truth[i].take(k).toHashSet()
        for (key in results[i].take(k)) if (key in expected) hits++
    }
    return hits.toDouble() / (truth.size * k)
}
