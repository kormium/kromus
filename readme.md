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

> **Status:** `0.15.0`, pre-1.0. All three layers, binary persistence, int8/binary quantization,
> metadata filters, pluggable analyzers, full-precision re-rank, graph compaction, an optional kemus
> storage adapter and an optional `kromus-onnx` embedder are usable today; the API may still change
> before 1.0. See the [changelog](CHANGELOG.md) for what has moved and the roadmap for what's next.

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
        implementation("io.github.kormium:kromus-core:0.15.0")

        // Optional companion modules — see their own readmes for details.
        implementation("io.github.kormium:kromus-kemus:0.15.0") // persist into a kemus store
        implementation("io.github.kormium:kromus-onnx:0.15.0")  // on-device text embedder
        implementation("io.github.kormium:kromus-sync:0.15.0")  // keep an index fresh from a Flow
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

A quantized index does not keep the originals, so `fullVectors` is yours to hold. An unquantized index
does keep them, and `index.vectorOf(key)` hands them back — useful for re-ranking a fused result list
without a second copy of the corpus.

Attach string attributes to entries and restrict a query with a `MetadataFilter`. For vector search
the filter is applied *during* graph traversal, so a filtered query still returns up to `k` matches:

```kotlin
index.add("doc-1", embedding, attributes = mapOf("type" to "doc", "lang" to "en"))
index.search(query, k = 10) { it["type"] == "doc" && it["lang"] == "en" }
```

Mid-traversal filtering is what lets a filtered query still return `k` results — but it has a cost
worth knowing about. Rejected entries are traversed and then discarded, so a filter that matches
almost nothing keeps the search from ever filling its candidate list and it ends up walking the whole
graph. `maxVisited` caps that: the query stops early and returns the matches it found.

```kotlin
index.search(query, k = 10, maxVisited = 2000) { it["type"] == "doc" }
```

