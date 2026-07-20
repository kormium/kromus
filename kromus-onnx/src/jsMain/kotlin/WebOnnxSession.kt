package io.github.kromus.onnx

import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * Kotlin/JS [OnnxSession] backed by
 * [onnxruntime-web](https://onnxruntime.ai/docs/tutorials/web/) (WASM, optionally WebGPU).
 *
 * The app owns the model runtime: it creates the `ort.InferenceSession` (from a URL or `ArrayBuffer`)
 * and passes it in, along with a factory that wraps an int64 `ort.Tensor` — kept as a parameter so
 * this module needs no compile-time dependency on the `ort` package.
 *
 * ```kotlin
 * // app has `ort` in scope (implementation(npm("onnxruntime-web", "1.20.1")))
 * val ortSession = ort.InferenceSession.create("all-MiniLM-L6-v2.onnx").await()
 * val session = WebOnnxSession(
 *     session = ortSession,
 *     createInt64Tensor = { values, dims ->
 *         val data = js("BigInt64Array.from(values, function (v) { return BigInt(v); })")
 *         js("new ort.Tensor('int64', data, dims)")
 *     },
 * )
 * val embedder = OnnxTextEmbedder(session, tokenizer, dimensions = 384)
 * ```
 *
 * @param session an `ort.InferenceSession`.
 * @param createInt64Tensor builds an int64 `ort.Tensor` from `values` with shape `dims`.
 * @param outputName the model's embedding output tensor (default `last_hidden_state`).
 */
public class WebOnnxSession(
    private val session: dynamic,
    private val createInt64Tensor: (values: IntArray, dims: IntArray) -> dynamic,
    private val outputName: String = "last_hidden_state",
) : OnnxSession {

    override suspend fun run(inputIds: IntArray, attentionMask: IntArray, tokenTypeIds: IntArray): OnnxOutput {
        val seq = inputIds.size
        val dims = intArrayOf(1, seq)

        val feeds: dynamic = js("({})")
        feeds["input_ids"] = createInt64Tensor(inputIds, dims)
        feeds["attention_mask"] = createInt64Tensor(attentionMask, dims)
        feeds["token_type_ids"] = createInt64Tensor(tokenTypeIds, dims)

        val results = session.run(feeds).unsafeCast<Promise<dynamic>>().await()
        val output = results[outputName]
        val data = output.data                          // Float32Array
        val outDims = output.dims
        val hidden = outDims[outDims.length - 1].unsafeCast<Int>()

        val flat = FloatArray(seq * hidden) { data[it].unsafeCast<Float>() }
        return OnnxOutput(flat, seq, hidden)
    }

    override fun close() {
        val s = session
        js("if (s && typeof s.release === 'function') { s.release(); }")
    }
}
