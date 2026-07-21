# kromus

**An embedded, reflection-free hybrid search engine for Kotlin Multiplatform.**

kromus is a pure-Kotlin search index that runs *inside* your app — on JVM, Android, iOS, Native,
and the web (Wasm/JS) — with **one implementation and identical behaviour on every target**. No
native library to link, no per-platform build, no server.

It ships in layers:

- **Vector search** — a pure-Kotlin [HNSW](https://en.wikipedia.org/wiki/Hierarchical_navigable_small_world)
  approximate-nearest-neighbour index for semantic / similarity search over embeddings.
- **Full-text search** — an inverted index with [BM25](https://en.wikipedia.org/wiki/Okapi_BM25)
  ranking and pluggable analyzers.
- **Hybrid queries** — vector + full-text fused with Reciprocal Rank Fusion (RRF), the 2026 best
  practice that lifts recall well above either retriever alone.

> **Status:** `0.14.0`, pre-1.0. All three layers, binary persistence, int8/binary quantization,
> metadata filters, pluggable analyzers, full-precision re-rank, an optional kemus storage adapter and
> an optional `kromus-onnx` embedder are usable today; the API may still change before 1.0. See the
> roadmap for what's next.

## Why it exists

On-device semantic search is now table stakes for AI features (private, offline, no per-query cost),
but every existing option on Kotlin is a **C/C++ SQLite extension you bind per platform** — `sqlite-vec`
(brute-force only), `vectorlite`/`hnswlib` (C++), ObjectBox (commercial, Android/JVM + a separate iOS
product, not one KMP artifact). There is **no pure-Kotlin, common-code ANN index that runs on the whole
KMP matrix**. That is the gap kromus fills.

|                         | ANN / HNSW | Full-text / BM25 | Hybrid + RRF | Pure KMP (iOS + Wasm + Native) |
| ----------------------- | :--------: | :--------------: | :----------: | :----------------------------: |
| sqlite-vec              | ✗ (brute)  |        ✗         |      ✗       |     C extension, per-platform  |
| vectorlite / hnswlib    |     ✓      |        ✗         |      ✗       |     C++, per-platform          |
| ObjectBox               |     ✓      |        ✗         |      ✗       |     ✗ (Android/JVM + iOS SDK)  |
| SQLite FTS5             |     ✗      |        ✓         |      ✗       |     tied to SQLite             |
| **kromus**              |     ✓      |        ✓         |      ✓       |     ✓ **common code**          |

## Install

```kotlin
// build.gradle.kts — coordinates published under the kormium org's namespace
kotlin {
    sourceSets.commonMain.dependencies {
        implementation("io.github.kormium:kromus-core:0.14.0")
    }
}
```

## Quick start

kromus is **embedder-agnostic**: you bring the vectors (from any on-device or server embedding model)
as `FloatArray`s — see [Embeddings](#embeddings) — and kromus owns storage, graph construction and
retrieval.

```kotlin
import io.github.kromus.*

val index = VectorIndex<String>(dimensions = 384, metric = Metric.Cosine)

index.add("doc-1", embed("Kotlin coroutines guide"))
index.add("doc-2", embed("Structured concurrency in practice"))
index.add("doc-3", embed("Sourdough starter troubleshooting"))

val hits: List<SearchResult<String>> = index.search(embed("async programming"), k = 5)
// hits are closest-first; hits[i].score is a similarity (higher = closer)
```

Re-adding a key replaces its vector; `remove(key)` drops it from results. See the KDoc on
`VectorIndex`, `HnswConfig` and `Metric` for tuning.

To cut memory on device, store vectors quantized — queries still run at full precision (asymmetric).
`Quantization.Int8` is ~4× smaller with a small recall cost; `Quantization.Binary` is ~32× smaller
and coarse (great as a first-pass filter, typically re-ranked with full precision):

```kotlin
val index = VectorIndex<String>(384, config = HnswConfig(quantization = Quantization.Int8))
```

Binary is coarse, so pair it with a full-precision re-rank: over-fetch candidates, then re-score them
against the original vectors (which you keep — a quantized index doesn't store them). This recovers
accurate top-`k` at a fraction of the memory:

```kotlin
val coarse = index.search(query, k = 100)                       // binary: fast, approximate
val exact = rerank(query, coarse.map { it.key }, k = 10) { fullVectors[it] }
```

Attach string attributes to entries and restrict a query with a `MetadataFilter`. For vector search
the filter is applied *during* graph traversal, so a filtered query still returns up to `k` matches:

```kotlin
index.add("doc-1", embedding, attributes = mapOf("type" to "doc", "lang" to "en"))
index.search(query, k = 10) { it["type"] == "doc" && it["lang"] == "en" }
```

### Full-text and hybrid

`TextIndex` is a standalone BM25 index; `HybridIndex` combines a vector and a text index and fuses
their rankings with RRF — the recommended default, because vector search captures meaning while BM25
catches exact tokens (product codes, error strings, rare names) that embeddings miss.

The tokenizer is pluggable via `Analyzer`: `Analyzer.standard(stopwords = Stopwords.english, stemmer
= Stemmer.englishLight())` for Latin scripts, or `Analyzer.ngram(2)` for boundary-free languages
(CJK) and substring matching. Use the same analyzer for indexing and querying.

```kotlin
val index = HybridIndex<String>(dimensions = 384)

index.add("doc-1", embed("Kotlin coroutines guide"), "Kotlin coroutines guide")
index.add("doc-2", embed("Sourdough starter troubleshooting"), "Sourdough starter troubleshooting")

// fuses semantic similarity (vector) with keyword match (text)
val hits = index.search(vector = embed("async programming"), text = "coroutines", k = 10)

// or query a single modality
index.searchText("coroutines", k = 10)
index.searchVector(embed("async programming"), k = 10)
```

### Persistence

Building an HNSW graph is expensive; persist a prebuilt index and reload it instantly (ship it with
your app, or cache it on device). The format is a compact, dependency-free binary that is identical
across platforms. Analyzers are functions and are not serialized — pass the same one when reloading.

```kotlin
val bytes: ByteArray = index.encodeToByteArray(KeyCodec.string)
val reloaded = decodeHybridIndex(bytes, KeyCodec.string)      // or decodeVectorIndex / decodeTextIndex
```

The optional **`kromus-kemus`** module stores an index in a [kemus](https://github.com/kormium/kemus)
store (binary value), so it inherits kemus's persistence, TTL and offline→online sync — build once,
reload instantly:

```kotlin
index.saveTo(kemus, "my-index", KeyCodec.string)
val reloaded = loadHybridIndex(kemus, "my-index", KeyCodec.string)
```

## Use cases

kromus is a search *primitive*, so it powers more than a search box:

- **On-device search** — notes, mail, messages, documents, bookmarks. Private, offline, no server and
  no per-query cost. Hybrid (vector + BM25) plus metadata filters give both meaning and exact-token
  matches; n-gram analyzers add typo-tolerant and CJK search.
- **On-device / local RAG** — retrieve the most relevant chunks of the user's *own* data to feed a
  local (or remote) LLM, without shipping private data to a server. kromus is the retriever;
  quantization + `rerank` keep it small on device yet accurate.
- **Similarity & recommendations** — "more like this", related items, and near-duplicate detection /
  semantic dedup (contacts, tickets, media), on device or on the backend.
- **Classification & routing via k-NN** — match a query embedding against labelled exemplars for
  intent detection, auto-tagging or moderation triage. The product is a label, not a result list.
- **Semantic cache for LLM calls** — before calling the model, check whether a *similar* prompt was
  already answered; serve the cached answer and save tokens and latency.
- **Backend / edge search** — an embedded index inside a Kotlin/Ktor service for small-to-medium
  corpora (no separate search cluster), and — via the Native/Wasm targets — in edge/serverless
  runtimes where JVM-only Lucene won't run.
- **Offline-first apps with sync** — build the index, store it in a [kemus](https://github.com/kormium/kemus)
  binary value, and get persistence, TTL and offline→online sync (field, logistics, healthcare, retail).

**Where it doesn't fit:** web-scale corpora (hundreds of millions of vectors, sharded/distributed)
belong in a server-side vector DB — kromus is embedded. And it *indexes* vectors; you supply the model.

## Embeddings

kromus indexes vectors; it does not compute them — **by design**. On-device embedding models are
heavy, platform-specific (native runtimes), separately licensed and versioned, and don't cover every
KMP target uniformly. Keeping them out of the core is exactly what lets kromus stay zero-dependency
and behave identically everywhere. You produce a `FloatArray` however you like and hand it in; that
`embed(...)` in the examples is your embedder.

Where the vectors typically come from:

- **On-device (Android / iOS / desktop)**
  - **ONNX Runtime** — run a sentence-transformer exported to ONNX (e.g. `all-MiniLM-L6-v2` at 384
    dims, or `multilingual-e5-small`). One model, all mobile/desktop targets via the ONNX native libs.
  - **MediaPipe Text Embedder** (Google AI Edge) — Android / iOS.
  - **Apple NaturalLanguage** sentence embeddings — iOS / macOS.
- **Server / JVM** — any embedding API (OpenAI, Cohere, Voyage, Jina), local models via Ollama /
  llama.cpp, or JVM libraries (DJL, ONNX Runtime for the JVM).
- **No model (lexical)** — a hashing / character-n-gram vectorizer produces fuzzy *lexical* vectors
  with zero dependencies on every target: handy for demos and typo-tolerance, but **not semantic**
  (for lexical relevance, prefer `TextIndex`/BM25).

**Contract:** every vector in one index must have the same `dimensions` and come from the *same*
model — store the model id/version next to the index so you never mix embeddings from different models.

**Batteries-included?** The core stays model-free on purpose — but the optional
[`kromus-onnx`](kromus-onnx/) module is the ready-to-run path. Its `TextEmbedder` pipeline (WordPiece
tokenizer → model → pooling → normalization) is shared common code on **every** target, including the
web; only the model runtime is per-platform (JVM backend ships today, web/iOS/Android/native plug into
the same `OnnxSession`).

```kotlin
val embedder = OnnxTextEmbedder(OrtOnnxSession(modelBytes), tokenizer, dimensions = 384)
index.add("doc-1", embedder.embed("Kotlin coroutines guide"))
```

## Design principles

- **Zero dependencies** in the vector layer. HNSW is arithmetic over `FloatArray` and graph
  structures in common code — no coroutines, serialization, crypto or native interop.
- **Deterministic.** Level assignment is seeded (`HnswConfig.seed`), and the engine uses only
  fixed-order float arithmetic, so an index built from the same data on any platform ranks
  identically. Reproducibility is a feature, not an accident.
- **Reflection-free, `explicitApi()`.** The public surface is small, typed and ABI-validated.
- **Embedder-agnostic.** kromus is the index, not the model. Bring your own vectors.

## Supported targets

JVM · Android · iOS (x64/arm64/simulator) · linuxX64/Arm64 · macosX64/Arm64 · mingwX64 · JS · Wasm/JS.

## Roadmap

1. **Vector layer** ✅ HNSW ANN index, cosine / dot / euclidean, in-memory.
2. **Full-text layer** ✅ Inverted index + BM25, pluggable analyzers.
3. **Hybrid** ✅ RRF fusion of vector + full-text (`HybridIndex`).
4. **Persistence** ✅ Compact, cross-platform binary encode/decode for all three indexes.
5. **Quantization** ✅ int8 (~4×) and binary (~32×) quantization, asymmetric full-precision queries.
6. **Metadata filters** ✅ string attributes + `MetadataFilter`, applied mid-traversal for vectors.
7. **Analyzers** ✅ pluggable tokenizer: stemming, stop-words, CJK/substring n-grams.
8. **kemus storage** ✅ optional `kromus-kemus` adapter — persist an index into a
   [kemus](https://github.com/kormium/kemus) store (embedded / offline→online sync).
9. **Re-rank** ✅ `rerank(query, candidates, k) { fullVector }` — two-phase search for quantized indexes.
10. **Embeddings** ✅ optional [`kromus-onnx`](kromus-onnx/) — a `TextEmbedder` whose pipeline is shared
    on every target, with **`OnnxSession` backends for JVM, Android, web (JS + Wasm), iOS and desktop-native**.
11. **Sync** ✅ optional [`kromus-sync`](kromus-sync/) — keep an index fresh from a `Flow<List<T>>`
    snapshot stream (e.g. `kormium-observe`); reconciles new/changed/removed with no data-layer dep.
12. **Next** — publish `kromus-kemus`/`kromus-onnx`/`kromus-sync` to Maven Central.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
