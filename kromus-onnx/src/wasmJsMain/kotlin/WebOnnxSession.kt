@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.kromus.onnx

import kotlinx.coroutines.await
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsNumber
import kotlin.js.Promise
import kotlin.js.toJsNumber

/**
 * Kotlin/Wasm [OnnxSession] over onnxruntime-web, through typed Wasm↔JS interop. Same idea as the
 * Kotlin/JS backend: the app creates the `ort.InferenceSession` and passes it in. The JS glue expects
 * `ort` to be reachable in the page's scope.
 *
 * ```kotlin
 * val session = WebOnnxSession(ortSession)  // ortSession: JsAny from ort.InferenceSession.create(...)
 * val embedder = OnnxTextEmbedder(session, tokenizer, dimensions = 384)
 * ```
 *
 * Note: compiled and shaped against the onnxruntime-web API; run it in your app against a real model.
 *
 * @param session an `ort.InferenceSession` (as a `JsAny`).
 * @param outputName the model's embedding output tensor (default `last_hidden_state`).
 */
public class WebOnnxSession(
    private val session: JsAny,
    private val outputName: String = "last_hidden_state",
) : OnnxSession {

    override suspend fun run(inputIds: IntArray, attentionMask: IntArray, tokenTypeIds: IntArray): OnnxOutput {
        val seq = inputIds.size
        val result: JsAny = ortRun(
            session,
            inputIds.toJsArray(),
            attentionMask.toJsArray(),
            tokenTypeIds.toJsArray(),
            seq,
            outputName,
        ).await()
        val hidden = jsHidden(result)
        val data = jsData(result)
        val flat = FloatArray(seq * hidden) { jsFloatAt(data, it) }
        return OnnxOutput(flat, seq, hidden)
    }
}

private fun IntArray.toJsArray(): JsArray<JsNumber> {
    val array = JsArray<JsNumber>()
    for (i in indices) array[i] = this[i].toJsNumber()
    return array
}

@JsFun(
    """
    (session, ids, mask, types, seq, outputName) => {
      const dims = [1, seq];
      const toI64 = (a) => BigInt64Array.from(a, (v) => BigInt(v));
      const feeds = {
        input_ids: new ort.Tensor('int64', toI64(ids), dims),
        attention_mask: new ort.Tensor('int64', toI64(mask), dims),
        token_type_ids: new ort.Tensor('int64', toI64(types), dims)
      };
      return session.run(feeds).then((r) => {
        const o = r[outputName];
        return { data: o.data, hidden: o.dims[o.dims.length - 1] };
      });
    }
    """,
)
private external fun ortRun(
    session: JsAny,
    ids: JsArray<JsNumber>,
    mask: JsArray<JsNumber>,
    types: JsArray<JsNumber>,
    seq: Int,
    outputName: String,
): Promise<JsAny>

@JsFun("(r) => r.hidden")
private external fun jsHidden(result: JsAny): Int

@JsFun("(r) => r.data")
private external fun jsData(result: JsAny): JsAny

@JsFun("(a, i) => a[i]")
private external fun jsFloatAt(array: JsAny, index: Int): Float
