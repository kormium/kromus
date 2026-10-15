package io.github.kromus.files

import io.github.kromus.ByteSource
import io.github.kromus.KromusFormatException
import io.github.kromus.checkRange
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * An index's bytes read from a file, a range at a time.
 *
 * Reads go through positional [FileChannel.read], which never touches the channel's own position —
 * so one source can serve several searchers at once, and nothing has to be synchronized. Mapping the
 * file instead was the other option; it was not taken because a mapping's lifetime on the JVM is not
 * the caller's to end, and an index whose file is replaced or deleted underneath a live mapping fails
 * in ways that are hard to attribute. A positional read costs one syscall per cluster, against a page
 * fault per page — a difference this access pattern does not notice.
 *
 * The file is opened read-only and stays open until [close]. An index built on this source stops
 * working the moment it is closed, so close it when the index is done, not when the loading is.
 *
 * On **Android**, note that an asset packed in an APK is compressed by default and cannot be read at
 * an offset. Either mark it `noCompress` and open it through the `AssetFileDescriptor`'s own offset
 * and length with [openRange], or copy it into `filesDir` once on first run. Copying costs the space
 * twice; not copying costs nothing and is why an index is worth shipping this way at all.
 *
 * @throws KromusFormatException if the path cannot be opened or the range lies outside the file.
 */
public class FileByteSource private constructor(
    private val channel: FileChannel,
    private val base: Long,
    override val size: Int,
) : ByteSource {
    private var closed = false

    override fun read(offset: Int, length: Int, into: ByteArray, at: Int) {
        checkRange(offset, length, size)
        if (closed) throw KromusFormatException("this kromus file source is closed")
        if (length == 0) return
        val buffer = ByteBuffer.wrap(into, at, length)
        var position = base + offset
        // A channel may return fewer bytes than asked for. Looping is not a nicety here: a partly
        // filled buffer is read as vector data, and the query comes back merely plausible.
        while (buffer.hasRemaining()) {
            val read = try {
                channel.read(buffer, position)
            } catch (e: IOException) {
                throw KromusFormatException("reading $length byte(s) at $offset failed: ${e.message}")
            }
            if (read < 0) {
                throw KromusFormatException(
                    "truncated kromus index: $length byte(s) at $offset run past the end of the file",
                )
            }
            position += read.toLong()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { channel.close() }
    }

    public companion object {
        /** Opens the whole of [path]. */
        public fun open(path: String): FileByteSource = open(File(path))

        /** Opens the whole of [file]. */
        public fun open(file: File): FileByteSource {
            val channel = channelOf(file)
            val length = try {
                channel.size()
            } catch (e: IOException) {
                channel.close()
                throw KromusFormatException("cannot measure '${file.path}': ${e.message}")
            }
            if (length > Int.MAX_VALUE) {
                channel.close()
                throw KromusFormatException(
                    "'${file.path}' is $length byte(s); a kromus container addresses at most ${Int.MAX_VALUE}",
                )
            }
            return FileByteSource(channel, 0, length.toInt())
        }

        /**
         * Opens [length] bytes of [path] starting at [offset] — an index packed inside a larger file.
         *
         * This is the shape an Android asset arrives in: `AssetFileDescriptor` gives the APK's path
         * together with the offset and length of the entry inside it, and an uncompressed entry can be
         * read straight from there with no copy.
         */
        public fun openRange(path: String, offset: Long, length: Int): FileByteSource {
            require(offset >= 0) { "offset must be >= 0, was $offset" }
            require(length >= 0) { "length must be >= 0, was $length" }
            val file = File(path)
            val channel = channelOf(file)
            val total = try {
                channel.size()
            } catch (e: IOException) {
                channel.close()
                throw KromusFormatException("cannot measure '$path': ${e.message}")
            }
            if (offset + length > total) {
                channel.close()
                throw KromusFormatException(
                    "'$path' holds $total byte(s); the range $offset..${offset + length} runs past its end",
                )
            }
            return FileByteSource(channel, offset, length)
        }

        private fun channelOf(file: File): FileChannel =
            try {
                RandomAccessFile(file, "r").channel
            } catch (e: IOException) {
                throw KromusFormatException("cannot open '${file.path}' for reading: ${e.message}")
            }
    }
}
