package io.github.kromus.files

import io.github.kromus.KromusFormatException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val nodeFs: dynamic = js("eval('require')")("node:fs")
private val nodeOs: dynamic = js("eval('require')")("node:os")

private fun writeFile(path: String, bytes: ByteArray) {
    nodeFs.writeFileSync(path, bytes)
}

class NodeFileByteSourceTest {

    private val blob = SourceContract.blob()
    private val queries = SourceContract.corpus().take(8)
    private val dir: String = nodeFs.mkdtempSync("${nodeOs.tmpdir()}/kromus-js-") as String
    private val path = "$dir/index.krm"
    private val paddedPath = "$dir/bundle.bin"
    private val lead = ByteArray(1024) { 0x7F }

    init {
        writeFile(path, blob)
        writeFile(paddedPath, lead + blob + ByteArray(512) { 0x3C })
    }

    @AfterTest
    fun cleanUp() {
        nodeFs.rmSync(dir, js("({ recursive: true, force: true })"))
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
            SourceContract.refusesRangesOutsideItself(source)
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

    @Test
    fun aRangePastTheEndIsRefusedAtOpenTime() {
        val failure = assertFailsWith<KromusFormatException> {
            NodeFileByteSource.openRange(path, 0, blob.size + 1)
        }
        assertTrue("runs past its end" in failure.message!!)
    }
}
