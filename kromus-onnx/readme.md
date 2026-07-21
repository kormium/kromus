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
         ├ JVM / Android → ONNX Runtime (onnxruntime / -android)  ✅ shipped
         ├ Web (Kotlin/JS + Wasm) → onnxruntime-web               ✅ shipped
         ├ iOS → CallbackOnnxSession + Swift onnxruntime-objc     ✅ shipped
         └ desktop-native → ONNX Runtime C API (cinterop)         (planned)
```

## JVM & Android (shipped)

`OrtOnnxSession` runs on ONNX Runtime for Java. It's shared between JVM (`onnxruntime`) and Android
(`onnxruntime-android`) — same `ai.onnxruntime` API, same code.

```kotlin
val session = OrtOnnxSession(File("model.onnx").readBytes())   // Android: modelBytes from assets
val tokenizer = WordPieceTokenizer.fromVocabText(File("vocab.txt").readText())
val embedder = OnnxTextEmbedder(session, tokenizer, dimensions = 384)

val v: FloatArray = embedder.embed("Kotlin coroutines guide")   // ready for VectorIndex.add(...)
```

Get a model and its `vocab.txt` from Hugging Face — e.g. `Xenova/all-MiniLM-L6-v2` (384-dim,
mean-pooling), or export any sentence-transformer with `optimum-cli export onnx`. See the main
[Embeddings](../readme.md#embeddings) section.

## iOS (shipped)

No cinterop: implement the callback-shaped `OnnxRunner` in Swift over `onnxruntime-objc`, then wrap it
with `CallbackOnnxSession`. The tokenizer and pooling stay shared common code.

```swift
class OrtRunner: OnnxRunner {
    func run(inputIds: KotlinIntArray, attentionMask: KotlinIntArray, tokenTypeIds: KotlinIntArray,
             onResult: @escaping (KotlinFloatArray, KotlinInt, KotlinInt) -> Void,
             onError: @escaping (KotlinThrowable) -> Void) {
        // run the ORTSession, then: onResult(lastHiddenState, seqLen, hiddenSize)
    }
}
```
```kotlin
val embedder = OnnxTextEmbedder(CallbackOnnxSession(ortRunner), tokenizer, dimensions = 384)
```

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

Pre-1.0, part of the kromus suite. Shared layer runs on every target; the JVM, Android, web (Kotlin/JS
+ Wasm) and iOS `OnnxSession` backends ship. Desktop-native (ORT C API cinterop) is next. All Kotlin
backends are verified to compile here; full inference runs in your app against a real model. Not yet
published to Maven Central.

## License

Apache License 2.0.
