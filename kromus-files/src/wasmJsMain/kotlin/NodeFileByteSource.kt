@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.kromus.files
import io.github.kromus.ByteSource
import io.github.kromus.KromusFormatException
import io.github.kromus.checkRange
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toByteArray

/**
 * Node's `fs`, or null when this is not Node.
 *
 * Reached through `eval` so that a bundler building for the browser cannot see the import and fail on
 * it: this file ships in the same artifact as the browser source, and only one of the two can ever
 * work in a given program.
 */
private fun nodeFsOrNull(): JsAny? =
    js("(function(){ try { return eval('require')('node:fs'); } catch (e) { return null; } })()")

private fun openSync(fs: JsAny, path: String): Int = js("fs.openSync(path, 'r')")

private fun fileSize(fs: JsAny, fd: Int): Double = js("fs.fstatSync(fd).size")

private fun closeSync(fs: JsAny, fd: Int) {
    js("(function(){ try { fs.closeSync(fd); } catch (e) {} })()")
}

private fun readSync(fs: JsAny, fd: Int, into: Int8Array, at: Int, length: Int, position: Double): Int =
    js("fs.readSync(fd, into, at, length, position)")

/**
 * An index's bytes read from a file under Node, compiled to WebAssembly.
 *
 * The one difference from the Kotlin/JS source is where the bytes land. There a `ByteArray` *is* a
 * JavaScript `Int8Array`, so a read fills the caller's buffer directly; here it is Wasm linear memory,
 * which JavaScript cannot write into, so every read goes through a typed array and is copied across.
 * The copy is why a batch matters: one read per cluster crosses the boundary once, where one read per
 * vector would cross it hundreds of times.
 *
 * @throws KromusFormatException if the path cannot be opened or the range lies outside the file.
 */
public class NodeFileByteSource private constructor(
    private val fs: JsAny,
    private var fd: Int?,
    private val base: Int,
    override val size: Int,
    private val path: String,
) : ByteSource {

    override fun read(offset: Int, length: Int, into: ByteArray, at: Int) {
        checkRange(offset, length, size)
        val handle = fd ?: throw KromusFormatException("this kromus file source is closed")
        if (length == 0) return
        val buffer = Int8Array(length)
        var done = 0
        while (done < length) {
            val got = readSync(fs, handle, buffer, done, length - done, (base + offset + done).toDouble())
            if (got <= 0) {
                throw KromusFormatException(
                    "truncated kromus index: $length byte(s) at $offset could not be read from " +
                        "'$path' — the file ends after $done byte(s)",
                )
            }
            done += got
        }
        buffer.toByteArray().copyInto(into, at)
    }

    override fun close() {
        val handle = fd ?: return
        fd = null
        closeSync(fs, handle)
    }

    public companion object {
        /** Opens the whole of [path]. */
        public fun open(path: String): NodeFileByteSource {
            val fs = nodeFsOrNull()
                ?: throw KromusFormatException(
                    "a Node file source needs Node's module loader, and this program is not running under it",
                )
            val fd = try {
                openSync(fs, path)
            } catch (e: Throwable) {
                throw KromusFormatException("cannot open '$path' for reading: ${e.message}")
            }
            val length = try {
                fileSize(fs, fd)
            } catch (e: Throwable) {
                closeSync(fs, fd)
                throw KromusFormatException("cannot measure '$path': ${e.message}")
            }
            if (length > Int.MAX_VALUE.toDouble()) {
                closeSync(fs, fd)
                throw KromusFormatException(
                    "'$path' is $length byte(s); a kromus container addresses at most ${Int.MAX_VALUE}",
                )
            }
            return NodeFileByteSource(fs, fd, 0, length.toInt(), path)
        }

        /** Opens [length] bytes of [path] starting at [offset] — an index packed inside a larger file. */
        public fun openRange(path: String, offset: Int, length: Int): NodeFileByteSource {
            require(offset >= 0) { "offset must be >= 0, was $offset" }
            require(length >= 0) { "length must be >= 0, was $length" }
            val whole = open(path)
            if (offset.toLong() + length > whole.size.toLong()) {
                whole.close()
                throw KromusFormatException(
                    "'$path' holds ${whole.size} byte(s); the range $offset..${offset + length} runs past its end",
                )
            }
            val fd = whole.fd
            whole.fd = null // hand the descriptor over rather than opening the file twice
            return NodeFileByteSource(whole.fs, fd, offset, length, path)
        }
    }
}
