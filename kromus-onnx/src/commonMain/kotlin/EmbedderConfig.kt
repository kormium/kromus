package io.github.kromus.onnx

/** How token embeddings are collapsed into one sentence vector. */
public enum class Pooling {
    /** Average of all token vectors weighted by the attention mask — the sentence-transformers default. */
    Mean,

    /** The first token's vector (`[CLS]`). */
    Cls,
}

/**
 * Per-model settings for [OnnxTextEmbedder]. Defaults suit most sentence-transformers (mean pooling +
 * L2 normalization). Set the prefixes for asymmetric models such as E5 (which expects `"query: "` on
 * queries and `"passage: "` on documents).
 *
 * @property maxTokens sequence length cap (longer inputs are truncated).
 * @property pooling how to pool token vectors; see [Pooling].
 * @property normalize L2-normalize the result (so cosine == dot).
 * @property queryPrefix prepended by [OnnxTextEmbedder.embed].
 * @property passagePrefix prepended by [OnnxTextEmbedder.embedDocument].
 */
public class EmbedderConfig(
    public val maxTokens: Int = 256,
    public val pooling: Pooling = Pooling.Mean,
    public val normalize: Boolean = true,
    public val queryPrefix: String = "",
    public val passagePrefix: String = "",
) {
    init {
        require(maxTokens >= 2) { "maxTokens must be >= 2 (room for [CLS]/[SEP]), was $maxTokens" }
    }
}
