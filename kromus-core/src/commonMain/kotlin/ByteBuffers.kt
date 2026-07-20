package io.github.kromus

/**
 * A minimal growable, big-endian byte writer — the zero-dependency serialization primitive behind
 * index persistence. Floats are stored via their raw IEEE-754 bits so a round trip is exact and
 * identical on every platform.
 */
internal class ByteWriter(initialCapacity: Int = 64) {
    private var buf = ByteArray(initialCapacity)
    private var pos = 0

    fun toByteArray(): ByteArray = buf.copyOf(pos)

    private fun ensure(extra: Int) {
        if (pos + extra > buf.size) {
            var size = buf.size * 2
            while (size < pos + extra) size *= 2
            buf = buf.copyOf(size)
        }
    }

    fun byte(value: Int) {
        ensure(1)
        buf[pos++] = value.toByte()
    }

    fun int(value: Int) {
        ensure(4)
        buf[pos++] = (value ushr 24).toByte()
        buf[pos++] = (value ushr 16).toByte()
        buf[pos++] = (value ushr 8).toByte()
        buf[pos++] = value.toByte()
    }

    fun long(value: Long) {
        int((value ushr 32).toInt())
        int(value.toInt())
    }

    fun float(value: Float) = int(value.toRawBits())

    fun bytes(value: ByteArray) {
        int(value.size)
        ensure(value.size)
        value.copyInto(buf, pos)
        pos += value.size
    }
}

/** Sequential big-endian reader; the exact inverse of [ByteWriter]. */
internal class ByteReader(private val buf: ByteArray) {
    private var pos = 0

    fun byte(): Int = buf[pos++].toInt() and 0xFF

    fun int(): Int {
        val v = ((buf[pos].toInt() and 0xFF) shl 24) or
            ((buf[pos + 1].toInt() and 0xFF) shl 16) or
            ((buf[pos + 2].toInt() and 0xFF) shl 8) or
            (buf[pos + 3].toInt() and 0xFF)
        pos += 4
        return v
    }

    fun long(): Long {
        val high = int().toLong() and 0xFFFFFFFFL
        val low = int().toLong() and 0xFFFFFFFFL
        return (high shl 32) or low
    }

    fun float(): Float = Float.fromBits(int())

    fun bytes(): ByteArray {
        val n = int()
        val out = buf.copyOfRange(pos, pos + n)
        pos += n
        return out
    }
}
