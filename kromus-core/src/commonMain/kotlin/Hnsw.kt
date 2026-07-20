package io.github.kromus

import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A Hierarchical Navigable Small World graph over a growing set of vectors — the approximate
 * nearest-neighbour index behind [VectorIndex]. Vector storage and distance computation are delegated
 * to a [VectorStore] (full-precision or quantized); this class owns only the graph.
 *
 * Vectors are addressed by a dense, monotonically increasing internal id (their insertion order).
 * Ids are never reused: a "removed" vector is only flagged [deleted] so it stops appearing in
 * results while still serving as a routing hop, which keeps the graph connected.
 *
 * Not thread-safe: build and query under a single writer (or external synchronization).
 */
internal class Hnsw private constructor(
    private val metric: Metric,
    private val config: HnswConfig,
    private val store: VectorStore,
) {
    constructor(dimensions: Int, metric: Metric, config: HnswConfig) :
        this(metric, config, newStore(dimensions, metric, config.quantization))

    // Id-indexed graph columns (parallel to the store). levels[id] is the node's top layer;
    // neighbors[id][layer] its adjacency list on that layer (present only for layer <= levels[id]).
    private val levels = ArrayList<Int>()
    private val neighbors = ArrayList<Array<MutableList<Int>>>()
    private val deleted = ArrayList<Boolean>()

    private var entryPoint = -1
    private var topLayer = 0

    private val rng = Random(config.seed)
    private val levelMultiplier = 1.0 / ln(config.m.toDouble())

    private val ACCEPT_ALL: (Int) -> Boolean = { true }

    /** Number of vectors ever inserted, including flagged-deleted ones (== the id space size). */
    val capacity: Int get() = store.size

    fun markDeleted(id: Int) {
        deleted[id] = true
    }

    /** Inserts [rawVector] and returns its internal id. */
    fun add(rawVector: FloatArray): Int {
        val prepared = prepare(rawVector)
        val id = store.add(prepared)
        val level = randomLevel()

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
        var currDist = store.distanceToQuery(prepared, curr)
        var lc = topLayer
        while (lc > level) {
            curr = greedyClosest(prepared, curr, currDist, lc).also { currDist = store.distanceToQuery(prepared, it) }
            lc--
        }

        // From the new node's top level down to 0: find neighbours, link, and prune. Construction
        // considers every node (including deleted ones) so the graph stays connected.
        var entryPoints = intArrayOf(curr)
        lc = if (level < topLayer) level else topLayer
        while (lc >= 0) {
            val candidates = searchLayer(prepared, entryPoints, config.efConstruction, lc, ACCEPT_ALL)
            val selected = selectNeighbors(id, candidates, config.m)
            val mMax = if (lc == 0) config.maxM0 else config.m

            val own = neighbors[id][lc]
            for (e in selected) {
                own.add(e)
                val other = neighbors[e][lc]
                other.add(id)
                if (other.size > mMax) {
                    val kept = selectNeighbors(e, other.toIntArray(), mMax)
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
     * Returns up to [k] nearest ids to [rawVector] that are not deleted and satisfy [accept], closest
     * first, each paired with its similarity score (see [Metric]). [ef] is raised to at least [k].
     * [accept] is evaluated during the layer-0 search: rejected nodes are still traversed for
     * connectivity but never returned, so a filtered query still yields up to [k] matches (a very
     * selective filter benefits from a larger [ef]).
     */
    fun query(rawVector: FloatArray, k: Int, ef: Int, accept: (Int) -> Boolean = { true }): List<Pair<Int, Float>> {
        if (entryPoint == -1) return emptyList()
        val q = prepare(rawVector)

        var curr = entryPoint
        var currDist = store.distanceToQuery(q, curr)
        var lc = topLayer
        while (lc > 0) {
            curr = greedyClosest(q, curr, currDist, lc).also { currDist = store.distanceToQuery(q, it) }
            lc--
        }

        val efEff = if (ef < k) k else ef
        val acceptable = { id: Int -> !deleted[id] && accept(id) }
        val found = searchLayer(q, intArrayOf(curr), efEff, 0, acceptable)
        val out = ArrayList<Pair<Int, Float>>(k)
        for (id in found) {
            out.add(id to similarityOf(store.distanceToQuery(q, id)))
            if (out.size >= k) break
        }
        return out
    }

    /** Walks greedily to the closest node reachable from [start] on [layer] (hill climbing, ef = 1). */
    private fun greedyClosest(query: FloatArray, start: Int, startDist: Float, layer: Int): Int {
        var curr = start
        var currDist = startDist
        var improved = true
        while (improved) {
            improved = false
            for (n in neighborsAt(curr, layer)) {
                val d = store.distanceToQuery(query, n)
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
     * longer improve the [ef] closest *acceptable* nodes. Every visited node is traversed, but only
     * those passing [acceptable] enter the result set — so filtering happens mid-traversal, keeping
     * the graph fully explorable. Returns the accepted ids sorted ascending by distance to [query].
     */
    private fun searchLayer(
        query: FloatArray,
        entryPoints: IntArray,
        ef: Int,
        layer: Int,
        acceptable: (Int) -> Boolean,
    ): IntArray {
        val visited = HashSet<Int>()
        val frontier = FloatHeap(minHeap = true)
        val best = FloatHeap(minHeap = false)

        for (ep in entryPoints) {
            if (!visited.add(ep)) continue
            val d = store.distanceToQuery(query, ep)
            frontier.push(ep, d)
            if (acceptable(ep)) {
                best.push(ep, d)
                if (best.size > ef) best.pop()
            }
        }

        while (!frontier.isEmpty()) {
            val cDist = frontier.peekKey()
            val c = frontier.pop()
            if (best.size >= ef && cDist > best.peekKey()) break
            for (e in neighborsAt(c, layer)) {
                if (!visited.add(e)) continue
                val d = store.distanceToQuery(query, e)
                // Explore whenever results aren't full yet or this node beats the current worst.
                val bound = if (best.size < ef) Float.MAX_VALUE else best.peekKey()
                if (d < bound) {
                    frontier.push(e, d)
                    if (acceptable(e)) {
                        best.push(e, d)
                        if (best.size > ef) best.pop()
                    }
                }
            }
        }

        val out = IntArray(best.size)
        var i = out.size - 1
        while (!best.isEmpty()) out[i--] = best.pop()
        return out
    }

    /**
     * Neighbour selection heuristic (Malkov & Yashunin, Algorithm 4): prefer diverse links by keeping
     * a candidate only when it is closer to [baseId] than to any already-chosen neighbour. Falls back
     * to nearest-remaining to reach [m] so connectivity is never starved. Operates entirely on stored
     * vectors via [VectorStore.distanceBetween].
     */
    private fun selectNeighbors(baseId: Int, candidateIds: IntArray, m: Int): IntArray {
        if (candidateIds.size <= m) return candidateIds

        val dists = FloatArray(candidateIds.size) { store.distanceBetween(baseId, candidateIds[it]) }
        val order = candidateIds.indices.sortedBy { dists[it] }

        val chosen = ArrayList<Int>(m)
        for (idx in order) {
            if (chosen.size >= m) break
            val cand = candidateIds[idx]
            val dBaseCand = dists[idx]
            var keep = true
            for (r in chosen) {
                if (store.distanceBetween(cand, r) < dBaseCand) {
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

    private fun similarityOf(distance: Float): Float =
        when (metric) {
            Metric.Cosine -> 1f - distance
            Metric.DotProduct, Metric.Euclidean -> -distance
        }

    // --- persistence support (read side) ---

    val entryPointValue: Int get() = entryPoint
    val topLayerValue: Int get() = topLayer

    fun levelAt(id: Int): Int = levels[id]
    fun deletedAt(id: Int): Boolean = deleted[id]
    fun neighborsAtLayer(id: Int, layer: Int): List<Int> = neighbors[id][layer]
    fun store(): VectorStore = store

    internal companion object {
        fun newStore(dimensions: Int, metric: Metric, quantization: Quantization): VectorStore =
            when (quantization) {
                Quantization.None -> Float32VectorStore(dimensions, metric)
                Quantization.Int8 -> Int8VectorStore(dimensions, metric)
            }

        /** Rebuilds a graph over an already-populated [store], bypassing insertion. */
        fun restore(
            metric: Metric,
            config: HnswConfig,
            store: VectorStore,
            levels: IntArray,
            neighbors: List<Array<IntArray>>,
            deleted: BooleanArray,
            entryPoint: Int,
            topLayer: Int,
        ): Hnsw {
            val h = Hnsw(metric, config, store)
            for (id in 0 until store.size) {
                h.levels.add(levels[id])
                h.deleted.add(deleted[id])
                h.neighbors.add(Array(neighbors[id].size) { neighbors[id][it].toMutableList() })
            }
            h.entryPoint = entryPoint
            h.topLayer = topLayer
            return h
        }
    }
}
