package io.github.kromus.files

import io.github.kromus.ByteSource
import io.github.kromus.KromusFormatException
import io.github.kromus.checkRange
import org.khronos.webgl.Int8Array

/**
 * Node's `require`, reached indirectly so that a bundler cannot see it.
 *
 * This file compiles into the same artifact as the browser one — Kotlin/JS has a single source set
 * for both — and a literal `require('node:fs')` would be resolved at bundle time, which fails a
 * browser build that never intended to use Node at all. Going through `eval` keeps it invisible to
 * static analysis and evaluated only when a Node source is actually constructed.
 */
private fun nodeRequire(module: String): dynamic {
    val require = js("eval('require')")
    if (require == null || jsTypeOf(require) != "function") {
        throw KromusFormatException(
            "a Node file source needs Node's module loader, and this program is not running under it",
        )
    }
    return require(module)
}

private val fs: dynamic by lazy { nodeRequire("node:fs") }

/**
 * An index's bytes read from a file under Node.
 *
 * `fs.readSync` is genuinely synchronous, so it satisfies [ByteSource] with nothing in between —
 * which makes server-side JavaScript one of the places a file-backed index works without ceremony.
 * In a browser use [OpfsByteSource]; there is no synchronous file read on the main thread there.
 *
 * @throws KromusFormatException if the path cannot be opened or the range lies outside the file.
 */
public class NodeFileByteSource private constructor(
    private var fd: Int?,
    private val base: Int,
    override val size: Int,
    private val path: String,
) : ByteSource {

    override fun read(offset: Int, length: Int, into: ByteArray, at: Int) {
        checkRange(offset, length, size)
        val handle = fd ?: throw KromusFormatException("this kromus file source is closed")
        if (length == 0) return
        // A ByteArray is an Int8Array at runtime, so the read lands in the caller's buffer directly.
        val view = into.unsafeCast<Int8Array>()
        var done = 0
        while (done < length) {
            val got = fs.readSync(
                handle,
                view,
                at + done,
                length - done,
                (base + offset + done).toDouble(),
            ) as Int
            if (got <= 0) {
                throw KromusFormatException(
                    "truncated kromus index: $length byte(s) at $offset could not be read from " +
                        "'$path' — the file ends after $done byte(s)",
                )
            }
            done += got
        }
    }

    override fun close() {
        val handle = fd ?: return
        fd = null
        runCatching { fs.closeSync(handle) }
    }

    public companion object {
        /** Opens the whole of [path]. */
        public fun open(path: String): NodeFileByteSource {
            val fd = openFd(path)
            val length = try {
                (fs.fstatSync(fd).size as Number).toDouble()
            } catch (e: Throwable) {
                runCatching { fs.closeSync(fd) }
                throw KromusFormatException("cannot measure '$path': ${e.message}")
            }
            if (length > Int.MAX_VALUE.toDouble()) {
                runCatching { fs.closeSync(fd) }
                throw KromusFormatException(
                    "'$path' is $length byte(s); a kromus container addresses at most ${Int.MAX_VALUE}",
                )
            }
            return NodeFileByteSource(fd, 0, length.toInt(), path)
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
            return NodeFileByteSource(fd, offset, length, path)
        }

        private fun openFd(path: String): Int =
            try {
                fs.openSync(path, "r") as Int
            } catch (e: Throwable) {
                throw KromusFormatException("cannot open '$path' for reading: ${e.message}")
            }
    }
}
