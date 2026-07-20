package io.github.kromus.onnx

import kotlin.math.sqrt

/**
 * The shared embedding pipeline: tokenize → run the model ([OnnxSession]) → pool → normalize. All of
 * this is common code, so the pipeline is identical on every target; only the [session] is
 * platform-specific.
 *
 * ```
 * val embedder = OnnxTextEmbedder(session, tokenizer, dimensions = 384)
 * val vector = embedder.embed("async programming")
 * ```
 *
 * @param session the model runtime for the current platform.
 * @param tokenizer must match the model that [session] runs.
 * @param dimensions embedding length (the model's hidden size after pooling).
 * @param config pooling / normalization / prefixes; see [EmbedderConfig].
 */
public class OnnxTextEmbedder(
    private val session: OnnxSession,
    private val tokenizer: WordPieceTokenizer,
    override val dimensions: Int,
    private val config: EmbedderConfig = EmbedderConfig(),
) : TextEmbedder {

    /** Embeds a query (applies [EmbedderConfig.queryPrefix]). */
    override suspend fun embed(text: String): FloatArray = encode(config.queryPrefix + text)

    /** Embeds a document (applies [EmbedderConfig.passagePrefix]) — for asymmetric models like E5. */
    public suspend fun embedDocument(text: String): FloatArray = encode(config.passagePrefix + text)

    private suspend fun encode(text: String): FloatArray {
        val input = tokenizer.tokenize(text, config.maxTokens)
        val out = session.run(input.inputIds, input.attentionMask, input.tokenTypeIds)
        val pooled = pool(out, input.attentionMask)
        return if (config.normalize) l2Normalize(pooled) else pooled
    }

    private fun pool(out: OnnxOutput, mask: IntArray): FloatArray {
        val h = out.hiddenSize
        val result = FloatArray(h)
        when (config.pooling) {
            Pooling.Cls -> for (j in 0 until h) result[j] = out.lastHiddenState[j]
            Pooling.Mean -> {
                var count = 0f
                for (i in 0 until out.seqLen) {
                    if (i < mask.size && mask[i] == 0) continue
                    count += 1f
                    val base = i * h
                    for (j in 0 until h) result[j] += out.lastHiddenState[base + j]
                }
                if (count > 0f) for (j in 0 until h) result[j] /= count
            }
        }
        return result
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        if (sum == 0f) return v
        val inv = 1f / sqrt(sum)
        return FloatArray(v.size) { v[it] * inv }
    }
}
