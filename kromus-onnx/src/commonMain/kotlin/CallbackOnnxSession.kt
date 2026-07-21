package io.github.kromus.onnx

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * A model runner shaped as a callback — the framework-free way to plug a platform's ONNX runtime into
 * kromus, in particular on **iOS**, where the app implements this in Swift over `onnxruntime-objc` (no
 * Kotlin/Native cinterop needed). Wrap it with [CallbackOnnxSession].
 *
 * The implementation runs the encoder and then invokes exactly one of [onResult] (with
 * `last_hidden_state` as `seqLen * hiddenSize` floats, row-major) or [onError].
 */
public fun interface OnnxRunner {
    public fun run(
        inputIds: IntArray,
        attentionMask: IntArray,
        tokenTypeIds: IntArray,
        onResult: (lastHiddenState: FloatArray, seqLen: Int, hiddenSize: Int) -> Unit,
        onError: (Throwable) -> Unit,
    )
}

/**
 * Adapts a callback-style [OnnxRunner] to the suspending [OnnxSession] the pipeline expects. Works on
 * every target; it's the recommended iOS backend:
 *
 * ```swift
 * // Swift — implement OnnxRunner over onnxruntime-objc, then hand it to Kotlin
 * class OrtRunner: OnnxRunner {
 *     func run(inputIds: KotlinIntArray, attentionMask: KotlinIntArray, tokenTypeIds: KotlinIntArray,
 *              onResult: @escaping (KotlinFloatArray, KotlinInt, KotlinInt) -> Void,
 *              onError: @escaping (KotlinThrowable) -> Void) {
 *         // run ORTSession, then onResult(hidden, seqLen, hiddenSize)
 *     }
 * }
 * ```
 * ```kotlin
 * val embedder = OnnxTextEmbedder(CallbackOnnxSession(ortRunner), tokenizer, dimensions = 384)
 * ```
 */
public class CallbackOnnxSession(private val runner: OnnxRunner) : OnnxSession {
    override suspend fun run(inputIds: IntArray, attentionMask: IntArray, tokenTypeIds: IntArray): OnnxOutput =
        suspendCancellableCoroutine { continuation ->
            runner.run(
                inputIds,
                attentionMask,
                tokenTypeIds,
                onResult = { lastHiddenState, seqLen, hiddenSize ->
                    continuation.resume(OnnxOutput(lastHiddenState, seqLen, hiddenSize))
                },
                onError = { error -> continuation.resumeWithException(error) },
            )
        }
}
