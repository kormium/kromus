package io.github.kromus

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Owns the stored vectors for [Hnsw] and computes distances against them, hiding whether they are
 * kept as full floats or quantized. Ids are dense and assignment order; the graph structure lives in
 * [Hnsw]. Distances are "smaller = closer" (see the metric conversions).
 *
 * Two distance forms mirror how HNSW uses them: a full-precision query vector against a stored one
 * ([distanceToQuery]), and two stored vectors against each other ([distanceBetween], used by the
 * neighbour-selection heuristic).
 */
internal interface VectorStore {
    val dimensions: Int
    val metric: Metric
    val size: Int

    /** Stores an already-prepared vector (normalized for Cosine) and returns its id. */
    fun add(prepared: FloatArray): Int

    fun distanceToQuery(query: FloatArray, id: Int): Float

    fun distanceBetween(a: Int, b: Int): Float

    /**
     * Reads a stored vector back as floats — exact for full precision, dequantized otherwise. The
     * result is in *stored* form (already normalized for [Metric.Cosine]), so feeding it back through
     * [Hnsw.addPrepared] reproduces the identical stored representation. That is what makes
     * [VectorIndex.compact] lossless.
     */
    fun reconstruct(id: Int): FloatArray
}

/** Shared metric math over two float vectors. */
internal fun metricDistance(a: FloatArray, b: FloatArray, metric: Metric): Float =
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

/** Exact full-precision storage. */
internal class Float32VectorStore(
    override val dimensions: Int,
    override val metric: Metric,
) : VectorStore {
    private val vectors = ArrayList<FloatArray>()

    override val size: Int get() = vectors.size

    override fun add(prepared: FloatArray): Int {
        vectors.add(prepared)
        return vectors.size - 1
    }

    override fun distanceToQuery(query: FloatArray, id: Int): Float = metricDistance(query, vectors[id], metric)

    override fun distanceBetween(a: Int, b: Int): Float = metricDistance(vectors[a], vectors[b], metric)

    override fun reconstruct(id: Int): FloatArray = vectors[id].copyOf()

    fun vectorAt(id: Int): FloatArray = vectors[id]

    /** Restores a stored vector verbatim (persistence). */
    fun load(vector: FloatArray): Int {
        vectors.add(vector)
        return vectors.size - 1
    }
}

/**
 * 8-bit symmetric scalar quantization with a per-vector scale (~4× smaller than [Float32VectorStore]).
 *
 * Note for [Metric.Cosine]: the dequantized vector is no longer exactly unit length, so the reported
 * distance is the inner product against it rather than a true cosine. The bias is small and uniform
 * enough not to disturb ranking, but scores are marginally off the exact cosine — re-rank with
 * [rerank] when the score value itself matters, not just the order.
 */
internal class Int8VectorStore(
    override val dimensions: Int,
    override val metric: Metric,
) : VectorStore {
    private val codes = ArrayList<ByteArray>()
    private val scales = ArrayList<Float>()

    override val size: Int get() = codes.size

    override fun add(prepared: FloatArray): Int {
        var maxAbs = 0f
        for (x in prepared) {
            val a = abs(x)
            if (a > maxAbs) maxAbs = a
        }
        val scale = if (maxAbs == 0f) 1f else maxAbs / 127f
        val inv = 1f / scale
        val code = ByteArray(dimensions) { (prepared[it] * inv).roundToInt().coerceIn(-127, 127).toByte() }
        codes.add(code)
        scales.add(scale)
        return codes.size - 1
    }

    override fun distanceToQuery(query: FloatArray, id: Int): Float {
        val code = codes[id]
        val scale = scales[id]
        return when (metric) {
            Metric.Cosine -> {
                var acc = 0f
                for (i in query.indices) acc += query[i] * code[i].toInt()
                1f - acc * scale
            }
            Metric.DotProduct -> {
                var acc = 0f
                for (i in query.indices) acc += query[i] * code[i].toInt()
                -acc * scale
            }
            Metric.Euclidean -> {
                var acc = 0f
                for (i in query.indices) {
                    val d = query[i] - code[i].toInt() * scale
                    acc += d * d
                }
                sqrt(acc)
            }
        }
    }

    override fun distanceBetween(a: Int, b: Int): Float {
        val ca = codes[a]
        val cb = codes[b]
        val sa = scales[a]
        val sb = scales[b]
        return when (metric) {
            Metric.Cosine -> {
                var acc = 0
                for (i in ca.indices) acc += ca[i].toInt() * cb[i].toInt()
                1f - acc * sa * sb
            }
            Metric.DotProduct -> {
                var acc = 0
                for (i in ca.indices) acc += ca[i].toInt() * cb[i].toInt()
                -acc * sa * sb
            }
            Metric.Euclidean -> {
                var acc = 0f
                for (i in ca.indices) {
                    val d = ca[i].toInt() * sa - cb[i].toInt() * sb
                    acc += d * d
                }
                sqrt(acc)
            }
        }
    }

    override fun reconstruct(id: Int): FloatArray {
        val code = codes[id]
        val scale = scales[id]
        return FloatArray(dimensions) { code[it].toInt() * scale }
    }

    fun codeAt(id: Int): ByteArray = codes[id]

    fun scaleAt(id: Int): Float = scales[id]

    /** Restores a quantized vector verbatim (persistence). */
    fun load(code: ByteArray, scale: Float): Int {
        codes.add(code)
        scales.add(scale)
        return codes.size - 1
    }
}

