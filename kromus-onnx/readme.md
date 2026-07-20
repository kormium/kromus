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
         ├ JVM   → ONNX Runtime for Java              ✅ shipped
         ├ Web   → onnxruntime-web (Kotlin/JS + Wasm) ✅ shipped
         ├ iOS / native → ONNX Runtime C API (cinterop)   (planned)
         └ Android → onnxruntime-android                  (planned)
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

## Web (Kotlin/JS & Kotlin/Wasm) — shipped

The web platform runs embeddings via [onnxruntime-web](https://onnxruntime.ai/docs/tutorials/web/)
(WASM, optionally WebGPU). `WebOnnxSession` is shipped for both **Kotlin/JS** and **Kotlin/Wasm** — you
create the `ort.InferenceSession` in your app (adding `implementation(npm("onnxruntime-web", "1.20.1"))`)
and hand it in; the tokenizer and pooling are the same shared common code.

```kotlin
// Kotlin/JS — app has `ort` in scope
val ortSession = ort.InferenceSession.create("all-MiniLM-L6-v2.onnx").await()
val session = WebOnnxSession(
    session = ortSession,
    createInt64Tensor = { values, dims ->
        val data = js("BigInt64Array.from(values, function (v) { return BigInt(v); })")
        js("new ort.Tensor('int64', data, dims)")
    },
)
val embedder = OnnxTextEmbedder(session, tokenizer, dimensions = 384)
val vector = embedder.embed("async programming")
```

Kotlin/Wasm is the same `WebOnnxSession(ortSession)` through typed Wasm↔JS interop. Both compile as part
of the build; run them in your app against a real model (this repo verifies the shared tokenizer +
pipeline on JS and Wasm, not a full in-browser inference).

## Asymmetric models (E5, etc.)

```kotlin
val cfg = EmbedderConfig(queryPrefix = "query: ", passagePrefix = "passage: ")
val embedder = OnnxTextEmbedder(session, tokenizer, dimensions = 384, config = cfg)
embedder.embed("how do coroutines work")        // query: …
embedder.embedDocument("Kotlin coroutines guide") // passage: …
```

## Status

Pre-1.0, part of the kromus suite. Shared layer runs on every target today; the JVM and web (Kotlin/JS
+ Wasm) `OnnxSession` backends ship; iOS / Android / desktop-native backends are next. Not yet
published to Maven Central.

## License

Apache License 2.0.
