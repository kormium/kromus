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

internal const val KIND_VECTOR: Int = 1
internal const val KIND_TEXT: Int = 2
internal const val KIND_HYBRID: Int = 3

// Deltas carry their own kinds, so feeding a snapshot where a delta belongs — or the reverse — is
// reported as the mix-up it is rather than as a version mismatch.
internal const val KIND_VECTOR_DELTA: Int = 4
internal const val KIND_TEXT_DELTA: Int = 5
internal const val KIND_HYBRID_DELTA: Int = 6
internal const val KIND_CLUSTERED: Int = 7

internal fun kindName(kind: Int): String =
    when (kind) {
        KIND_VECTOR -> "vector"
        KIND_TEXT -> "text"
        KIND_HYBRID -> "hybrid"
        KIND_VECTOR_DELTA -> "vector delta"
        KIND_TEXT_DELTA -> "text delta"
        KIND_HYBRID_DELTA -> "hybrid delta"
        KIND_CLUSTERED -> "clustered"
        else -> "unknown($kind)"
    }

/** Assembles a container. Sections are emitted in the order they are added, which keeps bytes stable. */
internal class ContainerWriter(
    private val kind: Int,
    private val version: Int,
    private val provenance: String?,
) {
    private val tags = ArrayList<String>()
    private val bodies = ArrayList<ByteArray>()

    fun section(tag: String, build: ByteWriter.() -> Unit) {
        require(tag.length == 4) { "a section tag is four characters, was '$tag'" }
        val w = ByteWriter()
        w.build()
        tags.add(tag)
        bodies.add(w.toByteArray())
    }

    fun toByteArray(): ByteArray {
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

/** Reads a container: verifies the header, then hands out sections by tag. */
internal class ContainerReader(
    private val bytes: ByteArray,
    kind: Int,
    version: Int,
    expect: String?,
) {
    private val offsets = HashMap<String, Int>()
    private val lengths = HashMap<String, Int>()
    private val checksums = HashMap<String, Long>()
    private val verified = HashSet<String>()

    val provenance: String?

    init {
        val r = ByteReader(bytes)
        if (r.remaining < FIXED_HEADER) {
            throw KromusFormatException("not a kromus index: only ${r.remaining} byte(s), need at least $FIXED_HEADER")
        }
        if (r.byte() != MAGIC_0 || r.byte() != MAGIC_1 || r.byte() != MAGIC_2 || r.byte() != MAGIC_3) {
            throw KromusFormatException(
                "not a kromus index: bad magic (anything written by kromus 0.16 or earlier has a " +
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
            if (offset < 0 || length < 0 || offset.toLong() + length > bytes.size) {
                throw KromusFormatException(
                    "corrupt kromus index: section '$tag' claims $length byte(s) at $offset, " +
                        "outside a ${bytes.size}-byte file",
                )
            }
            offsets[tag] = offset
            lengths[tag] = length
            checksums[tag] = checksum
        }
    }

    fun has(tag: String): Boolean = tag in offsets

    fun lengthOf(tag: String): Int =
        lengths[tag] ?: throw KromusFormatException("kromus index is missing its '$tag' section")

    fun offsetOf(tag: String): Int =
        offsets[tag] ?: throw KromusFormatException("kromus index is missing its '$tag' section")

    /** A section's bytes as their own array — for a section that is itself a nested container. */
    fun sectionBytes(tag: String): ByteArray {
        val offset = offsetOf(tag)
        val length = lengthOf(tag)
        section(tag) // verifies the checksum
        return bytes.copyOfRange(offset, offset + length)
    }

    /**
     * A reader over one section, its contents checked against the checksum recorded for it.
     *
     * Verifying here rather than up front means a reader that never touches a section never pays for
     * it — which is what makes reading a graph without its vectors actually cheaper, rather than
     * cheaper except for the checksum pass.
     */
    fun section(tag: String): ByteReader {
        val offset = offsetOf(tag)
        val length = lengthOf(tag)
        if (verified.add(tag)) {
            val actual = checksumOf(bytes, offset, length)
            if (actual != checksums[tag]) {
                throw KromusFormatException(
                    "corrupt kromus index: section '$tag' does not match its checksum — " +
                        "$length byte(s) at $offset have been altered or truncated in transit",
                )
            }
        }
        return ByteReader(bytes, offset, length)
    }
}

// --- deltas ---
//
// A delta is a change log, not an index: a short sequence of records that only means anything applied
// to the snapshot it names. It gets the same magic and the same kind check — feeding one where a
// snapshot belongs must say so — but no section table. There is nothing to address independently in a
// stream that is replayed start to finish, and a table over a few kilobytes would cost more than it
// tells anyone.

internal fun ByteWriter.deltaHeader(kind: Int, version: Int) {
    byte(MAGIC_0)
    byte(MAGIC_1)
    byte(MAGIC_2)
    byte(MAGIC_3)
    byte(kind)
    byte(version)
}

internal fun ByteReader.deltaHeader(kind: Int, version: Int) {
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
