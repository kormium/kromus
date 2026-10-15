package io.github.kromus

// The container every kromus index is written in.
//
// It knows nothing about what an index contains: a magic, a kind, a format version, an optional
// provenance string, and a table of named sections. What sections exist and what is in them is the
// business of the kind — which is what lets a second kind of index (a clustered one, say) reuse all of
// this without the container growing a single conditional.
//
// Sections earn their place three times over:
//
//  - **Density.** A section is homogeneous, so its fields can be sized for what they hold rather than
//    for the widest thing nearby. Interleaved records force everything to the common denominator.
//  - **Diagnosis.** The table says what a file is made of without parsing it, and each section carries
//    a checksum, so corruption is located rather than merely detected. Tags are four ASCII characters
//    so a hex dump is readable by eye.
//  - **Partial reads.** A section is a contiguous range. The graph can be read without touching the
//    vectors; a fixed-stride vector section can be addressed arithmetically instead of walked.
//
// Every integer here is unsigned. Widths are chosen for the values they hold, and reading them as
// unsigned is what makes a two-byte field hold 65 536 values instead of 32 768 — the difference
// between a two-byte and a four-byte neighbour link for most indexes anyone ships.

private const val MAGIC_0 = 0x4B // 'K'
private const val MAGIC_1 = 0x52 // 'R'
private const val MAGIC_2 = 0x4D // 'M'
private const val MAGIC_3 = 0x53 // 'S'

/** Bytes of header before the section table: magic, kind, version, flags. */
private const val FIXED_HEADER = 8

/** tag(4) + offset(4) + length(4) + checksum(8). */
private const val TABLE_ENTRY = 20

/**
 * Kinds the library itself writes. A third-party index picks a byte outside this range — the reader
 * only checks that the kind it was asked for is the kind it found, so the numbering is a convention
 * rather than a registry.
 */
internal const val KIND_VECTOR: Int = 1
internal const val KIND_TEXT: Int = 2
internal const val KIND_HYBRID: Int = 3

// Deltas carry their own kinds, so feeding a snapshot where a delta belongs — or the reverse — is
// reported as the mix-up it is rather than as a version mismatch.
internal const val KIND_VECTOR_DELTA: Int = 4
internal const val KIND_TEXT_DELTA: Int = 5
internal const val KIND_HYBRID_DELTA: Int = 6
internal const val KIND_IVF: Int = 7
internal const val KIND_FLAT: Int = 8

public fun kindName(kind: Int): String =
    when (kind) {
        KIND_VECTOR -> "vector"
        KIND_TEXT -> "text"
        KIND_HYBRID -> "hybrid"
        KIND_VECTOR_DELTA -> "vector delta"
        KIND_TEXT_DELTA -> "text delta"
        KIND_HYBRID_DELTA -> "hybrid delta"
        KIND_IVF -> "ivf"
        KIND_FLAT -> "flat"
        else -> "unknown($kind)"
    }

/** Assembles a container. Sections are emitted in the order they are added, which keeps bytes stable. */
public class ContainerWriter(
    private val kind: Int,
    private val version: Int,
    private val provenance: String?,
) {
    private val tags = ArrayList<String>()
    private val bodies = ArrayList<ByteArray>()

    public fun section(tag: String, build: ByteWriter.() -> Unit) {
        require(tag.length == 4) { "a section tag is four characters, was '$tag'" }
        val w = ByteWriter()
        w.build()
        tags.add(tag)
        bodies.add(w.toByteArray())
    }

    public fun toByteArray(): ByteArray {
        val w = ByteWriter()
        w.byte(MAGIC_0)
        w.byte(MAGIC_1)
        w.byte(MAGIC_2)
        w.byte(MAGIC_3)
        w.byte(kind)
        w.byte(version)
        w.short(0) // flags, reserved
        w.provenance(provenance)
        w.short(tags.size)

        // Offsets are absolute, so a reader can jump straight to a section without walking the ones
        // before it — the whole point of having a table.
        var offset = w.bytesWritten + tags.size * TABLE_ENTRY
        for (i in tags.indices) {
            for (c in tags[i]) w.byte(c.code)
            w.int(offset)
            w.int(bodies[i].size)
            w.long(checksumOf(bodies[i]))
            offset += bodies[i].size
        }
        for (body in bodies) w.raw(body)
        return w.toByteArray()
    }
}

/**
 * How many bytes to ask a source for before the header's real length is known.
 *
 * The header is a fixed part, an optional provenance string of any length, and a table sized by the
 * section count — so its length cannot be computed until some of it has been read. One generous probe
 * covers every index the library writes; a longer provenance costs one more read, not a guess.
 */
