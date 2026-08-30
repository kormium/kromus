package io.github.kromus.files

import io.github.kromus.KeyCodec
import io.github.kromus.KromusFormatException
import io.github.kromus.openIvfIndex
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileByteSourceTest {

    private val blob = SourceContract.blob()
    private val queries = SourceContract.corpus().take(8)
    private val dir: File = Files.createTempDirectory("kromus-files").toFile()
    private val file: File = File(dir, "index.krm").also { it.writeBytes(blob) }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun aFileReadsLikeTheSameBytesInMemory() {
        FileByteSource.open(file).use { SourceContract.readsLikeMemory(it, blob, queries) }
    }

    @Test
    fun everyOffsetReadsWhatAnArrayWould() {
        FileByteSource.open(file.path).use { SourceContract.readsTheSameBytes(it, blob) }
    }

    @Test
    fun rangesOutsideTheFileAreRefused() {
        FileByteSource.open(file).use { SourceContract.refusesRangesOutsideItself(it) }
    }

    @Test
    fun aClosedSourceRefusesToRead() {
        SourceContract.refusesReadsAfterClosing(FileByteSource.open(file))
    }

    @Test
    fun anIndexPackedInsideALargerFileIsFoundByItsRange() {
        // The Android asset shape: the index sits at an offset inside a bigger file, and only its own
        // range may be read — which is what stops a container from parsing its neighbours' bytes.
        val padded = File(dir, "bundle.bin")
        val lead = ByteArray(1024) { 0x7F }
        padded.writeBytes(lead + blob + ByteArray(512) { 0x3C })

        FileByteSource.openRange(padded.path, lead.size.toLong(), blob.size).use { source ->
            assertEquals(blob.size, source.size)
            SourceContract.readsLikeMemory(source, blob, queries)
            SourceContract.refusesRangesOutsideItself(source)
        }
    }

    @Test
    fun aMissingFileIsAFormatFailureRatherThanAnIoOne() {
        // Everything a caller has to catch when loading an index is one exception, whether the bytes
        // were wrong or the file they should have come from was not there.
        val failure = assertFailsWith<KromusFormatException> {
            FileByteSource.open(File(dir, "absent.krm"))
        }
        assertTrue("absent.krm" in failure.message!!)
    }

    @Test
    fun aRangePastTheEndIsRefusedAtOpenTime() {
        val failure = assertFailsWith<KromusFormatException> {
            FileByteSource.openRange(file.path, 0, blob.size + 1)
        }
        assertTrue("runs past its end" in failure.message!!)
    }

    @Test
    fun severalSearchersMayShareOneSource() {
        // Positional reads do not move a shared cursor, which is the whole reason to use them: one
        // open file serves every thread that searches the index.
        FileByteSource.open(file).use { source ->
            val index = openIvfIndex(source, KeyCodec.int)
            val results = (0 until 4).toList().parallelStream().map { t ->
                val searcher = index.searcher()
                (0 until 5).map { searcher.search(queries[it], 5).map { r -> r.key } }
            }.toList()
            for (r in results) assertEquals(results[0], r, "a shared source ranked differently per thread")
        }
    }

    private fun <T> FileByteSource.use(block: (FileByteSource) -> T): T =
        try {
            block(this)
        } finally {
            close()
        }
}
