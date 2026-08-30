@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.kromus.files
import io.github.kromus.KromusFormatException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun makeTempDir(): String =
    js("eval('require')('node:fs').mkdtempSync(eval('require')('node:os').tmpdir() + '/kromus-wasm-')")

private fun writeBytes(path: String, data: org.khronos.webgl.Int8Array) {
    js("eval('require')('node:fs').writeFileSync(path, data)")
}

private fun removeDir(path: String) {
    js("eval('require')('node:fs').rmSync(path, { recursive: true, force: true })")
}

private fun ByteArray.toTypedArray(): org.khronos.webgl.Int8Array {
    val out = org.khronos.webgl.Int8Array(size)
    for (i in indices) setByte(out, i, this[i].toInt())
    return out
}

private fun setByte(array: org.khronos.webgl.Int8Array, at: Int, value: Int) {
    js("array[at] = value")
}

class NodeFileByteSourceTest {

    private val blob = SourceContract.blob()
    private val queries = SourceContract.corpus().take(8)
    private val dir = makeTempDir()
    private val path = "$dir/index.krm"
    private val paddedPath = "$dir/bundle.bin"
    private val lead = ByteArray(1024) { 0x7F }

    init {
        writeBytes(path, blob.toTypedArray())
        writeBytes(paddedPath, (lead + blob + ByteArray(512) { 0x3C }).toTypedArray())
    }

    @AfterTest
    fun cleanUp() {
        removeDir(dir)
    }

    @Test
    fun aFileReadsLikeTheSameBytesInMemory() {
        val source = NodeFileByteSource.open(path)
        try {
            SourceContract.readsLikeMemory(source, blob, queries)
        } finally {
            source.close()
        }
    }

    @Test
    fun everyOffsetReadsWhatAnArrayWould() {
        val source = NodeFileByteSource.open(path)
        try {
            SourceContract.readsTheSameBytes(source, blob)
        } finally {
            source.close()
        }
    }

    @Test
    fun rangesOutsideTheFileAreRefused() {
        val source = NodeFileByteSource.open(path)
        try {
            SourceContract.refusesRangesOutsideItself(source)
        } finally {
            source.close()
        }
    }

    @Test
    fun aClosedSourceRefusesToRead() {
        SourceContract.refusesReadsAfterClosing(NodeFileByteSource.open(path))
    }

    @Test
    fun anIndexPackedInsideALargerFileIsFoundByItsRange() {
        val source = NodeFileByteSource.openRange(paddedPath, lead.size, blob.size)
        try {
            assertEquals(blob.size, source.size)
            SourceContract.readsLikeMemory(source, blob, queries)
        } finally {
            source.close()
        }
    }

    @Test
    fun aMissingFileIsAFormatFailureRatherThanAnIoOne() {
        val failure = assertFailsWith<KromusFormatException> {
            NodeFileByteSource.open("$dir/absent.krm")
        }
        assertTrue("cannot open" in failure.message!!)
    }
}
