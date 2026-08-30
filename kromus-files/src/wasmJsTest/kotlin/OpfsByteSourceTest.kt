@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.kromus.files
import io.github.kromus.KromusFormatException
import org.khronos.webgl.Int8Array
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A stand-in for a `FileSystemSyncAccessHandle`, backed by a typed array.
 *
 * A real handle exists only inside a Web Worker and a test runner drives the main thread, so what is
 * checked here is the half that is ours: the read loop, the offsets, a read that returns less than it
 * was asked for, and the state after closing. The binding to the browser's own object is not
 * exercised, and this file does not pretend otherwise.
 */
private fun fakeHandle(bytes: Int8Array, shortBy: Int): FileSystemSyncAccessHandle =
    js(
        """({
            getSize: function () { return bytes.length; },
            close: function () {},
            read: function (buffer, options) {
                var at = options.at;
                var want = buffer.length;
                var give = (shortBy > 0 && shortBy < want) ? want - shortBy : want;
                var available = (at + give > bytes.length) ? bytes.length - at : give;
                for (var i = 0; i < available; i++) buffer[i] = bytes[at + i];
                return available;
            }
        })""",
    )

private fun typedArrayOf(bytes: ByteArray): Int8Array {
    val out = Int8Array(bytes.size)
    for (i in bytes.indices) setByte(out, i, bytes[i].toInt())
    return out
}

private fun setByte(array: Int8Array, at: Int, value: Int) {
    js("array[at] = value")
}

class OpfsByteSourceTest {

    private val blob = SourceContract.blob()
    private val queries = SourceContract.corpus().take(6)

    @Test
    fun anOpfsHandleReadsLikeTheSameBytesInMemory() {
        val source = OpfsByteSource.open(fakeHandle(typedArrayOf(blob), 0))
        try {
            SourceContract.readsLikeMemory(source, blob, queries)
        } finally {
            source.close()
        }
    }

    @Test
    fun everyOffsetReadsWhatAnArrayWould() {
        val source = OpfsByteSource.open(fakeHandle(typedArrayOf(blob), 0))
        try {
            SourceContract.readsTheSameBytes(source, blob)
        } finally {
            source.close()
        }
    }

    @Test
    fun rangesOutsideTheFileAreRefused() {
        val source = OpfsByteSource.open(fakeHandle(typedArrayOf(blob), 0))
        try {
            SourceContract.refusesRangesOutsideItself(source)
        } finally {
            source.close()
        }
    }

    @Test
    fun aClosedSourceRefusesToRead() {
        SourceContract.refusesReadsAfterClosing(OpfsByteSource.open(fakeHandle(typedArrayOf(blob), 0)))
    }

    @Test
    fun aHandleThatReadsShortIsLoopedRatherThanTrusted() {
        val source = OpfsByteSource.open(fakeHandle(typedArrayOf(blob), 17))
        try {
            SourceContract.readsTheSameBytes(source, blob)
            SourceContract.readsLikeMemory(source, blob, queries)
        } finally {
            source.close()
        }
    }

    @Test
    fun anIndexPackedInsideALargerFileIsFoundByItsRange() {
        val lead = ByteArray(1024) { 0x7F }
        val padded = lead + blob + ByteArray(512) { 0x3C }
        val source = OpfsByteSource.openRange(fakeHandle(typedArrayOf(padded), 0), lead.size, blob.size)
        try {
            assertEquals(blob.size, source.size)
            SourceContract.readsLikeMemory(source, blob, queries)
        } finally {
            source.close()
        }
    }

    @Test
    fun aRangePastTheEndIsRefusedAtOpenTime() {
        val failure = assertFailsWith<KromusFormatException> {
            OpfsByteSource.openRange(fakeHandle(typedArrayOf(blob), 0), 0, blob.size + 1)
        }
        assertTrue("runs past its end" in failure.message!!)
    }
}
