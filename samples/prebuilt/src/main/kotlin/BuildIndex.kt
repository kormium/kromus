package io.github.kromus.samples.prebuilt

import io.github.kromus.HnswConfig
import io.github.kromus.HybridIndex
import io.github.kromus.KeyCodec
import io.github.kromus.Quantization
import io.github.kromus.encodeToByteArray
import io.github.kromus.samples.common.ToyEmbedder
import java.io.File

/**
 * Build step: turn a corpus into an index blob. Runs on a build machine, never on a device.
 *
 * The two costs a phone is worst at — running an embedding model over every document, and building
 * an HNSW graph — are paid exactly once, here. What ships is the result.
 */
fun main(args: Array<String>) {
    val corpus = File(args[0])
    val outputDir = File(args[1]).apply { mkdirs() }

    val embedder = ToyEmbedder()
    val index = HybridIndex<String>(
        dimensions = embedder.dimensions,
        // int8 is the shipping default: a quarter of the memory for about a hundredth of the recall.
        hnswConfig = HnswConfig(quantization = Quantization.Int8),
    )

    val lines = corpus.readLines().filter { it.isNotBlank() }
    lines.forEachIndexed { i, line ->
        index.add("doc-$i", embedder.embed(line), line)
    }

    // The provenance string is the guard against the failure this whole shape invites: the device
    // embeds queries with *its* model, and if that is not the model the corpus was embedded with,
    // nothing throws — the results are just quietly wrong. Naming it here and expecting it there
    // turns that into a refusal at load time.
    val blob = index.encodeToByteArray(KeyCodec.string, provenance = PROVENANCE)

    val out = File(outputDir, INDEX_RESOURCE)
    out.writeBytes(blob)
    println("indexed ${lines.size} documents -> ${out.name}, ${blob.size} bytes, provenance '$PROVENANCE'")
}

/** Identifies what produced the vectors. A real project would put the model name and revision here. */
const val PROVENANCE: String = "toy-embedder/v1"

const val INDEX_RESOURCE: String = "corpus.kromus"
