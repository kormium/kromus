# kromus-onnx

**Text embeddings for kromus — one API on every target, model runtime per platform.**

`kromus-onnx` turns text into the `FloatArray` vectors you feed a kromus index. It exists because the
core stays embedder-agnostic; this optional module is the batteries-included path.

## How it's uniform *and* cross-platform

The whole pipeline — **WordPiece tokenizer → model run → pooling → L2 normalization** — is pure Kotlin
in `commonMain`, identical on JVM, Android, iOS, Native and the web. The *only* per-platform piece is
[`OnnxSession`](src/commonMain/kotlin/OnnxSession.kt): a single method that runs the model and returns
its token embeddings. So adding a platform means writing one small backend, not a second pipeline.

```
TextEmbedder            ← what your app calls (common)
 └ OnnxTextEmbedder      ← tokenize · pool · normalize (common, tested on JVM/JS/Wasm/Native)
     └ OnnxSession       ← the model runtime (per platform)
         ├ JVM   → ONNX Runtime for Java        ✅ shipped
         ├ Web   → onnxruntime-web / Transformers.js   (drop-in, below)
         ├ iOS / native → ONNX Runtime C API via cinterop   (planned)
         └ Android → onnxruntime-android         (planned)
```

## JVM (shipped)

```kotlin
val session = OrtOnnxSession(File("model.onnx").readBytes())
val tokenizer = WordPieceTokenizer.fromVocabText(File("vocab.txt").readText())
val embedder = OnnxTextEmbedder(session, tokenizer, dimensions = 384)

val v: FloatArray = embedder.embed("Kotlin coroutines guide")   // ready for VectorIndex.add(...)
```

Get a model and its `vocab.txt` from Hugging Face — e.g. `Xenova/all-MiniLM-L6-v2` (384-dim,
mean-pooling), or export any sentence-transformer with `optimum-cli export onnx`. See the main
[Embeddings](../readme.md#embeddings) section.

## Web (Kotlin/JS & Kotlin/Wasm) — drop-in

The web platform runs embeddings via [onnxruntime-web](https://onnxruntime.ai/docs/tutorials/web/)
(WASM, optionally WebGPU). Implement the same `OnnxSession` over its JS API and everything above it is
unchanged:

```kotlin
// jsMain — sketch; add: implementation(npm("onnxruntime-web", "1.20.1"))
external interface OrtTensor { val data: Float32Array; val dims: IntArray }
external interface OrtSession { fun run(feeds: dynamic): Promise<dynamic> }

class WebOnnxSession(private val session: OrtSession) : OnnxSession {
    override suspend fun run(inputIds: IntArray, attentionMask: IntArray, tokenTypeIds: IntArray): OnnxOutput {
        val seq = inputIds.size
        fun i64(a: IntArray) = ort.Tensor("int64", BigInt64Array(a.map { it.toLong() }), arrayOf(1, seq))
        val out = session.run(json("input_ids" to i64(inputIds), "attention_mask" to i64(attentionMask))).await()
        val t = out.last_hidden_state.unsafeCast<OrtTensor>()
        return OnnxOutput(FloatArray(t.data.length) { t.data[it] }, seq, t.dims[2])
    }
}
```

The Kotlin/Wasm backend is the same idea through Wasm↔JS interop. Because the tokenizer and pooling are
common, only this `OnnxSession` differs — and it's already validated by the shared tests on JS and Wasm.

## Asymmetric models (E5, etc.)

```kotlin
val cfg = EmbedderConfig(queryPrefix = "query: ", passagePrefix = "passage: ")
val embedder = OnnxTextEmbedder(session, tokenizer, dimensions = 384, config = cfg)
embedder.embed("how do coroutines work")        // query: …
embedder.embedDocument("Kotlin coroutines guide") // passage: …
```

## Status

Pre-1.0, part of the kromus suite. Shared layer runs on every target today; the JVM ONNX backend
ships; web / iOS / Android / native backends implement the documented `OnnxSession`. Not yet published
to Maven Central.

## License

Apache License 2.0.