private const val HEADER_PROBE = 512

/**
 * Total header bytes, or null when [available] is not yet enough to tell.
 *
 * Parsed without a [ByteReader] so that "not enough yet" is a return value rather than an exception —
 * this runs in a loop that grows the probe, and control flow through a format error would hide a real
 * one.
 */
private fun headerLength(p: ByteArray, available: Int): Int? {
    if (available < FIXED_HEADER + 1) return null
    var at = FIXED_HEADER
    val present = p[at].toInt() and 0xFF
    at += 1
    if (present == 1) {
        if (available < at + 4) return null
        val n = ((p[at].toInt() and 0xFF) shl 24) or ((p[at + 1].toInt() and 0xFF) shl 16) or
            ((p[at + 2].toInt() and 0xFF) shl 8) or (p[at + 3].toInt() and 0xFF)
        if (n < 0) throw KromusFormatException("corrupt kromus index: provenance claims $n byte(s)")
        at += 4 + n
        if (at < 0) throw KromusFormatException("corrupt kromus index: header overflows")
    }
    if (available < at + 2) return null
    val count = ((p[at].toInt() and 0xFF) shl 8) or (p[at + 1].toInt() and 0xFF)
    return at + 2 + count * TABLE_ENTRY
}

/** Reads just the header of [source], growing the probe until the whole of it is in hand. */
private fun headerOf(source: ByteSource): ByteArray {
    var want = if (source.size < HEADER_PROBE) source.size else HEADER_PROBE
    if (want < FIXED_HEADER) {
        throw KromusFormatException("not a kromus index: only ${source.size} byte(s), need at least $FIXED_HEADER")
    }
    while (true) {
        val buf = ByteArray(want)
        source.read(0, want, buf)
        val need = headerLength(buf, want)
        if (need != null && need <= want) return if (need == want) buf else buf.copyOf(need)
        val next = need ?: (want * 2)
        if (next > source.size || next <= want) {
            throw KromusFormatException(
                "truncated kromus index: its header needs $next byte(s), the source holds ${source.size}",
            )
        }
        want = next
    }
}

/**
 * Reads a container: verifies the header, then hands out sections by tag.
 *
 * Two ways in, one implementation. Given a [ByteArray] the whole file is already in hand, and a
 * section is handed out as a window onto it — nothing is copied. Given a [ByteSource] only the header
 * is read up front, and a section is read from the source when it is asked for, which is what lets an
 * index leave the bulk of a file where it is. Both parse the same header the same way, because a
 * second parser that drifted would accept a file the first rejects.
 */
