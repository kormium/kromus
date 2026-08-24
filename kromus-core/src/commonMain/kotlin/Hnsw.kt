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
 * Ids are never reused: a "removed" vector is only flagged [deletedAt] so it stops appearing in
 * results while still serving as a routing hop, which keeps the graph connected. Reclaiming those
 * slots is a rebuild — see [VectorIndex.compact].
 *
 * Graph columns are primitive arrays and the per-search scratch (visited set, candidate heaps) is
 * allocated once and reused, so a query allocates only its result. That reuse is also why this class
 * is not thread-safe: build and query under a single writer (or external synchronization).
 */
internal class Hnsw private constructor(
    private val metric: Metric,
    private val config: HnswConfig,
    private val store: VectorStore,
) {
    constructor(dimensions: Int, metric: Metric, config: HnswConfig) :
        this(metric, config, newStore(dimensions, metric, config.quantization))

    // Id-indexed graph columns, parallel to the store and sized by nodeCount. levels[id] is the
    // node's top layer; neighbors[id][layer] its adjacency list on that layer (present only for
    // layer <= levels[id]). Kept as primitive arrays: an ArrayList<Int>/MutableList<Int> would box
    // every level, flag and neighbour, which dominates memory on a large index.
    private var levels = IntArray(0)
    private var deletedFlags = BooleanArray(0)
    private val neighbors = ArrayList<Array<IntArrayList>>()
    private var nodeCount = 0
    private var deletedCount = 0

    private var entryPoint = -1
    private var topLayer = 0

    private val rng = Random(config.seed)
    private val levelMultiplier = 1.0 / ln(config.m.toDouble())

    // --- per-search scratch, reused across calls (single-writer contract) ---

    private val frontier = FloatHeap(minHeap = true)
    private val best = FloatHeap(minHeap = false)

    // Visited set as marks + a monotonic epoch: O(1) test and mark, no per-node boxing, and no
    // clearing between searches (a stale mark simply predates the current epoch).
    private var visitMarks = IntArray(0)
    private var visitEpoch = 0

    // Output of the most recent searchLayer: ids ascending by distance, with those distances.
    private var layerIds = IntArray(0)
    private var layerDists = FloatArray(0)
    private var layerCount = 0

    /** Distance of the node last returned by [greedyClosest], so callers need not recompute it. */
    private var greedyDistance = 0f

    /** Number of vectors ever inserted, including flagged-deleted ones (== the id space size). */
    val capacity: Int get() = store.size

    /** Number of ids flagged deleted; the space a rebuild would reclaim. */
    val tombstones: Int get() = deletedCount

    fun markDeleted(id: Int) {
        if (deletedFlags[id]) return
        deletedFlags[id] = true
        deletedCount++
    }

    /** Inserts [rawVector] and returns its internal id. */
    fun add(rawVector: FloatArray): Int = addPrepared(prepare(rawVector))

    /**
     * Inserts a vector that is already in stored form (L2-normalized for [Metric.Cosine]), skipping
     * [prepare]. Used when rebuilding a graph from vectors read back out of a store, where preparing
     * again would re-normalize an already-normalized vector and drift.
     */
    fun addPrepared(prepared: FloatArray): Int {
        val id = store.add(prepared)
        val level = randomLevel()

        ensureNodeCapacity(id + 1)
        levels[id] = level
        deletedFlags[id] = false
        neighbors.add(Array(level + 1) { IntArrayList() })
        nodeCount = id + 1

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
            curr = greedyClosest(prepared, curr, currDist, lc)
            currDist = greedyDistance
            lc--
        }

        // From the new node's top level down to 0: find neighbours, link, and prune. Construction
        // considers every node (including deleted ones) so the graph stays connected.
        var entryPoints = intArrayOf(curr)
        lc = if (level < topLayer) level else topLayer
        while (lc >= 0) {
            searchLayer(prepared, entryPoints, config.efConstruction, lc, Int.MAX_VALUE, ACCEPT_ALL)
            val candidates = layerIds.copyOf(layerCount)
            val selected = selectNeighbors(id, candidates, candidates.size, config.m)
            val mMax = if (lc == 0) config.maxM0 else config.m

            val own = neighbors[id][lc]
            for (e in selected) {
                own.add(e)
                val other = neighbors[e][lc]
                other.add(id)
                if (other.size > mMax) {
                    val kept = selectNeighbors(e, other.data, other.size, mMax)
                    other.setAll(kept, kept.size)
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
     *
     * [maxVisited] caps how many nodes the traversal may touch; `<= 0` means the whole index, which
     * is also its natural ceiling. See [HnswConfig.maxVisited].
     */
    fun query(
        rawVector: FloatArray,
        k: Int,
        ef: Int,
        maxVisited: Int,
        accept: (Int) -> Boolean = ACCEPT_ALL,
    ): Hits {
        if (entryPoint == -1) return Hits.EMPTY
        val q = prepare(rawVector)

        var curr = entryPoint
        var currDist = store.distanceToQuery(q, curr)
        var lc = topLayer
        while (lc > 0) {
            curr = greedyClosest(q, curr, currDist, lc)
            currDist = greedyDistance
            lc--
        }

        val efEff = if (ef < k) k else ef
        val budget = when {
            maxVisited <= 0 -> store.size
            maxVisited < efEff -> efEff
            else -> maxVisited
        }
        val acceptable = { id: Int -> !deletedFlags[id] && accept(id) }
        searchLayer(q, intArrayOf(curr), efEff, 0, budget, acceptable)

        val n = if (layerCount < k) layerCount else k
        val ids = IntArray(n)
        val scores = FloatArray(n)
        for (i in 0 until n) {
            ids[i] = layerIds[i]
            scores[i] = similarityOf(layerDists[i])
        }
        return Hits(ids, scores)
    }

    /** Walks greedily to the closest node reachable from [start] on [layer] (hill climbing, ef = 1). */
    private fun greedyClosest(query: FloatArray, start: Int, startDist: Float, layer: Int): Int {
        var curr = start
        var currDist = startDist
        var improved = true
        while (improved) {
            improved = false
            val nb = neighborsAt(curr, layer)
            if (nb != null) {
                for (i in 0 until nb.size) {
                    val n = nb[i]
                    val d = store.distanceToQuery(query, n)
                    if (d < currDist) {
                        currDist = d
                        curr = n
                        improved = true
                    }
                }
            }
        }
        greedyDistance = currDist
        return curr
    }

    /**
     * Best-first search on a single [layer]: expands the frontier from [entryPoints] until it can no
     * longer improve the [ef] closest *acceptable* nodes, or until [maxVisited] nodes have been
     * touched. Every visited node is traversed, but only those passing [acceptable] enter the result
     * set — so filtering happens mid-traversal, keeping the graph fully explorable.
     *
     * Writes its result into [layerIds] / [layerDists] / [layerCount], ascending by distance, instead
     * of allocating: the layer search runs once per layer per insert and once per query.
     */
    private fun searchLayer(
        query: FloatArray,
        entryPoints: IntArray,
        ef: Int,
        layer: Int,
        maxVisited: Int,
        acceptable: (Int) -> Boolean,
    ) {
        beginVisits()
        frontier.clear()
        best.clear()
        frontier.reserve(ef)
        best.reserve(ef + 1)
        var visits = 0

        for (ep in entryPoints) {
            if (!visit(ep)) continue
            visits++
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
            if (visits >= maxVisited) break
            val nb = neighborsAt(c, layer) ?: continue
            for (i in 0 until nb.size) {
                val e = nb[i]
                if (!visit(e)) continue
                visits++
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
                if (visits >= maxVisited) break
            }
        }

        layerCount = best.size
        ensureLayerCapacity(layerCount)
        var i = layerCount - 1
        while (!best.isEmpty()) {
            val d = best.peekKey()
            layerIds[i] = best.pop()
            layerDists[i] = d
            i--
        }
    }

    /**
     * Neighbour selection heuristic (Malkov & Yashunin, Algorithm 4): prefer diverse links by keeping
     * a candidate only when it is closer to [baseId] than to any already-chosen neighbour. Falls back
     * to nearest-remaining to reach [m] so connectivity is never starved. Reads the first [count]
     * entries of [candidateIds] and operates entirely on stored vectors via [VectorStore.distanceBetween].
     */
    private fun selectNeighbors(baseId: Int, candidateIds: IntArray, count: Int, m: Int): IntArray {
        if (count <= m) return candidateIds.copyOf(count)

        val dists = FloatArray(count) { store.distanceBetween(baseId, candidateIds[it]) }
        val order = (0 until count).sortedBy { dists[it] }

        val chosen = IntArray(m)
        var chosenSize = 0
        for (idx in order) {
            if (chosenSize >= m) break
            val cand = candidateIds[idx]
            val dBaseCand = dists[idx]
            var keep = true
            for (r in 0 until chosenSize) {
                if (store.distanceBetween(cand, chosen[r]) < dBaseCand) {
                    keep = false
                    break
                }
            }
            if (keep) chosen[chosenSize++] = cand
        }
        if (chosenSize < m) {
            for (idx in order) {
                if (chosenSize >= m) break
                val cand = candidateIds[idx]
                var already = false
                for (r in 0 until chosenSize) {
                    if (chosen[r] == cand) {
                        already = true
                        break
                    }
                }
                if (!already) chosen[chosenSize++] = cand
            }
        }
        return if (chosenSize == m) chosen else chosen.copyOf(chosenSize)
    }

    private fun neighborsAt(id: Int, layer: Int): IntArrayList? =
        if (layer <= levels[id]) neighbors[id][layer] else null

    private fun randomLevel(): Int {
        val r = rng.nextDouble()
        val u = if (r <= 0.0) Double.MIN_VALUE else r
        return (-ln(u) * levelMultiplier).toInt()
    }

    private fun ensureNodeCapacity(needed: Int) {
        if (levels.size >= needed) return
        val n = maxOf(needed, if (levels.size == 0) 16 else levels.size * 2)
        levels = levels.copyOf(n)
        deletedFlags = deletedFlags.copyOf(n)
    }

    private fun ensureLayerCapacity(needed: Int) {
        if (layerIds.size >= needed) return
        layerIds = IntArray(needed)
        layerDists = FloatArray(needed)
    }

    /** Opens a new visited epoch, resizing (and, on the rare wrap, clearing) the mark array. */
    private fun beginVisits() {
        if (visitMarks.size < nodeCount) visitMarks = visitMarks.copyOf(maxOf(nodeCount, visitMarks.size * 2))
        if (visitEpoch == Int.MAX_VALUE) {
            visitMarks.fill(0)
            visitEpoch = 0
        }
        visitEpoch++
    }

    /** Marks [id] visited in the current epoch; false if it was already visited. */
    private fun visit(id: Int): Boolean {
        if (visitMarks[id] == visitEpoch) return false
        visitMarks[id] = visitEpoch
        return true
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

    fun deletedAt(id: Int): Boolean = deletedFlags[id]

    fun neighborsAtLayer(id: Int, layer: Int): IntArrayList = neighbors[id][layer]

    fun store(): VectorStore = store

    /** Ids ascending by distance with their similarity scores — the allocation-light result of [query]. */
    internal class Hits(
        val ids: IntArray,
        val scores: FloatArray,
    ) {
        val size: Int get() = ids.size

        companion object {
            val EMPTY: Hits = Hits(IntArray(0), FloatArray(0))
        }
    }

    internal companion object {
        private val ACCEPT_ALL: (Int) -> Boolean = { true }

        fun newStore(dimensions: Int, metric: Metric, quantization: Quantization): VectorStore =
            when (quantization) {
                Quantization.None -> Float32VectorStore(dimensions, metric)
                Quantization.Int8 -> Int8VectorStore(dimensions, metric)
                Quantization.Binary -> BinaryVectorStore(dimensions, metric)
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
            val n = store.size
            h.ensureNodeCapacity(n)
            for (id in 0 until n) {
                h.levels[id] = levels[id]
                h.deletedFlags[id] = deleted[id]
                if (deleted[id]) h.deletedCount++
                h.neighbors.add(
                    Array(neighbors[id].size) { layer ->
                        val links = neighbors[id][layer]
                        IntArrayList(maxOf(links.size, 1)).also { it.setAll(links, links.size) }
                    },
                )
            }
            h.nodeCount = n
            h.entryPoint = entryPoint
            h.topLayer = topLayer
            return h
        }
    }
}
