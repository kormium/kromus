package io.github.kromus

/**
 * A minimal growable, big-endian byte writer — the zero-dependency serialization primitive behind
 * index persistence. Floats are stored via their raw IEEE-754 bits so a round trip is exact and
 * identical on every platform.
 */
internal class ByteWriter(
    initialCapacity: Int = 64,
) {
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

    /** Writes a length-prefixed blob. */
    fun bytes(value: ByteArray) {
        int(value.size)
        raw(value)
    }

    /** Appends bytes verbatim, with no length prefix. */
    fun raw(value: ByteArray) {
        ensure(value.size)
        value.copyInto(buf, pos)
        pos += value.size
    }
}

/**
 * Sequential big-endian reader; the exact inverse of [ByteWriter].
 *
 * Every read is bounds-checked and every count is sanity-checked against the bytes that remain.
 * Persisted indexes outlive the build that wrote them — cached on device, shipped as an asset, synced
 * through a store — so a decoder meets truncated and stale bytes in normal operation, and it owes the
 * caller a [KromusFormatException] it can act on rather than an index-out-of-bounds crash.
 */
internal class ByteReader(
    private val buf: ByteArray,
) {
    private var pos = 0

    val remaining: Int get() = buf.size - pos

    private fun need(n: Int) {
        if (n > remaining) {
            throw KromusFormatException(
                "truncated kromus index: need $n more byte(s) at offset $pos, $remaining left",
            )
        }
    }

    fun byte(): Int {
        need(1)
        return buf[pos++].toInt() and 0xFF
    }

    fun int(): Int {
        need(4)
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
        val n = count(1, "blob")
        val out = buf.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    /**
     * Reads an element count and rejects it if the remaining bytes could not possibly hold that many
     * elements of [minBytesEach] — which is what stops a corrupt length from being turned into a
     * multi-gigabyte allocation.
     */
    fun count(minBytesEach: Int, what: String): Int {
        val n = int()
        if (n < 0) throw KromusFormatException("corrupt kromus index: negative $what count $n at offset ${pos - 4}")
        if (n.toLong() * minBytesEach > remaining) {
            throw KromusFormatException(
                "corrupt kromus index: $what count $n needs at least ${n.toLong() * minBytesEach} " +
                    "byte(s) but only $remaining remain",
            )
        }
        return n
    }

    /** Reads an enum ordinal, rejecting values this build does not know. */
    fun <T> enumValue(values: List<T>, what: String): T {
        val ordinal = byte()
        if (ordinal >= values.size) {
            throw KromusFormatException("corrupt kromus index: unknown $what ordinal $ordinal")
        }
        return values[ordinal]
    }
}

// Every index starts with "KRMS", a kind byte and a format version, so a decoder can tell a foreign
// or stale blob from a truncated one and say which it is.
private const val MAGIC_0 = 0x4B // 'K'
private const val MAGIC_1 = 0x52 // 'R'
private const val MAGIC_2 = 0x4D // 'M'
private const val MAGIC_3 = 0x53 // 'S'

internal const val KIND_VECTOR: Int = 1
internal const val KIND_TEXT: Int = 2
internal const val KIND_HYBRID: Int = 3

private fun kindName(kind: Int): String =
    when (kind) {
        KIND_VECTOR -> "vector"
        KIND_TEXT -> "text"
        KIND_HYBRID -> "hybrid"
        else -> "unknown($kind)"
    }

internal fun ByteWriter.header(kind: Int, version: Int) {
    byte(MAGIC_0)
    byte(MAGIC_1)
    byte(MAGIC_2)
    byte(MAGIC_3)
    byte(kind)
    byte(version)
}

/** Verifies the magic, kind and version, or throws a [KromusFormatException] saying which failed. */
internal fun ByteReader.header(kind: Int, version: Int) {
    if (remaining < 6) {
        throw KromusFormatException("not a kromus index: only $remaining byte(s), need at least 6")
    }
    if (byte() != MAGIC_0 || byte() != MAGIC_1 || byte() != MAGIC_2 || byte() != MAGIC_3) {
        throw KromusFormatException(
            "not a kromus index: bad magic (an index written by kromus 0.14 or earlier has no magic " +
                "header and must be rebuilt)",
        )
    }
    val actualKind = byte()
    if (actualKind != kind) {
        throw KromusFormatException(
            "expected a kromus ${kindName(kind)} index, found a ${kindName(actualKind)} one",
        )
    }
    val actualVersion = byte()
    if (actualVersion != version) {
        throw KromusFormatException(
            "kromus ${kindName(kind)} index format v$actualVersion cannot be read by this build " +
                "(it reads v$version) — rebuild the index from your source data",
        )
    }
}

/**
 * Collects the strings an index repeats — attribute keys and values, and every indexed term — and
 * writes each exactly once, so records reference them by id.
 *
 * A term appears in the postings of every document that contains it, and attribute pairs like
 * `type=doc` repeat across the whole corpus; spelling them out per record is the bulk of a serialized
 * index. Ids are assigned in first-seen order over a deterministic record walk, so the pool (and with
 * it the whole file) is byte-identical for identical index content on every platform.
 */
internal class StringPoolWriter {
    private val ids = LinkedHashMap<String, Int>()

    fun idOf(value: String): Int = ids.getOrPut(value) { ids.size }

    fun writeTo(w: ByteWriter) {
        w.int(ids.size)
        for (value in ids.keys) w.bytes(value.encodeToByteArray())
    }
}

internal class StringPoolReader(
    r: ByteReader,
) {
    private val values: Array<String>

    init {
        val n = r.count(4, "string pool")
        values = Array(n) { r.bytes().decodeToString() }
    }

    fun get(id: Int): String {
        if (id < 0 || id >= values.size) {
            throw KromusFormatException("corrupt kromus index: string id $id outside a pool of ${values.size}")
        }
        return values[id]
    }
}
