# kromus

**An embedded, reflection-free hybrid search engine for Kotlin Multiplatform.**

kromus is a pure-Kotlin search index that runs *inside* your app — on JVM, Android, iOS, Native,
and the web (Wasm/JS) — with **one implementation and identical behaviour on every target**. No
native library to link, no per-platform build, no server.

It ships in layers:

- **Vector search** *(v0, this release)* — a pure-Kotlin [HNSW](https://en.wikipedia.org/wiki/Hierarchical_navigable_small_world)
  approximate-nearest-neighbour index for semantic / similarity search over embeddings.
- **Full-text search** *(planned)* — an inverted index with BM25 ranking and pluggable analyzers.
- **Hybrid queries** *(planned)* — vector + full-text fused with Reciprocal Rank Fusion (RRF), the
  2026 best practice that lifts recall well above either retriever alone.

> **Status:** `0.1.0`, pre-1.0. The vector layer is usable today; the API may still change before 1.0.

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
| **kromus**              |     ✓      |    ✓ *(planned)* | ✓ *(planned)*|     ✓ **common code**          |

## Install

```kotlin
// build.gradle.kts — coordinates published under the kormium org's namespace
kotlin {
    sourceSets.commonMain.dependencies {
        implementation("io.github.kormium:kromus-core:0.1.0")
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

1. **v0 — vector layer** ✅ HNSW ANN index, cosine / dot / euclidean, in-memory.
2. **v1 — persistence & quantization.** Serializable index; int8/binary quantization to fit larger
   corpora on-device. Optional integration with [kemus](https://github.com/kemus/kemus) as the store.
3. **v1 — full-text layer.** Inverted index + BM25, pluggable analyzers (tokenization, stemming,
   CJK n-grams).
4. **v2 — hybrid.** RRF fusion of vector + full-text, metadata filters.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
