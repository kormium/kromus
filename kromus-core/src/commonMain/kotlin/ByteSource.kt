package io.github.kromus

/**
 * Bytes that can be read at any offset — where an index reads from when it does not hold its own.
 *
 * This is the seam between kromus and wherever an index actually lives. The library ships one
 * implementation, [ByteArraySource], which reads from an array already in memory; `kromus-files`
 * ships the ones backed by a file on each platform. Anything else — a decrypting reader, an Android
 * `AssetFileDescriptor`, a virtual file system, a cache that fetches and spills to local storage —
 * is this interface and nothing more.
 *
 * **Reads are synchronous, and that is deliberate.** A distance scan is synchronous arithmetic; making
 * the read `suspend` would push coroutines through the whole search path and into a module that has
 * no dependencies at all. The cost is that a source which can only be read asynchronously — a network
 * fetch, or the browser's file APIs outside a worker — has to land in memory or on local storage
 * first.
 *
 * **[read] must fill exactly what was asked for.** Returning early is the one failure this interface
 * cannot detect: a partly-filled buffer leaves whatever was there before in the tail, and the scan
 * reads it as vector data. Nothing throws, nothing is logged, and the query returns neighbours that
 * are merely plausible. An implementation over an API that may return fewer bytes than requested —
 * most stream and channel APIs do — must loop until the range is complete, and throw if it cannot be.
 *
 * Implementations need not be thread-safe: a searcher takes one, and searchers are per-thread. An
 * implementation whose reads *are* independent may be shared by several.
 */
public interface ByteSource {
    /** Total bytes readable from this source. */
    public val size: Int

    /**
     * Copies exactly [length] bytes starting at [offset] into [into], starting at [at].
     *
     * @throws KromusFormatException if the range lies outside the source or cannot be read in full.
     */
    public fun read(offset: Int, length: Int, into: ByteArray, at: Int = 0)

    /** Releases whatever the source holds. Idempotent, and safe to call on a source never read. */
    public fun close() {}
}

/**
 * A window onto part of a source, addressed from zero.
 *
 * An index reads its vectors through one of these: the vector section sits at some offset inside a
 * container, and the search path should not have to know where. Closing a slice does not close what
 * it was taken from — the container owns that.
 */
public fun ByteSource.slice(from: Int, count: Int): ByteSource {
    require(from >= 0 && count >= 0 && from.toLong() + count <= size) {
        "slice $from..${from + count} lies outside a $size-byte source"
    }
    val outer = this
    return object : ByteSource {
        override val size: Int get() = count

        override fun read(offset: Int, length: Int, into: ByteArray, at: Int) {
            checkRange(offset, length, count)
            outer.read(from + offset, length, into, at)
        }
    }
}

/** Vectors or a whole container already in memory — the ordinary case, and the fallback everywhere. */
public class ByteArraySource(
    private val bytes: ByteArray,
    private val base: Int = 0,
    override val size: Int = bytes.size - base,
) : ByteSource {
    init {
        require(base >= 0 && size >= 0 && base.toLong() + size <= bytes.size) {
            "window $base..${base + size} lies outside a ${bytes.size}-byte array"
        }
    }

    override fun read(offset: Int, length: Int, into: ByteArray, at: Int) {
        checkRange(offset, length, size)
        bytes.copyInto(into, at, base + offset, base + offset + length)
    }
}

/**
 * The bounds check every source owes its caller, in one place.
 *
 * A source is handed offsets that came out of a file's own section table, so a corrupt table is the
 * expected way for a range to be wrong — which makes this a format error rather than an argument
 * error.
 */
public fun checkRange(offset: Int, length: Int, size: Int) {
    if (offset < 0 || length < 0 || offset.toLong() + length > size) {
        throw KromusFormatException(
            "corrupt kromus index: a read of $length byte(s) at $offset lies outside a $size-byte source",
        )
    }
}
