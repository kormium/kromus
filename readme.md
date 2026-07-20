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

> **Status:** `0.7.0`, pre-1.0. All three layers, binary persistence, int8/binary quantization,
> metadata filters and pluggable analyzers (stemming, stop-words, CJK n-grams) are usable today; the
> API may still change before 1.0. See the roadmap for what's next.

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
        implementation("io.github.kormium:kromus-core:0.7.0")
    }
}
```

## Quick start

kromus is **embedder-agnostic**: you bring the vectors (from any on-device or server embedding model
— ONNX Runtime, MediaPipe Text Embedder, a backend API, …) as `FloatArray`s. kromus owns storage,
graph construction and retrieval.

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
10. **Next** — publish `kromus-kemus` once kemus is on Maven Central; more built-in analyzers.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
