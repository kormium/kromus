package io.github.kromus

import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A Hierarchical Navigable Small World graph over a growing set of vectors — the approximate
 * nearest-neighbour index behind [VectorIndex].
 *
 * Vectors are addressed by a dense, monotonically increasing internal id (their insertion order).
 * Ids are never reused: a "removed" vector is only flagged [deleted] so it stops appearing in
 * results while still serving as a routing hop, which keeps the graph connected. Callers map their
 * own keys onto these ids ([VectorIndex] does exactly that).
 *
 * Not thread-safe: build and query under a single writer (or external synchronization).
 */
internal class Hnsw(
    private val dimensions: Int,
    private val metric: Metric,
    private val config: HnswConfig,
) {
    // Parallel, id-indexed columns. vectors[id] is stored pre-normalized for Cosine so a plain dot
    // product yields cosine similarity; levels[id] is the node's top layer; neighbors[id][layer] is
    // its adjacency list on that layer (present only for layer <= levels[id]).
    private val vectors = ArrayList<FloatArray>()
    private val levels = ArrayList<Int>()
    private val neighbors = ArrayList<Array<MutableList<Int>>>()
    private val deleted = ArrayList<Boolean>()

    private var entryPoint = -1
    private var topLayer = 0

    private val rng = Random(config.seed)
    private val levelMultiplier = 1.0 / ln(config.m.toDouble())

    /** Number of vectors ever inserted, including flagged-deleted ones (== the id space size). */
    val capacity: Int get() = vectors.size

    fun markDeleted(id: Int) {
        deleted[id] = true
    }

    /** Inserts [rawVector] and returns its internal id. The array is copied; the caller keeps ownership. */
    fun add(rawVector: FloatArray): Int {
        val v = prepare(rawVector)
        val id = vectors.size
        val level = randomLevel()

        vectors.add(v)
        levels.add(level)
        neighbors.add(Array(level + 1) { mutableListOf() })
        deleted.add(false)

        if (entryPoint == -1) {
            entryPoint = id
            topLayer = level
            return id
        }

        // Greedy descent through the layers above the new node's top level (ef = 1).
        var curr = entryPoint
        var currDist = distance(v, curr)
        var lc = topLayer
        while (lc > level) {
            curr = greedyClosest(v, curr, currDist, lc).also { currDist = distance(v, it) }
            lc--
        }

        // From the new node's top level down to 0: find neighbours, link, and prune.
        var entryPoints = intArrayOf(curr)
        lc = if (level < topLayer) level else topLayer
        while (lc >= 0) {
            val candidates = searchLayer(v, entryPoints, config.efConstruction, lc)
            val selected = selectNeighbors(v, candidates, config.m)
            val mMax = if (lc == 0) config.maxM0 else config.m

            val own = neighbors[id][lc]
            for (e in selected) {
                own.add(e)
                val other = neighbors[e][lc]
                other.add(id)
                if (other.size > mMax) {
                    val kept = selectNeighbors(vectors[e], other.toIntArray(), mMax)
                    other.clear()
                    for (k in kept) other.add(k)
                }
            }
            entryPoints = candidates
            lc--
        }

        if (level > topLayer) {
            topLayer = level
            entryPoint = id
        }
        return id
    }

    /**
     * Returns up to [k] non-deleted ids nearest to [rawVector], closest first, each paired with its
     * similarity score (see [Metric]). [ef] is raised to at least [k].
     */
    fun query(rawVector: FloatArray, k: Int, ef: Int): List<Pair<Int, Float>> {
        if (entryPoint == -1) return emptyList()
        val q = prepare(rawVector)

        var curr = entryPoint
        var currDist = distance(q, curr)
        var lc = topLayer
        while (lc > 0) {
            curr = greedyClosest(q, curr, currDist, lc).also { currDist = distance(q, it) }
            lc--
        }

        val efEff = if (ef < k) k else ef
        val found = searchLayer(q, intArrayOf(curr), efEff, 0)
        val out = ArrayList<Pair<Int, Float>>(k)
        for (id in found) {
            if (deleted[id]) continue
            out.add(id to similarityOf(distance(q, id)))
            if (out.size >= k) break
        }
        return out
    }

    /** Walks greedily to the closest node reachable from [start] on [layer] (hill climbing, ef = 1). */
    private fun greedyClosest(q: FloatArray, start: Int, startDist: Float, layer: Int): Int {
        var curr = start
        var currDist = startDist
        var improved = true
        while (improved) {
            improved = false
            for (n in neighborsAt(curr, layer)) {
                val d = distance(q, n)
                if (d < currDist) {
                    currDist = d
                    curr = n
                    improved = true
                }
            }
        }
        return curr
    }

    /**
     * Best-first search on a single [layer]: expands the frontier from [entryPoints] until it can no
     * longer improve the [ef] closest found. Returns those ids sorted ascending by distance to [q].
     */
    private fun searchLayer(q: FloatArray, entryPoints: IntArray, ef: Int, layer: Int): IntArray {
        val visited = HashSet<Int>()
        val frontier = FloatHeap(minHeap = true)
        val best = FloatHeap(minHeap = false)

        for (ep in entryPoints) {
            if (!visited.add(ep)) continue
            val d = distance(q, ep)
            frontier.push(ep, d)
            best.push(ep, d)
        }

        while (!frontier.isEmpty()) {
            val cDist = frontier.peekKey()
            val c = frontier.pop()
            if (best.size >= ef && cDist > best.peekKey()) break
            for (e in neighborsAt(c, layer)) {
                if (!visited.add(e)) continue
                val d = distance(q, e)
                if (best.size < ef || d < best.peekKey()) {
                    frontier.push(e, d)
                    best.push(e, d)
                    if (best.size > ef) best.pop()
                }
            }
        }

        // best is a max-heap; drain it and reverse so the result is closest-first.
        val out = IntArray(best.size)
        var i = out.size - 1
        while (!best.isEmpty()) out[i--] = best.pop()
        return out
    }

    /**
     * Neighbour selection heuristic (Malkov & Yashunin, Algorithm 4): prefer diverse links by keeping
     * a candidate only when it is closer to [base] than to any already-chosen neighbour. Falls back to
     * nearest-remaining to reach [m] so connectivity is never starved. [candidateIds] may be unsorted.
     */
    private fun selectNeighbors(base: FloatArray, candidateIds: IntArray, m: Int): IntArray {
        if (candidateIds.size <= m) return candidateIds

        // Sort candidates ascending by distance to base.
        val dists = FloatArray(candidateIds.size) { distance(base, candidateIds[it]) }
        val order = candidateIds.indices.sortedBy { dists[it] }

        val chosen = ArrayList<Int>(m)
        for (idx in order) {
            if (chosen.size >= m) break
            val cand = candidateIds[idx]
            val dBaseCand = dists[idx]
            var keep = true
            for (r in chosen) {
                if (distance(vectors[cand], r) < dBaseCand) {
                    keep = false
                    break
                }
            }
            if (keep) chosen.add(cand)
        }
        if (chosen.size < m) {
            for (idx in order) {
                if (chosen.size >= m) break
                val cand = candidateIds[idx]
                if (cand !in chosen) chosen.add(cand)
            }
        }
        return chosen.toIntArray()
    }

    private fun neighborsAt(id: Int, layer: Int): List<Int> =
        if (layer <= levels[id]) neighbors[id][layer] else emptyList()

    private fun randomLevel(): Int {
        val r = rng.nextDouble()
        val u = if (r <= 0.0) Double.MIN_VALUE else r
        return (-ln(u) * levelMultiplier).toInt()
    }

    /** Normalizes for Cosine (so dot == cosine), otherwise copies defensively. */
    private fun prepare(vector: FloatArray): FloatArray {
        if (metric != Metric.Cosine) return vector.copyOf()
        var sum = 0f
        for (x in vector) sum += x * x
        if (sum == 0f) return vector.copyOf()
        val inv = 1f / sqrt(sum)
        return FloatArray(vector.size) { vector[it] * inv }
    }

    /** Distance from a prepared query [q] to the stored vector [id]. Smaller = closer. */
    private fun distance(q: FloatArray, id: Int): Float = distance(q, vectors[id])

    private fun distance(a: FloatArray, b: FloatArray): Float =
        when (metric) {
            Metric.Cosine -> {
                var dot = 0f
                for (i in a.indices) dot += a[i] * b[i]
                1f - dot
            }
            Metric.DotProduct -> {
                var dot = 0f
                for (i in a.indices) dot += a[i] * b[i]
                -dot
            }
            Metric.Euclidean -> {
                var s = 0f
                for (i in a.indices) {
                    val d = a[i] - b[i]
                    s += d * d
                }
                sqrt(s)
            }
        }

    private fun similarityOf(distance: Float): Float =
        when (metric) {
            Metric.Cosine -> 1f - distance
            Metric.DotProduct, Metric.Euclidean -> -distance
        }
}
