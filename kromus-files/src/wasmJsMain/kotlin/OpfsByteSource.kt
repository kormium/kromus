@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.kromus.files
import io.github.kromus.ByteSource
import io.github.kromus.KromusFormatException
import io.github.kromus.checkRange
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toByteArray

/**
 * The synchronous half of the Origin Private File System.
 *
 * Only the handle is declared: everything asynchronous — asking for the directory, opening the file,
 * creating the handle — belongs to the caller, who is the one that knows where the index lives.
 */
public external interface FileSystemSyncAccessHandle : JsAny {
    public fun getSize(): Double

    public fun close()
}

private fun readInto(handle: FileSystemSyncAccessHandle, buffer: Int8Array, position: Double): Int =
    js("handle.read(buffer, { at: position })")

/**
 * An index's bytes read from a file in the browser's Origin Private File System, from WebAssembly.
 *
 * **This only works inside a Web Worker.** `createSyncAccessHandle` is the one file API in the browser
 * that reads synchronously, and it is not exposed on the main thread — a constraint of the platform
 * rather than a choice made here. A search over a file-backed index therefore runs in a worker and
 * posts its results back, which is where a scan over thousands of vectors belongs anyway.
 *
 * The handle is obtained asynchronously, and this class takes an already-open one rather than opening
 * it, so that [ByteSource] can stay synchronous:
 *
 * ```kotlin
 * // inside a worker
 * val handle = // navigator.storage.getDirectory() → getFileHandle → createSyncAccessHandle
 * val index = openIvfIndex(OpfsByteSource.open(handle), KeyCodec.string)
 * ```
 *
 * A sync access handle holds an exclusive lock on the file for as long as it is open, so [close] it
 * when the index is finished with — and note that closing it invalidates the index.
 *
 * Unlike the Kotlin/JS source, every read is copied out of a typed array: Wasm linear memory is not
 * something JavaScript can write into. One read per cluster crosses that boundary once.
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
        val buffer = Int8Array(length)
        var done = 0
        while (done < length) {
            val got = readInto(open, buffer.subarray(done, length), (base + offset + done).toDouble())
            if (got <= 0) {
                throw KromusFormatException(
                    "truncated kromus index: $length byte(s) at $offset could not be read from OPFS — " +
                        "the file ends after $done byte(s)",
                )
            }
            done += got
        }
        buffer.toByteArray().copyInto(into, at)
    }

    override fun close() {
        val open = handle ?: return
        handle = null
        open.close()
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
