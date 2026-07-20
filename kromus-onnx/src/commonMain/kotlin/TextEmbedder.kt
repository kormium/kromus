package io.github.kromus.onnx

/**
 * Turns text into a dense `FloatArray` embedding — the vectors you feed to a kromus index. The same
 * interface is available on every KMP target; what differs per platform is only the model runtime
 * behind it (see [OnnxSession]).
 *
 * ```
 * val vector = embedder.embed("Kotlin coroutines guide")   // FloatArray of `dimensions`
 * index.add("doc-1", vector)
 * ```
 */
public interface TextEmbedder {
    /** Length of every embedding this embedder produces. */
    public val dimensions: Int

    /** Embeds a single text. */
    public suspend fun embed(text: String): FloatArray

    /** Embeds many texts; override for a batched implementation. */
    public suspend fun embedAll(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
}
