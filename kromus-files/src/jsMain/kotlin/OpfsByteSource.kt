package io.github.kromus.files

import io.github.kromus.ByteSource
import io.github.kromus.KromusFormatException
import io.github.kromus.checkRange
import org.khronos.webgl.Int8Array

/**
 * The synchronous half of the Origin Private File System.
 *
 * Declared here rather than taken from a Kotlin wrapper because the handle is the only part of OPFS
 * this module touches: everything asynchronous — asking for the directory, opening the file — belongs
 * to the caller, who is the one that knows where the index lives.
 */
public external interface FileSystemSyncAccessHandle {
    public fun read(buffer: Int8Array, options: OpfsReadOptions): Double

    public fun getSize(): Double

    public fun close()
}

/** Where in the file a synchronous OPFS read starts. */
public external interface OpfsReadOptions {
    public var at: Double
}

private fun readAt(position: Int): OpfsReadOptions {
    val options = js("{}").unsafeCast<OpfsReadOptions>()
    options.at = position.toDouble()
    return options
}

/**
 * An index's bytes read from a file in the browser's Origin Private File System.
 *
 * **This only works inside a Web Worker.** `createSyncAccessHandle` is the one file API in the
 * browser that reads synchronously, and it is not exposed on the main thread — which is a constraint
 * of the platform, not a choice made here. A search on a file-backed index therefore runs in a worker
 * and posts its results back, which is where it belongs anyway: a scan over thousands of vectors is
 * not something to run on the thread that draws the interface.
 *
 * The handle is obtained asynchronously, and this class deliberately takes an already-open one rather
 * than opening it — so that [ByteSource] can stay synchronous, and so that nothing here has an opinion
 * about where your index is stored:
 *
 * ```kotlin
 * // inside a worker
 * val root = navigator.storage.getDirectory().await()
 * val file = root.getFileHandle("index.krm").await()
 * val handle = file.createSyncAccessHandle().await()
 * val index = openIvfIndex(OpfsByteSource.open(handle), KeyCodec.string)
 * ```
 *
 * A sync access handle takes an exclusive lock on the file for as long as it is open, so [close] it
 * when the index is finished with — and note that closing it invalidates the index.
 */
public class OpfsByteSource private constructor(
    private var handle: FileSystemSyncAccessHandle?,
    private val base: Int,
    override val size: Int,
) : ByteSource {

    override fun read(offset: Int, length: Int, into: ByteArray, at: Int) {
        checkRange(offset, length, size)
        val open = handle ?: throw KromusFormatException("this kromus OPFS source is closed")
        if (length == 0) return
        // A ByteArray is an Int8Array at runtime, so a subarray is a view onto the caller's buffer and
        // the read lands in it directly, with nothing copied afterwards.
        val view = into.unsafeCast<Int8Array>().subarray(at, at + length)
        var done = 0
        while (done < length) {
            val got = open.read(view.subarray(done, length), readAt(base + offset + done)).toInt()
            if (got <= 0) {
                throw KromusFormatException(
                    "truncated kromus index: $length byte(s) at $offset could not be read from OPFS — " +
                        "the file ends after $done byte(s)",
                )
            }
            done += got
        }
    }

    override fun close() {
        val open = handle ?: return
        handle = null
        runCatching { open.close() }
    }

    public companion object {
        /** Reads the whole file behind [handle]. */
        public fun open(handle: FileSystemSyncAccessHandle): OpfsByteSource =
            OpfsByteSource(handle, 0, sizeOf(handle))

        /** Reads [length] bytes starting at [offset] — an index packed inside a larger file. */
        public fun openRange(handle: FileSystemSyncAccessHandle, offset: Int, length: Int): OpfsByteSource {
            require(offset >= 0) { "offset must be >= 0, was $offset" }
            require(length >= 0) { "length must be >= 0, was $length" }
            val total = sizeOf(handle)
            if (offset.toLong() + length > total.toLong()) {
                throw KromusFormatException(
                    "this OPFS file holds $total byte(s); the range $offset..${offset + length} runs past its end",
                )
            }
            return OpfsByteSource(handle, offset, length)
        }

        private fun sizeOf(handle: FileSystemSyncAccessHandle): Int {
            val length = handle.getSize()
            if (length > Int.MAX_VALUE.toDouble()) {
                throw KromusFormatException(
                    "this OPFS file is $length byte(s); a kromus container addresses at most ${Int.MAX_VALUE}",
                )
            }
            return length.toInt()
        }
    }
}
