package io.github.kromus.files

import io.github.kromus.KromusFormatException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A stand-in for a `FileSystemSyncAccessHandle`, backed by an array.
 *
 * A real handle can only be created inside a Web Worker, and a test runner drives the main thread —
 * so what is checked here is the half that is ours: the read loop, the offsets, the handling of a
 * read that returns less than it was asked for, and the state after closing. The binding to the
 * browser's own object is not exercised, and this file does not pretend otherwise.
 *
 * [shortBy] makes every read return fewer bytes than requested, which is exactly what the browser
 * does when a read crosses whatever boundary it feels like. A source that does not loop returns a
 * half-filled buffer, and the index built on it answers with neighbours that merely look right.
 */
private fun fakeHandle(bytes: ByteArray, shortBy: Int = 0): FileSystemSyncAccessHandle {
    val handle = js("{}")
    handle.getSize = { bytes.size.toDouble() }
    handle.close = { }
    handle.read = { buffer: dynamic, options: dynamic ->
        val at = (options.at as Number).toInt()
        val want = buffer.length as Int
        val give = if (shortBy in 1 until want) want - shortBy else want
        val available = if (at + give > bytes.size) bytes.size - at else give
        for (i in 0 until available) buffer[i] = bytes[at + i]
        available.toDouble()
    }
    return handle.unsafeCast<FileSystemSyncAccessHandle>()
}

class OpfsByteSourceTest {

    private val blob = SourceContract.blob()
    private val queries = SourceContract.corpus().take(6)

    @Test
    fun anOpfsHandleReadsLikeTheSameBytesInMemory() {
        val source = OpfsByteSource.open(fakeHandle(blob))
        try {
            SourceContract.readsLikeMemory(source, blob, queries)
        } finally {
            source.close()
        }
    }

    @Test
    fun everyOffsetReadsWhatAnArrayWould() {
        val source = OpfsByteSource.open(fakeHandle(blob))
        try {
            SourceContract.readsTheSameBytes(source, blob)
        } finally {
            source.close()
        }
    }

    @Test
    fun rangesOutsideTheFileAreRefused() {
        val source = OpfsByteSource.open(fakeHandle(blob))
        try {
            SourceContract.refusesRangesOutsideItself(source)
        } finally {
            source.close()
        }
    }

    @Test
    fun aClosedSourceRefusesToRead() {
        SourceContract.refusesReadsAfterClosing(OpfsByteSource.open(fakeHandle(blob)))
    }

    @Test
    fun aHandleThatReadsShortIsLoopedRatherThanTrusted() {
        // The failure this whole interface warns about: if the loop were missing, the index would
        // still load and still answer — with vectors made partly of whatever was in the buffer.
        val source = OpfsByteSource.open(fakeHandle(blob, shortBy = 17))
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
        val source = OpfsByteSource.openRange(fakeHandle(padded), lead.size, blob.size)
        try {
            assertEquals(blob.size, source.size)
            SourceContract.readsLikeMemory(source, blob, queries)
            SourceContract.refusesRangesOutsideItself(source)
        } finally {
            source.close()
        }
    }

    @Test
    fun aRangePastTheEndIsRefusedAtOpenTime() {
        val failure = assertFailsWith<KromusFormatException> {
            OpfsByteSource.openRange(fakeHandle(blob), 0, blob.size + 1)
        }
        assertTrue("runs past its end" in failure.message!!)
    }
}
