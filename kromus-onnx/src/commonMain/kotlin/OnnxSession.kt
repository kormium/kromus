package io.github.kromus.onnx

/**
 * The one per-platform seam: runs the encoder model on a tokenized input and returns its token
 * embeddings (`last_hidden_state`). Everything else — tokenization, pooling, normalization — is shared
 * common code in [OnnxTextEmbedder], so a backend only has to bridge to a runtime:
 *
 * - **JVM** — ONNX Runtime for Java (shipped: `OrtOnnxSession`).
 * - **Web (Kotlin/JS, Kotlin/Wasm)** — `onnxruntime-web` / Transformers.js via JS interop.
 * - **iOS / desktop native** — the ONNX Runtime C API via cinterop.
 * - **Android** — `onnxruntime-android`.
 *
 * Implementations are typically backed by native resources; call [close] when done.
 */
public interface OnnxSession {
    /**
     * Runs the model for one sequence. [inputIds], [attentionMask] and [tokenTypeIds] all have the
     * same length (the sequence length). A model without a `token_type_ids` input may ignore it.
     */
    public suspend fun run(inputIds: IntArray, attentionMask: IntArray, tokenTypeIds: IntArray): OnnxOutput

    public fun close() {}
}

/**
 * The encoder output: [lastHiddenState] is `seqLen * hiddenSize` floats in row-major order (token 0's
 * vector first).
 */
public class OnnxOutput(
    public val lastHiddenState: FloatArray,
    public val seqLen: Int,
    public val hiddenSize: Int,
)
