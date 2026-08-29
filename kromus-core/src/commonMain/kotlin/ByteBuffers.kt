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

    /**
     * Bytes written so far.
     *
     * Named at length on purpose: this class is used as a lambda receiver, where a short name like
     * `size` silently shadows whatever the enclosing scope calls `size` — and produces an empty
     * section rather than a compile error.
     */
    val bytesWritten: Int get() = pos

    /** Two bytes, unsigned: 0..65535. */
    fun short(value: Int) {
        require(value in 0..0xFFFF) { "short out of range: $value" }
        ensure(2)
        buf[pos++] = (value ushr 8).toByte()
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
    private val start: Int = 0,
    length: Int = buf.size - start,
) {
    private var pos = start
    private val end = start + length

    val remaining: Int get() = end - pos

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

    /** Two bytes, unsigned: 0..65535, which is why a two-byte field holds twice what a signed one would. */
    fun short(): Int {
        need(2)
        val v = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
        pos += 2
        return v
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

    /**
     * Rejects a node level that the remaining bytes could not possibly describe.
     *
     * A level is not read through [count], but it is a count in disguise: `level + 1` adjacency lists
     * follow it, each costing at least its own four-byte length. Without this a corrupt level of two
     * billion is merely "not negative", and the `Array(level + 1)` that follows exhausts the heap
     * before any other check runs — the exact failure [count] exists to prevent, reached by a field
     * that did not go through it.
     */
    fun level(id: Int): Int {
        val level = int()
        if (level < 0) throw KromusFormatException("corrupt kromus index: negative level $level for node $id")
        val needed = (level.toLong() + 1) * 4
        if (needed > remaining) {
            throw KromusFormatException(
                "corrupt kromus index: node $id claims level $level, whose ${level + 1} layer(s) need " +
                    "at least $needed byte(s) but only $remaining remain",
            )
        }
        return level
    }

    /**
     * A two-byte count, bounded against the bytes that remain exactly as [count] is.
     *
     * Same guard, narrower field: a short count cannot be negative, so only the "could not possibly
     * fit" half of the check applies.
     */
    fun shortCount(minBytesEach: Int, what: String): Int {
        val n = short()
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

/**
 * FNV-1a over a byte range: the checksum a container records per section, and the revision a delta
 * chains from. Integer arithmetic only — no dependency, identical on every target.
 */
internal fun checksumOf(bytes: ByteArray, from: Int = 0, length: Int = bytes.size - from): Long {
    var h = -0x340d631b7bdddcdbL // 14695981039346656037
    for (i in from until from + length) {
        h = h xor (bytes[i].toLong() and 0xFF)
        h *= 0x100000001b3L
    }
    return h
}

/**
 * Writes an optional provenance string. Present or absent is one byte, so "no provenance" and
 * "provenance is the empty string" stay distinguishable.
 */
internal fun ByteWriter.provenance(value: String?) {
    if (value == null) {
        byte(0)
    } else {
        byte(1)
        bytes(value.encodeToByteArray())
    }
}

/**
 * Reads the stored provenance and, when the caller said what it expects, refuses a mismatch.
 *
 * This is the guard against the failure that persistence cannot catch on its own: an index is only
 * meaningful together with whatever produced its vectors and tokenized its text, and neither an
 * embedding model nor an analyzer is part of the bytes. Query a corpus embedded by one model with
 * vectors from another and nothing throws — the results are simply wrong, quietly, forever. That is
 * the whole reason to make the caller name what it is expecting.
 */
internal fun ByteReader.provenance(expected: String?): String? {
    val stored = if (byte() == 1) bytes().decodeToString() else null
    if (expected != null && stored != expected) {
        throw KromusFormatException(
            "kromus index provenance mismatch: it was built as " +
                (stored?.let { "'$it'" } ?: "(none recorded)") +
                " but '$expected' was expected — searching it with a different embedding model or " +
                "analyzer returns wrong results rather than failing, so this is refused",
        )
    }
    return stored
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
