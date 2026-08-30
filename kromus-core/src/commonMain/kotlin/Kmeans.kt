package io.github.kromus

import kotlin.random.Random

/**
 * Lloyd's k-means, made reproducible.
 *
 * Clustering is usually free to be whatever the machine happened to produce, because only the result
 * quality is judged. Here it decides the bytes: an index's layout follows its clusters, and kromus
 * promises that identical content encodes identically on every target. So every choice that would
 * ordinarily be arbitrary is pinned — the seeding draws from a seeded generator, the iteration count
 * is fixed rather than run to convergence, assignment ties go to the lower centroid, and an emptied
 * cluster is re-seeded from a determined point rather than a random one.
 *
 * Fixed iterations rather than "until it stops moving" is the awkward one: convergence is a property
 * of the data, so stopping on it would make the output depend on floating-point details that differ
 * between targets. A fixed budget costs a little quality on hard data and buys reproducibility.
 */
internal class Clustering(
    /** `k * dimensions` centroid coordinates, laid out one centroid after another. */
    val centroids: FloatArray,
    /** Which cluster each input vector belongs to. */
    val assignment: IntArray,
    val k: Int,
)

internal object Kmeans {

    /**
     * Clusters [vectors] into [k] groups.
     *
     * @param vectors one entry per point, each of [dimensions] floats, in stored form.
     */
    fun cluster(
        vectors: List<FloatArray>,
        dimensions: Int,
        k: Int,
        metric: Metric,
        seed: Long,
        iterations: Int,
    ): Clustering {
        require(k >= 1) { "k must be >= 1, was $k" }
        val n = vectors.size
        val effectiveK = if (k > n) n else k
        val centroids = FloatArray(effectiveK * dimensions)
        val assignment = IntArray(n)
        if (n == 0) return Clustering(centroids, assignment, effectiveK)

        seedCentroids(vectors, dimensions, effectiveK, metric, seed, centroids)

        val counts = IntArray(effectiveK)
        val sums = FloatArray(effectiveK * dimensions)
        repeat(iterations) {
            for (i in 0 until n) {
                assignment[i] = nearestCentroid(vectors[i], centroids, dimensions, effectiveK, metric)
            }

            sums.fill(0f)
            counts.fill(0)
            for (i in 0 until n) {
                val c = assignment[i]
                counts[c]++
                val base = c * dimensions
                val v = vectors[i]
                for (d in 0 until dimensions) sums[base + d] += v[d]
            }
            for (c in 0 until effectiveK) {
                val base = c * dimensions
                if (counts[c] == 0) {
                    // An emptied cluster would otherwise stay empty forever and waste a probe. Re-seed
                    // it from a point picked by position rather than at random, so the recovery is as
                    // reproducible as the rest.
                    val donor = vectors[((c.toLong() * 7919) % n).toInt()]
                    donor.copyInto(centroids, base)
                } else {
                    val inv = 1f / counts[c]
                    for (d in 0 until dimensions) centroids[base + d] = sums[base + d] * inv
                }
            }
        }

        // One last assignment against the final centroids, so the result matches what was stored.
        for (i in 0 until n) {
            assignment[i] = nearestCentroid(vectors[i], centroids, dimensions, effectiveK, metric)
        }
        return Clustering(centroids, assignment, effectiveK)
    }

    /**
     * k-means++ seeding: each new centroid is drawn with probability proportional to its squared
     * distance from the nearest already-chosen one, which spreads the starts out instead of letting
     * them clump.
     */
    private fun seedCentroids(
        vectors: List<FloatArray>,
        dimensions: Int,
        k: Int,
        metric: Metric,
        seed: Long,
        into: FloatArray,
    ) {
        val rng = Random(seed)
        val n = vectors.size
        vectors[rng.nextInt(n)].copyInto(into, 0)

        val closest = FloatArray(n) { Float.MAX_VALUE }
        for (c in 1 until k) {
            var total = 0.0
            for (i in 0 until n) {
                // distanceToCentroid is already squared for Euclidean and a cosine distance
                // otherwise; both are the right weight for k-means++ as they stand.
                val d = distanceToCentroid(vectors[i], into, (c - 1) * dimensions, dimensions, metric)
                if (d < closest[i]) closest[i] = d
                total += closest[i].toDouble()
            }
            var target = rng.nextDouble() * total
            var picked = n - 1
            for (i in 0 until n) {
                target -= closest[i].toDouble()
                if (target <= 0.0) {
                    picked = i
                    break
                }
            }
            vectors[picked].copyInto(into, c * dimensions)
        }
    }

    /** The nearest centroid, ties going to the lower index so the choice never depends on order. */
    fun nearestCentroid(
        vector: FloatArray,
        centroids: FloatArray,
        dimensions: Int,
        k: Int,
        metric: Metric,
    ): Int {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (c in 0 until k) {
            val d = distanceToCentroid(vector, centroids, c * dimensions, dimensions, metric)
            if (d < bestDistance) {
                bestDistance = d
                best = c
            }
        }
        return best
    }

    fun distanceToCentroid(
        vector: FloatArray,
        centroids: FloatArray,
        base: Int,
        dimensions: Int,
        metric: Metric,
    ): Float =
        when (metric) {
            Metric.Cosine -> {
                var dot = 0f
                for (d in 0 until dimensions) dot += vector[d] * centroids[base + d]
                1f - dot
            }
            Metric.DotProduct -> {
                var dot = 0f
                for (d in 0 until dimensions) dot += vector[d] * centroids[base + d]
                -dot
            }
            Metric.Euclidean -> {
                var acc = 0f
                for (d in 0 until dimensions) {
                    val diff = vector[d] - centroids[base + d]
                    acc += diff * diff
                }
                acc // squared is enough for ordering, and avoids a sqrt per centroid
            }
        }
}
