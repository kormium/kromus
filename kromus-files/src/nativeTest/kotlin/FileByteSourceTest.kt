package io.github.kromus.files

import io.github.kromus.KromusFormatException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.remove
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
private fun writeFile(path: String, bytes: ByteArray) {
    val file = fopen(path, "wb") ?: error("cannot create $path")
    bytes.usePinned { pinned ->
        val written = fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file).toInt()
        check(written == bytes.size) { "wrote $written of ${bytes.size} byte(s) to $path" }
    }
    fclose(file)
}

@OptIn(ExperimentalForeignApi::class)
private fun tempDir(): String =
    getenv("TMPDIR")?.let { pointerToString(it) }
        ?: getenv("TEMP")?.let { pointerToString(it) }
        ?: "/tmp"

@OptIn(ExperimentalForeignApi::class)
private fun pointerToString(p: kotlinx.cinterop.CPointer<kotlinx.cinterop.ByteVar>): String =
    p.toKString().trimEnd('/', '\\')

@OptIn(ExperimentalForeignApi::class)
class FileByteSourceTest {

    private val blob = SourceContract.blob()
    private val queries = SourceContract.corpus().take(8)
    private val path = "${tempDir()}/kromus-native-${blob.size}.krm"
    private val paddedPath = "${tempDir()}/kromus-native-${blob.size}-padded.bin"
    private val lead = ByteArray(1024) { 0x7F }

    init {
        writeFile(path, blob)
        writeFile(paddedPath, lead + blob + ByteArray(512) { 0x3C })
    }

    @AfterTest
    fun cleanUp() {
        remove(path)
        remove(paddedPath)
    }

    @Test
    fun aFileReadsLikeTheSameBytesInMemory() {
        val source = FileByteSource.open(path)
        try {
            SourceContract.readsLikeMemory(source, blob, queries)
        } finally {
            source.close()
        }
    }

    @Test
    fun everyOffsetReadsWhatAnArrayWould() {
        val source = FileByteSource.open(path)
        try {
            SourceContract.readsTheSameBytes(source, blob)
        } finally {
            source.close()
        }
    }

    @Test
    fun rangesOutsideTheFileAreRefused() {
        val source = FileByteSource.open(path)
        try {
            SourceContract.refusesRangesOutsideItself(source)
        } finally {
            source.close()
        }
    }

    @Test
    fun aClosedSourceRefusesToRead() {
        SourceContract.refusesReadsAfterClosing(FileByteSource.open(path))
    }

    @Test
    fun anIndexPackedInsideALargerFileIsFoundByItsRange() {
        val source = FileByteSource.openRange(paddedPath, lead.size.toLong(), blob.size)
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
            FileByteSource.open("${tempDir()}/kromus-native-absent.krm")
        }
        assertTrue("cannot open" in failure.message!!)
    }

    @Test
    fun aRangePastTheEndIsRefusedAtOpenTime() {
        val failure = assertFailsWith<KromusFormatException> {
            FileByteSource.openRange(path, 0, blob.size + 1)
        }
        assertTrue("runs past its end" in failure.message!!)
    }
}
