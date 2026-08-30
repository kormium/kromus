package io.github.kromus.files

import io.github.kromus.ByteSource
import io.github.kromus.KromusFormatException
import io.github.kromus.checkRange
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.FILE
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.feof
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

/**
 * An index's bytes read from a file, a range at a time.
 *
 * Buffered stdio rather than `mmap`: `fopen`/`fseek`/`fread` are the same three calls on Linux,
 * macOS, iOS and Windows, where mapping is `mmap` on three of them and `CreateFileMapping` on the
 * fourth. One implementation that behaves identically everywhere is worth more here than a page
 * fault saved per cluster — and the pattern this serves is one sequential run per probed cluster,
 * which is what stdio buffering is for.
 *
 * The file is opened read-only and stays open until [close]. An index built on this source stops
 * working the moment it is closed.
 *
 * Not thread-safe: a read seeks and then reads, and two threads sharing one handle would interleave
 * the two halves. Open one source per thread, or serialize access — a searcher is per-thread anyway.
 *
 * `UnsafeNumber` is opted into because a C long is 64-bit on Unix and 32-bit on Windows, so the
 * commonized `fseek`/`ftell` have different widths per target. Nothing here lets one escape: a
 * container addresses at most `Int.MAX_VALUE` bytes, which fits the narrower of the two.
 *
 * @throws KromusFormatException if the path cannot be opened or the range lies outside the file.
 */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
public class FileByteSource private constructor(
    private var handle: CPointer<FILE>?,
    private val base: Long,
    override val size: Int,
    private val path: String,
) : ByteSource {

    override fun read(offset: Int, length: Int, into: ByteArray, at: Int) {
        checkRange(offset, length, size)
        val file = handle ?: throw KromusFormatException("this kromus file source is closed")
        if (length == 0) return
        // convert() rather than a fixed width: a C long is 64-bit on Unix and 32-bit on Windows, and
        // a container is bounded to Int.MAX_VALUE bytes, so either holds every offset we can produce.
        if (fseek(file, (base + offset).convert(), SEEK_SET) != 0) {
            throw KromusFormatException("cannot seek to ${base + offset} in '$path'")
        }
        into.usePinned { pinned ->
            var done = 0
            // fread stops short at end of file and on error alike, so a partial read is checked
            // rather than assumed away: a half-filled buffer is read as vector data downstream.
            while (done < length) {
                val got = fread(pinned.addressOf(at + done), 1.convert(), (length - done).convert(), file).toInt()
                if (got <= 0) {
                    val why = if (feof(file) != 0) "the file ends early" else "the read failed"
                    throw KromusFormatException(
                        "truncated kromus index: $length byte(s) at $offset could not be read from " +
                            "'$path' — $why after $done byte(s)",
                    )
                }
                done += got
            }
        }
    }

    override fun close() {
        val file = handle ?: return
        handle = null
        fclose(file)
    }

    public companion object {
        /** Opens the whole of [path]. */
        public fun open(path: String): FileByteSource {
            val file = fopen(path, "rb")
                ?: throw KromusFormatException("cannot open '$path' for reading")
            if (fseek(file, 0.convert(), SEEK_END) != 0) {
                fclose(file)
                throw KromusFormatException("cannot measure '$path'")
            }
            // convert() rather than toLong(): ftell is a C long, which is already 64-bit on Unix and
            // 32-bit on Windows, so a fixed conversion is redundant on one and required on the other.
            val length = ftell(file).convert<Long>()
            if (length < 0) {
                fclose(file)
                throw KromusFormatException("cannot measure '$path'")
            }
            if (length > Int.MAX_VALUE.toLong()) {
                fclose(file)
                throw KromusFormatException(
                    "'$path' is $length byte(s); a kromus container addresses at most ${Int.MAX_VALUE}",
                )
            }
            return FileByteSource(file, 0, length.toInt(), path)
        }

        /** Opens [length] bytes of [path] starting at [offset] — an index packed inside a larger file. */
        public fun openRange(path: String, offset: Long, length: Int): FileByteSource {
            require(offset >= 0) { "offset must be >= 0, was $offset" }
            require(length >= 0) { "length must be >= 0, was $length" }
            val whole = open(path)
            if (offset + length > whole.size.toLong()) {
                whole.close()
                throw KromusFormatException(
                    "'$path' holds ${whole.size} byte(s); the range $offset..${offset + length} runs past its end",
                )
            }
            val file = whole.handle
            whole.handle = null // hand the open handle to the ranged source rather than reopening
            return FileByteSource(file, offset, length, path)
        }
    }
}
