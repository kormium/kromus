package io.github.kromus

import kotlin.math.sqrt

/**
 * Distances from a query to a run of stored vectors, read straight out of a byte block.
 *
 * These mirror [VectorStore]'s arithmetic exactly — the same expressions, reading from bytes instead
 * of from arrays — because a second implementation that disagrees anywhere would return quietly wrong
 * neighbours rather than fail. The tests hold the two to producing identical results.
 */
internal object BlockDistance {

    /** Whether a quantization can be scanned from bytes without unpacking it first. */
    fun supports(quantization: Quantization): Boolean = quantization != Quantization.Binary

    /**
     * Writes into [out] the distance from [query] to each of [count] vectors packed in [block].
     *
     * Binary is not handled: its query path is a nibble lookup table built per query, which a
     * byte-reading copy could only duplicate exactly or silently disagree with — and its codes are
     * small enough that there is no footprint to reclaim by streaming them.
     */
    fun scan(
        query: FloatArray,
        block: ByteArray,
        count: Int,
        dimensions: Int,
        metric: Metric,
        quantization: Quantization,
        out: FloatArray,
    ) {
        when (quantization) {
            Quantization.None -> scanFloat32(query, block, count, dimensions, metric, out)
            Quantization.Int8 -> scanInt8(query, block, count, dimensions, metric, out)
            Quantization.Binary -> error("binary vectors are not scanned from bytes")
        }
    }

    private fun floatAt(bytes: ByteArray, at: Int): Float {
        val bits = ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)
        return Float.fromBits(bits)
    }

    private fun scanFloat32(
        query: FloatArray,
        block: ByteArray,
        count: Int,
        dimensions: Int,
        metric: Metric,
        out: FloatArray,
    ) {
        val stride = dimensions * 4
        for (i in 0 until count) {
            val base = i * stride
            out[i] = when (metric) {
                Metric.Cosine -> {
                    var dot = 0f
                    for (d in 0 until dimensions) dot += query[d] * floatAt(block, base + d * 4)
                    1f - dot
                }
                Metric.DotProduct -> {
                    var dot = 0f
                    for (d in 0 until dimensions) dot += query[d] * floatAt(block, base + d * 4)
                    -dot
                }
                Metric.Euclidean -> {
                    var acc = 0f
                    for (d in 0 until dimensions) {
                        val diff = query[d] - floatAt(block, base + d * 4)
                        acc += diff * diff
                    }
                    sqrt(acc)
                }
            }
        }
    }

    private fun scanInt8(
        query: FloatArray,
        block: ByteArray,
        count: Int,
        dimensions: Int,
        metric: Metric,
        out: FloatArray,
    ) {
        // Codes then the per-vector scale, exactly as the encoder lays them out.
        val stride = dimensions + 4
        for (i in 0 until count) {
            val base = i * stride
            val scale = floatAt(block, base + dimensions)
            out[i] = when (metric) {
                Metric.Cosine -> {
                    var acc = 0f
                    for (d in 0 until dimensions) acc += query[d] * block[base + d].toInt()
                    1f - acc * scale
                }
                Metric.DotProduct -> {
                    var acc = 0f
                    for (d in 0 until dimensions) acc += query[d] * block[base + d].toInt()
                    -acc * scale
                }
                Metric.Euclidean -> {
                    var acc = 0f
                    for (d in 0 until dimensions) {
                        val diff = query[d] - block[base + d].toInt() * scale
                        acc += diff * diff
                    }
                    sqrt(acc)
                }
            }
        }
    }
}
