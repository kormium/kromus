package io.github.kromus.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * JVM / Android [OnnxSession] backed by ONNX Runtime for Java (`onnxruntime` on the JVM,
 * `onnxruntime-android` on Android — same `ai.onnxruntime` API). Give it the raw bytes of an ONNX
 * encoder model (e.g. `all-MiniLM-L6-v2`); pair it with a [WordPieceTokenizer] and an [OnnxTextEmbedder].
 *
 * ```
 * val session = OrtOnnxSession(File("model.onnx").readBytes())
 * val tokenizer = WordPieceTokenizer.fromVocabText(File("vocab.txt").readText())
 * val embedder = OnnxTextEmbedder(session, tokenizer, dimensions = 384)
 * ```
 *
 * @param inputNames the model's input tensor names (BERT defaults). `token_type_ids` is only fed when
 *   the model actually declares it.
 */
public class OrtOnnxSession(
    modelBytes: ByteArray,
    private val inputNames: InputNames = InputNames(),
) : OnnxSession {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelBytes)
    private val wantsTokenTypes = session.inputNames.contains(inputNames.tokenTypeIds)

    override suspend fun run(inputIds: IntArray, attentionMask: IntArray, tokenTypeIds: IntArray): OnnxOutput =
        withContext(Dispatchers.Default) {
            val seq = inputIds.size
            val shape = longArrayOf(1, seq.toLong())
            val idsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(seq) { inputIds[it].toLong() }), shape)
            val maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(seq) { attentionMask[it].toLong() }), shape)
            val typeTensor = if (wantsTokenTypes) {
                OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(seq) { tokenTypeIds[it].toLong() }), shape)
            } else {
                null
            }

            val inputs = HashMap<String, OnnxTensor>()
            inputs[inputNames.inputIds] = idsTensor
            inputs[inputNames.attentionMask] = maskTensor
            if (typeTensor != null) inputs[inputNames.tokenTypeIds] = typeTensor

            try {
                session.run(inputs).use { result ->
                    val tensor = result.get(0) as OnnxTensor
                    val hidden = tensor.info.shape[2].toInt()
                    val buffer = tensor.floatBuffer
                    val flat = FloatArray(buffer.remaining())
                    buffer.get(flat)
                    OnnxOutput(flat, seq, hidden)
                }
            } finally {
                idsTensor.close()
                maskTensor.close()
                typeTensor?.close()
            }
        }

    override fun close() {
        session.close()
    }
}

/** The model's input tensor names. Defaults match the BERT / sentence-transformers convention. */
public class InputNames(
    public val inputIds: String = "input_ids",
    public val attentionMask: String = "attention_mask",
    public val tokenTypeIds: String = "token_type_ids",
)