public class ContainerReader private constructor(
    /**
     * Where this container reads from, so a caller can address a section without copying it.
     *
     * This is how an index leaves its vectors where they are: take the offset and length of the vector
     * section, [ByteSource.slice] them, and read through that instead of inflating the section.
     */
    public val source: ByteSource,
    // Set only when the caller already had the whole file as an array: lets a section be a window
    // rather than a copy. Not part of ByteSource — a source is not obliged to be an array, and the
    // seam should not carry an optimization that only one implementation can satisfy.
    private val whole: ByteArray?,
    kind: Int,
    version: Int,
    expect: String?,
) {
    private val offsets = HashMap<String, Int>()
    private val lengths = HashMap<String, Int>()
    private val checksums = HashMap<String, Long>()
    private val verified = HashSet<String>()

    public val provenance: String?

    /** Reads a container held entirely in memory. */
    public constructor(bytes: ByteArray, kind: Int, version: Int, expect: String? = null) :
        this(ByteArraySource(bytes), bytes, kind, version, expect)

    /** Reads a container from [source], touching only its header until a section is asked for. */
    public constructor(source: ByteSource, kind: Int, version: Int, expect: String? = null) :
        this(source, null, kind, version, expect)

    init {
        val header = whole ?: headerOf(source)
        val r = ByteReader(header)
        if (r.remaining < FIXED_HEADER) {
            throw KromusFormatException("not a kromus index: only ${r.remaining} byte(s), need at least $FIXED_HEADER")
        }
        if (r.byte() != MAGIC_0 || r.byte() != MAGIC_1 || r.byte() != MAGIC_2 || r.byte() != MAGIC_3) {
            throw KromusFormatException(
                "not a kromus index: bad magic (anything written by kromus 0.15 or earlier has a " +
                    "different layout and must be rebuilt)",
            )
        }
        val actualKind = r.byte()
        if (actualKind != kind) {
            throw KromusFormatException(
                "expected a kromus ${kindName(kind)} index, found a ${kindName(actualKind)} one",
            )
        }
        val actualVersion = r.byte()
        if (actualVersion != version) {
            throw KromusFormatException(
                "kromus ${kindName(kind)} index format v$actualVersion cannot be read by this build " +
                    "(it reads v$version) — rebuild the index from your source data",
            )
        }
        val flags = r.short()
        if (flags != 0) throw KromusFormatException("kromus index sets unknown flags $flags")
        provenance = r.provenance(expect)

        val count = r.short()
        for (i in 0 until count) {
            val tag = buildString(4) { repeat(4) { append(r.byte().toChar()) } }
            val offset = r.int()
            val length = r.int()
            val checksum = r.long()
            if (offset < 0 || length < 0 || offset.toLong() + length > source.size) {
                throw KromusFormatException(
                    "corrupt kromus index: section '$tag' claims $length byte(s) at $offset, " +
                        "outside a ${source.size}-byte file",
                )
            }
            offsets[tag] = offset
            lengths[tag] = length
            checksums[tag] = checksum
        }
    }

    public fun has(tag: String): Boolean = tag in offsets

    public fun lengthOf(tag: String): Int =
        lengths[tag] ?: throw KromusFormatException("kromus index is missing its '$tag' section")

    public fun offsetOf(tag: String): Int =
        offsets[tag] ?: throw KromusFormatException("kromus index is missing its '$tag' section")

    /** A section's bytes as their own array — for a section that is itself a nested container. */
    public fun sectionBytes(tag: String): ByteArray {
        val offset = offsetOf(tag)
        val length = lengthOf(tag)
        if (whole != null) {
            verify(tag, whole, offset, length, offset)
            return whole.copyOfRange(offset, offset + length)
        }
        return read(tag, offset, length)
    }

    /**
     * A reader over one section, its contents checked against the checksum recorded for it.
     *
     * Verifying here rather than up front means a reader that never touches a section never pays for
     * it — which is what makes reading a graph without its vectors actually cheaper, rather than
     * cheaper except for the checksum pass.
     */
    public fun section(tag: String): ByteReader {
        val offset = offsetOf(tag)
        val length = lengthOf(tag)
        if (whole != null) {
            verify(tag, whole, offset, length, offset)
            return ByteReader(whole, offset, length)
        }
        return ByteReader(read(tag, offset, length))
    }

    private fun read(tag: String, offset: Int, length: Int): ByteArray {
        val buf = ByteArray(length)
        source.read(offset, length, buf)
        verify(tag, buf, 0, length, offset)
        return buf
    }

    // A section held in the whole-file array is the same bytes on every call, so verifying it once is
    // enough. One read from a source is not the same bytes as the next, so that path always verifies —
    // it just paid for a read, and the checksum over what it read is the cheap half.
    private fun verify(tag: String, buf: ByteArray, from: Int, length: Int, reportedAt: Int) {
        if (whole != null && !verified.add(tag)) return
        val actual = checksumOf(buf, from, length)
        if (actual != checksums[tag]) {
            throw KromusFormatException(
                "corrupt kromus index: section '$tag' does not match its checksum — " +
                    "$length byte(s) at $reportedAt have been altered or truncated in transit",
            )
        }
    }
}

// --- deltas ---
//
// A delta is a change log, not an index: a short sequence of records that only means anything applied
// to the snapshot it names. It gets the same magic and the same kind check — feeding one where a
// snapshot belongs must say so — but no section table. There is nothing to address independently in a
// stream that is replayed start to finish, and a table over a few kilobytes would cost more than it
// tells anyone.

public fun ByteWriter.deltaHeader(kind: Int, version: Int) {
    byte(MAGIC_0)
    byte(MAGIC_1)
    byte(MAGIC_2)
    byte(MAGIC_3)
    byte(kind)
    byte(version)
}

public fun ByteReader.deltaHeader(kind: Int, version: Int) {
    if (remaining < 6) {
        throw KromusFormatException("not a kromus delta: only $remaining byte(s), need at least 6")
    }
    if (byte() != MAGIC_0 || byte() != MAGIC_1 || byte() != MAGIC_2 || byte() != MAGIC_3) {
        throw KromusFormatException("not a kromus delta: bad magic")
    }
    val actualKind = byte()
    if (actualKind != kind) {
        throw KromusFormatException(
            "expected a kromus ${kindName(kind)}, found a ${kindName(actualKind)}",
        )
    }
    val actualVersion = byte()
    if (actualVersion != version) {
        throw KromusFormatException(
            "kromus ${kindName(kind)} format v$actualVersion cannot be read by this build " +
                "(it reads v$version) — rebuild from your source data",
        )
    }
}