Set it per query, or once in `HnswConfig(maxVisited = …)`. The [benchmarks](#benchmarks) below show
what it saves. To change an entry's metadata without re-indexing it, use `updateAttributes` — a
re-`add` would insert a whole new vector and leave the old one behind as a tombstone.

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

### Keeping an index fresh

`remove(key)`, and re-`add`ing a key that already exists, do not free anything: the old vector stays
in memory and remains in the graph as a routing hop, which is what keeps the graph connected and
lookups correct. Over a long-lived index on changing data those tombstones accumulate — memory grows
and queries slow down, because the search walks nodes it can never return.

`compact()` is the reclaim step. It rebuilds the graph over the live entries only:

```kotlin
if (index.tombstones > index.size / 2) index.compact()
```

It costs a full rebuild, so trigger it on churn rather than per edit — at app start, or after a bulk
sync. Nothing is re-approximated: quantized codes survive a rebuild bit-exact, and a full-precision
index comes out byte-identical to one built from the same entries.

`updateAttributes(key, …)` exists so metadata edits never create a tombstone in the first place.

### Persistence

Building an HNSW graph is expensive; persist a prebuilt index and reload it instantly (ship it with
your app, or cache it on device). The format is a compact, dependency-free binary that is identical
across platforms. Analyzers are functions and are not serialized — pass the same one when reloading.

```kotlin
val bytes: ByteArray = index.encodeToByteArray(KeyCodec.string)
val reloaded = decodeHybridIndex(bytes, KeyCodec.string)      // or decodeVectorIndex / decodeTextIndex
```

A persisted index outlives the build that wrote it, so decoding is defensive: blobs carry a magic
header and a format version, every read is bounds-checked, and anything unreadable — a truncated
file, a stale cache from an older format — raises a `KromusFormatException` rather than crashing.
Pre-1.0 the format can change between versions, so treat a rebuild as the migration path:

```kotlin
val index = try {
    decodeHybridIndex(cached, KeyCodec.string)
} catch (e: KromusFormatException) {
    buildIndexFromScratch()          // and cache the new bytes
}
```

Encoding is deterministic: the same index content produces the same bytes on every platform, so an
index can be content-hashed, cached by digest, or compared in a test.

The optional **`kromus-kemus`** module stores an index in a [kemus](https://github.com/kormium/kemus)
store (binary value), so it inherits kemus's persistence, TTL and offline→online sync — build once,
reload instantly:

```kotlin
index.saveTo(kemus, "my-index", KeyCodec.string)
val reloaded = loadHybridIndex(kemus, "my-index", KeyCodec.string)
```

## Examples

Runnable samples live in [`samples/`](samples/): `:quickstart`, `:hybrid`, `:quantization`, `:sync`
(readable toy embedder), plus **`./gradlew :samples:onnx:run`** — real semantic search with a genuine
`all-MiniLM-L6-v2` model (auto-downloaded via `kromus-onnx`).

## Benchmarks

Numbers from `./gradlew :benchmarks:run` — 50 000 vectors × 128 dims, 200 queries, single-threaded on
one JVM. They are machine-specific; what travels is the shape of the curves and the ratios between
modes. Reproduce them yourself, or point the suite at your own sizes with
`--args="--vectors 200000 --dim 384"`.

Recall is only meaningful next to the corpus it was measured on. A tightly clustered corpus makes any
graph index look perfect, and one that is effectively uniform noise defeats them all — so the suite
reports the dataset's **contrast** (how much closer the true 10th neighbour is than an average corpus
member) alongside every result. The runs below sit at 2.2×, in the range real embedding corpora
occupy.

Exact brute-force search over the same corpus costs **12 950 µs** per query. That is the number an
approximate index exists to beat.

### Recall and latency

Full precision, `m = 16`, `efConstruction = 200`, k = 10.

| `efSearch` | recall@10 | mean query | p95 query | vs brute force |
| --- | --- | --- | --- | --- |
| 16 | 0.862 | 84 µs | 123 µs | 154× |
| 32 | 0.964 | 105 µs | 143 µs | 123× |
| 64 | 0.997 | 167 µs | 246 µs | 78× |
| 128 | 1.000 | 219 µs | 277 µs | 59× |
| 256 | 1.000 | 325 µs | 425 µs | 40× |

The default `efSearch = 64` gives essentially exact results roughly 80× faster than scanning the
corpus. Raise it when recall matters more than latency; lower it for the reverse.

### Quantization

Same graph settings throughout, `efSearch = 64`. "Serialized" is what `encodeToByteArray` produces —
the bytes you actually ship or cache.

| mode | serialized | build | recall@10 | mean query |
| --- | --- | --- | --- | --- |
| `None` | 30.7 MiB | 21.9 s | 0.997 | 145 µs |
| `Int8` | 12.5 MiB | 19.9 s | 0.986 | 149 µs |
| `Binary` | 7.0 MiB | 11.5 s | 0.283 | 73 µs |
| `Binary` + re-rank | 7.0 MiB | — | 0.845 | 189 µs |

`Int8` is close to free: 2.5× smaller for one point of recall. `Binary` is 4.4× smaller and the
fastest to build and query, but on its own it is genuinely coarse — pair it with a full-precision
re-rank, which is what the last row measures (over-fetch 100, re-score, keep 10).

### Selective filters, and what a budget buys

One entry in 200 matches the filter here — the case that makes filtered vector search expensive,
because rejected entries are still traversed.

| query | mean latency | hits returned (k = 10) |
| --- | --- | --- |
| unfiltered | 155 µs | 10.0 |
| filtered, no budget | 14 835 µs | 10.0 |
| filtered, `maxVisited = 2000` | 573 µs | 4.5 |
| filtered, `maxVisited = 500` | 134 µs | 0.4 |

Unbounded, a filter this selective costs 96× an ordinary query — it walks most of the graph to fill
its result. A budget is a real trade, not a free win: it buys back the latency and gives up results.
Pick it against your own filters and how many hits the screen actually needs.

### Churn and compaction

Every entry replaced once — the shape of an index kept in step with changing data.

| state | live entries | graph slots | serialized | mean query |
| --- | --- | --- | --- | --- |
| fresh | 50 000 | 50 000 | 30.7 MiB | 151 µs |
| after replacing every entry | 50 000 | 100 000 | 60.5 MiB | 386 µs |
| after `compact()` | 50 000 | 50 000 | 30.7 MiB | 215 µs |

Left alone, one round of replacements doubles the memory and makes queries 2.6× slower. `compact()`
takes it back, at the cost of a full rebuild (29 s here) — which is why it belongs at app start or
after a bulk sync, not on the edit path.

### Recall vs corpus hardness

10 000 vectors, `efSearch = 64` — the context every recall number needs.

| cluster spread | contrast | recall@10 |
| --- | --- | --- |
| 0.5 | 4.64× | 1.000 |
| 1.0 | 2.03× | 1.000 |
| 2.0 | 1.41× | 0.850 |
| 4.0 | 1.37× | 0.647 |

As a corpus approaches uniform noise its true neighbours stop being meaningfully nearer than random
points, and recall falls away — for any graph index, not just this one. If your own recall looks
disappointing, measure the contrast of your embeddings before blaming the index.

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

## Concurrency

An index is a single-writer structure and is **not thread-safe**: locking inside the traversal loop
would tax the common case of building and querying in one place. The on-device case is often not that
case, though — a background sync or an import writes while the UI searches — so `kromus-sync` ships a
guard for it:

```kotlin
val index = HybridIndex<String>(dimensions = 384).concurrent()

scope.launch { docs.observe(db).syncTo(index, keyOf = { it.id }) { HybridDoc(embed(it.body), it.body) } }

val hits = index.search(embed(query), text = query, k = 10)   // safe from any coroutine
```

The wrapper takes a `Mutex` around every operation, and the `syncTo` overloads for it run the
embedding *outside* the lock, so a slow model never blocks a search. It lives in `kromus-sync` rather
than the core because it needs coroutines, and the core has no dependencies.

## Design principles

- **Zero dependencies** in the vector layer. HNSW is arithmetic over `FloatArray` and graph
  structures in common code — no coroutines, serialization, crypto or native interop.
- **Deterministic.** Level assignment is seeded (`HnswConfig.seed`), float arithmetic runs in a fixed
  order, BM25 scores accumulate in query order and break ties by insertion order, and serialization
  writes records by id rather than by hash iteration order. So an index built from the same data on
  any platform ranks identically *and* encodes to identical bytes. Reproducibility is a feature, not
  an accident.
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
12. **Maven Central** ✅ `kromus-core`, `kromus-kemus`, `kromus-onnx` and `kromus-sync` are all published.
13. **Compaction** ✅ `compact()` rebuilds a graph over its live entries, reclaiming the tombstones that
    removals and replacements leave behind; `updateAttributes` avoids creating them at all.
14. **Traversal budget** ✅ `maxVisited` bounds the cost of a highly selective metadata filter.
15. **Benchmarks** ✅ a suite covering recall, latency, quantization, filters and churn — see above.
16. **Concurrency** ✅ optional `Mutex`-guarded index wrappers in `kromus-sync` for background writers.

Next: multi-value and numeric metadata filters, an incremental "add to a persisted index without a
full re-encode" path, and SIMD-friendly distance kernels where a platform offers them without
breaking the one-implementation rule.

## Contributing

Bug reports, failing tests and measured performance work are all welcome — see
[CONTRIBUTING.md](CONTRIBUTING.md) for the setup, the three properties the library will not trade
away, and what a change to the public API or the persistence format requires.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