/**
 * 1-bit-per-dimension quantization: each component is reduced to its sign (~32× smaller). Stored
 * vectors are compared with Hamming distance (packed 64-bit words + popcount); the full-precision
 * query is compared against the ±1 sign vector directly (asymmetric).
 *
 * Reducing a vector to its signs discards magnitude entirely, which collapses the metrics: all three
 * rank by agreement with the query's signs, so [Metric.DotProduct] loses the magnitude semantics that
 * distinguish it and behaves like [Metric.Cosine]. Pick the metric that suits your data anyway (it
 * still governs the scores you get back and any later re-rank), but do not expect binary quantization
 * to preserve inner-product ranking.
 */
internal class BinaryVectorStore(
    override val dimensions: Int,
    override val metric: Metric,
) : VectorStore {
    private val words = (dimensions + 63) ushr 6
    private val codes = ArrayList<LongArray>()
    private val invSqrtDim = 1f / sqrt(dimensions.toFloat())

    override val size: Int get() = codes.size

    override fun add(prepared: FloatArray): Int {
        val bits = LongArray(words)
        for (i in 0 until dimensions) {
            if (prepared[i] >= 0f) bits[i ushr 6] = bits[i ushr 6] or (1L shl (i and 63))
        }
        codes.add(bits)
        return codes.size - 1
    }

    private fun signAt(bits: LongArray, i: Int): Float =
        if ((bits[i ushr 6] ushr (i and 63)) and 1L == 1L) 1f else -1f

    // --- asymmetric query path ---
    //
    // Every query distance is q · s against a stored ±1 vector. Walking that per dimension — extract
    // the bit, branch, multiply — costs as much as a full float dot product, which would leave binary
    // quantization slower than the precision it is meant to trade away. Instead note that
    // `q · s = 2 * (sum of q over the set bits) - (sum of q)`: only the set-bit sum depends on the
    // stored vector, and that sum is table-driven. The query is preprocessed once per search into
    // per-nibble partial sums, after which each stored vector costs one lookup per 4 dimensions.
    //
    // The table is rebuilt whenever a different query array arrives — identity, not equality, because
    // a search hands the same prepared array to every distance call.

    private val nibbles = (dimensions + 3) ushr 2
    private var lutQuery: FloatArray? = null
    private var lut = FloatArray(0)
    private var lutQuerySum = 0f
    private var lutQueryNormSquared = 0f

    private fun ensureLut(query: FloatArray) {
        if (lutQuery === query) return
        if (lut.size < nibbles * 16) lut = FloatArray(nibbles * 16)

        var sum = 0f
        for (n in 0 until nibbles) {
            val base = n shl 4
            val offset = n shl 2
            lut[base] = 0f
            // Each pattern is the previous one with its lowest set bit added back, so building the
            // table costs one addition per entry.
            for (pattern in 1 until 16) {
                val lowest = pattern and -pattern
                val dimension = offset + lowest.countTrailingZeroBits()
                val q = if (dimension < dimensions) query[dimension] else 0f
                lut[base + pattern] = lut[base + (pattern xor lowest)] + q
            }
            sum += lut[base + 15]
        }
        var normSquared = 0f
        for (i in 0 until dimensions) normSquared += query[i] * query[i]

        lutQuerySum = sum
        lutQueryNormSquared = normSquared
        lutQuery = query
    }

    /** `q · s` for the stored sign vector [id], through the query's nibble table. */
    private fun dotWithQuery(id: Int): Float {
        val bits = codes[id]
        var setBitSum = 0f
        for (n in 0 until nibbles) {
            val pattern = ((bits[n ushr 4] ushr ((n and 15) shl 2)) and 0xFL).toInt()
            setBitSum += lut[(n shl 4) + pattern]
        }
        return 2f * setBitSum - lutQuerySum
    }

    override fun distanceToQuery(query: FloatArray, id: Int): Float {
        ensureLut(query)
        val dot = dotWithQuery(id)
        return when (metric) {
            Metric.Cosine -> 1f - dot * invSqrtDim
            Metric.DotProduct -> -dot
            // |q - s|² = |q|² - 2 q·s + |s|², and |s|² is the dimension count for a ±1 vector.
            Metric.Euclidean -> {
                val squared = lutQueryNormSquared - 2f * dot + dimensions
                if (squared <= 0f) 0f else sqrt(squared)
            }
        }
    }

    override fun distanceBetween(a: Int, b: Int): Float {
        val ca = codes[a]
        val cb = codes[b]
        var hamming = 0
        for (w in 0 until words) hamming += (ca[w] xor cb[w]).countOneBits()
        return when (metric) {
            // dot of two ±1 vectors = dimensions - 2*hamming.
            Metric.Cosine -> 2f * hamming / dimensions
            Metric.DotProduct -> (2 * hamming - dimensions).toFloat()
            Metric.Euclidean -> sqrt(4f * hamming)
        }
    }

    override fun reconstruct(id: Int): FloatArray {
        val bits = codes[id]
        return FloatArray(dimensions) { signAt(bits, it) }
    }

    fun codeAt(id: Int): LongArray = codes[id]

    /** Restores a quantized vector verbatim (persistence). */
    fun load(code: LongArray): Int {
        codes.add(code)
        return codes.size - 1
    }
}
