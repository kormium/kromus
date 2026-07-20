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

    fun vectorAt(id: Int): FloatArray = vectors[id]

    /** Restores a stored vector verbatim (persistence). */
    fun load(vector: FloatArray): Int {
        vectors.add(vector)
        return vectors.size - 1
    }
}

/** 8-bit symmetric scalar quantization with a per-vector scale (~4× smaller than [Float32VectorStore]). */
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

    override fun distanceToQuery(query: FloatArray, id: Int): Float {
        val bits = codes[id]
        return when (metric) {
            Metric.Cosine -> {
                var dot = 0f
                for (i in 0 until dimensions) dot += query[i] * signAt(bits, i)
                1f - dot * invSqrtDim
            }
            Metric.DotProduct -> {
                var dot = 0f
                for (i in 0 until dimensions) dot += query[i] * signAt(bits, i)
                -dot
            }
            Metric.Euclidean -> {
                var acc = 0f
                for (i in 0 until dimensions) {
                    val d = query[i] - signAt(bits, i)
                    acc += d * d
                }
                sqrt(acc)
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

    fun codeAt(id: Int): LongArray = codes[id]

    /** Restores a quantized vector verbatim (persistence). */
    fun load(code: LongArray): Int {
        codes.add(code)
        return codes.size - 1
    }
}
