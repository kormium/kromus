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

> **Status:** `0.16.0`, pre-1.0. All three layers, binary persistence, int8/binary quantization,
> metadata filters, pluggable analyzers, full-precision re-rank, graph compaction, an optional kemus
> storage adapter and an optional `kromus-onnx` embedder are usable today; the API may still change
> before 1.0. See the [changelog](CHANGELOG.md) for what has moved and the roadmap for what's next.
>
> **API reference:** [kormium.github.io/kromus](https://kormium.github.io/kromus/) — all four
> modules, cross-linked, rebuilt from each release tag.

## Why it exists

On-device semantic search is now table stakes for AI features (private, offline, no per-query cost),
and on the JVM and Android there are already good ways to get it. What there is not, as far as we can
find, is **one implementation in common code that covers the whole KMP matrix** — Android *and* iOS
*and* Native *and* Wasm — and that puts vector, full-text and hybrid retrieval in a single artifact.
That is the gap kromus fills, and it is a narrow one: the claim is about coverage in common code, not
about being the only vector search available to Kotlin.

|                                    | ANN / HNSW | Full-text / BM25 | Hybrid + RRF | Runs on the whole KMP matrix |
| ---------------------------------- | :--------: | :--------------: | :----------: | :--------------------------- |
| [sqlite-vec][sv]                   | ✗ (brute)  |        ✗         |      ✗       | C extension, per-platform    |
| [sqlite-vector][svec]              | ✗ (brute)  |        ✗         |      ✗       | C extension; Elastic License |
| vectorlite / [hnswlib (C++)][hl]   |     ✓      |        ✗         |      ✗       | C++, per-platform            |
| [hnswlib (Java)][jh]               |     ✓      |        ✗         |      ✗       | ✗ — JVM/Android only         |
| [JVector][jv]                      |     ✓      |        ✗         |      ✗       | ✗ — JVM only                 |
| [ObjectBox][ob]                    |     ✓      |        ✗         |      ✗       | ✗ — separate Java and Swift SDKs |
| SQLite FTS5                        |     ✗      |        ✓         |      ✗       | tied to SQLite               |
| **kromus**                         |     ✓      |        ✓         |      ✓       | ✓ **common code**            |

[sv]: https://github.com/asg017/sqlite-vec
[svec]: https://github.com/sqliteai/sqlite-vector
[hl]: https://github.com/nmslib/hnswlib
[jh]: https://github.com/jelmerk/hnswlib
[jv]: https://github.com/datastax/jvector
[ob]: https://objectbox.io/

Worth being precise about the neighbours, because "there is nothing else" would be false:

- **On the JVM and Android you do not need a native binary.** [hnswlib for Java][jh] is a pure-JVM HNSW
  on Maven Central — thread-safe, serializable, incremental. [JVector][jv] is a more advanced embedded
  engine in the same slice. Neither reaches iOS, Native or Wasm, which is the only reason kromus exists
  rather than a wrapper around one of them.
- **ObjectBox is not a paid product.** Its language bindings are Apache 2.0 and its core is documented
  as always free to use; what it is not is open-source at the core, or one artifact — vector search is
  offered to Java/Kotlin and to Swift as separate SDKs.
- **sqlite-vec may not stay brute-force.** Its [ANN tracking issue][annissue] has been open since 2024
  and is still choosing between IVF, HNSW and DiskANN, but that is a matter of when, not whether.

[annissue]: https://github.com/asg017/sqlite-vec/issues/25

Absence of evidence is not evidence of absence: this is what a search of the ecosystem turned up in
August 2026, not a proof that no common-code alternative exists. If you know of one, an issue
correcting this table is welcome.

## Install

```kotlin
// build.gradle.kts — coordinates published under the kormium org's namespace
kotlin {
    sourceSets.commonMain.dependencies {
        implementation("io.github.kormium:kromus-core:0.16.0")

        // Optional companion modules — see their own readmes for details.
        implementation("io.github.kormium:kromus-files:0.16.0") // search an index from a file
        implementation("io.github.kormium:kromus-kemus:0.16.0") // persist into a kemus store
        implementation("io.github.kormium:kromus-onnx:0.16.0")  // on-device text embedder
        implementation("io.github.kormium:kromus-sync:0.16.0")  // keep an index fresh from a Flow
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

### Searching on several threads

A search reads the graph and changes nothing in it, but it is still not safe to run two at once on the
same index: a traversal's working state — visited marks, candidate heaps, layer buffers — is reused
between calls, which is what makes a query allocate almost nothing. Unguarded, concurrent searches
mostly throw, and the ones that survive return the wrong neighbours.

A **searcher** owns that state, so searchers do not interact:

```kotlin
// one per thread or coroutine, not one shared between them
val searcher = index.searcher()
val hits = searcher.search(queryEmbedding, k = 10)
```

The index's own contract is unchanged: **nothing may write to it while a search runs.** Searchers make
reads parallel, not reads-and-writes concurrent. If something is writing — a sync keeping the index
fresh — use [`kromus-sync`](kromus-sync/)'s `.concurrent()` wrapper, which arranges both: searches run
alongside each other, a writer runs alone, and a writer is not starved by a steady stream of searches.

### Three index types

| | what it is | when |
| --- | --- | --- |
| `FlatIndex` | exhaustive scan, no structure | small corpora, and as the exact answer to check the others against |
| `VectorIndex` | HNSW graph | the index is resident and queried in the same process |
| `IvfIndex` | inverted file: k-means centroids, one list per centroid, `nprobe` lists opened per query | the index is built elsewhere and read from a file |

They are the standard structures under their standard names, so what is known about tuning each
applies here unchanged.

```kotlin
// exhaustive and exact — no build step, nothing to tune
val flat = FlatIndex.build(dimensions = 384, entries = docs)

// a graph — the best in-memory structure
val graph = VectorIndex<String>(dimensions = 384).also { docs.forEach { d -> it.add(d.id, d.vector) } }

// an inverted file — the best structure on a file
val ivf = IvfIndex.build(dimensions = 384, entries = docs)
```

**IVF needs no tuning to start.** How many lists a query must open is a property of the data — one
where the corpus partitions cleanly, dozens where it barely does — so `build` measures the corpus and
picks. `index.nprobe` is what it chose and `index.estimatedRecall` what that was measured to be worth;
the estimate is optimistic on hard corpora and says so.

**The graph is better in memory**, because it is guided by real distances to real vectors at every hop
where a partitioned index commits to a set of lists before looking at a single point. **The inverted
file is better on a file**, because a list is a contiguous run: at equal recall on clusterable data
that is 10 pages read against 43. No arrangement of a file gives a graph the same thing — its access
pattern is the algorithm.

#### SPANN

[SPANN](https://arxiv.org/abs/2111.08566) is not a fourth structure. It is `IvfIndex` with three
choices made together, and it is available as a preset:

```kotlin
val index = IvfIndex.build(
    dimensions = 384,
    entries = docs,
    config = IvfPresets.spann(entryCount = docs.size, postingSize = 128),
)
```

- **many small lists**, so a query reads little — sized by how many entries a list should hold rather
  than by a list count, because the size of the read is what matters;
- **`Routing.Graph`**, because scanning that many centroids would itself become the expensive part —
  the router is the same HNSW the library already has, applied to the centroids;
- **redundant assignment**, so a vector near a boundary sits in both lists and stops being lost to
  whichever side a query arrives from. It costs storage: a vector in two lists is stored twice, which
  is deliberate — disk is cheaper than the recall lost at a boundary, and the duplicate is what keeps
  each list contiguous.

The fourth part of the design, postings that live on disk rather than in memory, is a loader rather
than a setting — see [`openIvfIndex`](#reading-vectors-without-holding-them).

#### Reading vectors without holding them

`openIvfIndex` and `openFlatIndex` take a `ByteSource` and read through it — one list at a time for
IVF, in batches for a flat scan — instead of inflating the file. Only the header and the small
sections are read when the index opens: centroids, the list table, keys and attributes, a few
megabytes where the vectors are tens. The vector region is not touched until a query asks for a
cluster.

```kotlin
val source = FileByteSource.open("$filesDir/catalogue.krm")   // kromus-files
val index = openIvfIndex(source, KeyCodec.string)

val hits = index.search(queryVector, 10)   // reads the probed clusters, nothing else
source.close()                              // the index stops working here, not before
```

`ByteArraySource` over a blob you already hold is the honest baseline rather than a saving — the array
is still there. The memory is reclaimed when the source reads from somewhere that is not the heap.

**What that costs, measured on the access pattern rather than argued:** opening a 600-entry index
reads less than its vector section occupies, and ten queries at `nprobe = 3` together read less than
the file — the assertions are in `ByteSourceTest`, against the size of the vector region rather than
against a number chosen to pass.

`kromus-files` ships a source for every platform that has synchronous file access:

| source | where | notes |
| --- | --- | --- |
| `FileByteSource` | JVM, Android | positional `FileChannel` reads; one open file serves every thread |
| `FileByteSource` | iOS, macOS, Linux, Windows | buffered `fopen`/`fseek`/`fread`; one source per thread |
| `NodeFileByteSource` | Node (JS and Wasm) | `fs.readSync` |
| `OpfsByteSource` | browser (JS and Wasm) | **worker only** — see below |

`openRange` on the first three opens an index packed inside a larger file, which is the shape an
Android asset arrives in: an `AssetFileDescriptor` gives the APK's path with the entry's offset and
length. Note that an APK asset is compressed by default and cannot be read at an offset at all —
either mark it `noCompress`, or copy it into `filesDir` once on first run.

In the browser, the only synchronous file read is OPFS's `createSyncAccessHandle`, and it exists
**only inside a Web Worker**. That is a constraint of the platform, not a choice made here — so a
file-backed search runs in a worker and posts its results back, which is where a scan over thousands
of vectors belongs anyway. `OpfsByteSource` takes an already-open handle rather than opening one, so
that everything asynchronous stays with the caller and `ByteSource` can stay synchronous.

Honest about coverage: the OPFS sources are tested against a stand-in handle that reproduces the
browser's contract, including reads that return fewer bytes than asked for. The binding to the
browser's own object is not exercised by CI, because a test runner drives the main thread and a real
handle cannot be created there.

### Writing your own

The three index types are implementations, not the extent of what fits. Four seams are public so that
a structure, a quantizer or a place to read bytes from that kromus does not ship can be added without
waiting for it to be added here.

**`VectorSearch<K>`** — what an index is, from a caller's side: dimensions, metric, keys, and a
`searcher()`. Write against it and the choice between flat, graph and inverted file becomes
configuration rather than a rewrite. A fourth implementation satisfies it by answering queries; nothing
in it assumes a graph or a partitioning.

**`VectorStore`** — how vectors are stored and how distance to them is computed. This is the quantizer
seam. The built-in trades are full precision, 8 bits and 1 bit; a different trade is an implementation
of this interface and nothing else:

```kotlin
class MyQuantizer(override val dimensions: Int, override val metric: Metric) : VectorStore {
    override val strideBytes: Int get() = /* bytes one vector takes */
    override fun add(prepared: FloatArray): Int = /* … */
    override fun distanceToQuery(query: FloatArray, id: Int): Float = /* … */
    override fun writeVector(id: Int, out: ByteWriter) { /* … */ }
    override fun readVector(from: ByteReader) { /* … */ }
    // distanceBetween, reconstruct, size
}

val mine = VectorStoreFactory { d, m -> MyQuantizer(d, m) }

val flat = FlatIndex.build(384, entries, store = mine)
val ivf = IvfIndex.build(384, entries, store = mine)
val graph = VectorIndex<String>(384, store = mine)

val reloaded = decodeVectorIndex(bytes, KeyCodec.string, store = mine)
```

All three index types take one, and so does each of `decodeVectorIndex`, `decodeIvfIndex` and
`decodeFlatIndex`. `VectorIndex` keeps the factory, because `compact()` and `clear()` rebuild the
graph and would otherwise rebuild it on different storage.

Nothing in the bytes names the quantizer, so the same factory is supplied when reading. That is the
honest contract: the alternative is a registry of quantizer ids, which buys nothing until more than
one program reads the file. What *is* checked is the stride — a file whose vector section is not
`size × strideBytes` long is refused before a record is read, so supplying a store of the wrong width
fails loudly rather than quietly. One of the same width does not; that much is on you.
[ScaNN](https://arxiv.org/abs/1908.10396)'s anisotropic quantization is a store; so is int4, so is
product quantization — and `rerank`, the other half of that pipeline, already ships.

**`ContainerWriter` / `ContainerReader`** — the file format: named sections, per-section checksums,
provenance, a kind byte and a version. An index of your own gets all of it, and the same diagnostics,
rather than inventing a format beside kromus's.

**`ByteSource`** — where an index's bytes come from. Three methods: `size`, `read(offset, length,
into, at)` and `close()`. `kromus-files` implements it over a file on every platform that has
synchronous file access, and anything else is the same interface and nothing more — a decrypting
reader, an Android `AssetFileDescriptor`, a virtual file system, a cache that fetches and spills to
local storage.

The one rule that is not optional: **`read` must fill exactly what was asked for.** Returning early is
the single failure this interface cannot detect — a partly-filled buffer leaves whatever was there
before in the tail, and the scan reads it as vector data. Nothing throws, and the query comes back
with neighbours that are merely plausible. Loop until the range is complete, and throw
`KromusFormatException` if it cannot be. Every source in `kromus-files` is tested against a stand-in
that deliberately reads short.

This one does not combine with a custom `VectorStore`: a streamed scan reads the stored bytes directly
and only the built-in layouts are known to it, so a store of your own is decoded rather than
streamed.

What is deliberately *not* open is `Metric`. It is a closed enum, and exhaustive `when` over it is what
lets each quantizer specialize its arithmetic — the binary store's per-query lookup table among them.
Opening it means either losing that or asking every store to handle a metric it has never seen; the
cost is real and the gain smaller than the seams above.

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

Encoding is deterministic: the same index content produces the same bytes on every platform — and
across a reload, since records and the string-keyed maps inside them are written in a fixed order
rather than in whatever order a map happens to iterate. So an index can be content-hashed, cached by
digest, or compared in a test.

#### Where an index gets built

There is one way to build an index — `add`, in common code — and it runs wherever you run it. Because
construction and encoding are deterministic and identical on every target, the same code produces the
same bytes on a phone, on a server, or in a Gradle task. What differs is not the building but the
deployment, and there are two shapes:

**Built where it is used.** The index lives, changes and is searched in the same process.
[`kromus-sync`](kromus-sync/) keeps it in step with your data and `.concurrent()` guards it against a
writer and readers sharing it. This is the shape for content the user creates.

**Built elsewhere, read here.** Build on a server or in CI, ship the bytes, and the device only loads
and searches. The two costs a device is worst at — running an embedding model over the whole corpus,
and constructing the graph — are paid once on a machine that does not mind. Nothing writes to the
index on device, so there is no lock to take: hand each thread a [`searcher()`](#searching-on-several-threads).
This is the shape for content you ship. [`samples/prebuilt`](samples/prebuilt/) builds its index in a
Gradle task and loads it from resources.

"On a server" and "at compile time" are the same shape with a different build machine — same bytes,
same guarantees, same loading code. And the two shapes compose: a prebuilt index over your content
plus a small local one over the user's, searched together and fused, which is what most apps actually
want.

Two things do not move to the server. **Queries are still embedded on the device**, with the model the
corpus was embedded with — which is what the next section is about. And the whole blob is still
inflated into memory on load, which is the ceiling on how large a shipped index can usefully be
([#27](https://github.com/kormium/kromus/issues/27)).

#### Recording what built an index

An index means nothing on its own. Its vectors are only comparable to queries embedded by the *same*
model, and its terms only match queries tokenized by the *same* analyzer — and neither travels in the
bytes. Pair a blob with the wrong one and nothing throws; the results are simply wrong.

That is easy to avoid while you build and search in one process, and easy to get wrong the moment an
index is built somewhere else — on a server, in CI, by a colleague. So a blob can carry what produced
it, and loading can insist:

```kotlin
// wherever the index is built
val blob = index.encodeToByteArray(KeyCodec.string, provenance = "all-MiniLM-L6-v2/mean/l2")

// wherever it is loaded — throws KromusFormatException if the two disagree
val index = decodeHybridIndex(blob, KeyCodec.string, expect = "all-MiniLM-L6-v2/mean/l2")

provenanceOf(blob)   // read it without decoding, e.g. to decide whether to fetch a newer asset
```

The string is opaque to kromus — put in whatever identifies the pairing: model name and revision,
analyzer configuration, corpus date. The guard is opt-in: pass no `expect` and nothing is checked.

#### Saving only what changed

A full encode rewrites everything, however little moved. That is right for "build once, ship it" and
wrong for an index a sync keeps in step with changing data — at 50 000 vectors it is tens of
megabytes after every batch, which on device is a write-amplification problem, not just a slow one.

`encodeDelta` writes only what changed since the last save, and the decoders take a base plus the
deltas recorded after it:

```kotlin
val base = index.encodeToByteArray(KeyCodec.string)   // also checkpoints the index

index.add("doc-99", embed("something new"))
val delta = index.encodeDelta(KeyCodec.string)        // null if nothing changed

val reloaded = decodeHybridIndex(base, listOf(delta!!), KeyCodec.string)
```

An insert relinks tens of existing nodes, so a delta is not a plain append — but a stored vector is
immutable, so a changed node owes only its adjacency and never its vector. That is what keeps a delta
small. See the numbers [below](#incremental-persistence).

Deltas accumulate; fold the chain back into a snapshot periodically by decoding it and re-encoding.
`dirtyNodes` says how much has changed, and `needsFullSnapshot` says when a delta is not an option —
`compact()` and `clear()` renumber internal ids, and deltas are written in terms of them. A delta
names the revision it applies to, so replaying one out of order, skipping one, or mixing in a delta
from a different index is rejected rather than quietly corrupting the result.

The optional **[`kromus-kemus`](kromus-kemus/)** module stores an index in a [kemus](https://github.com/kormium/kemus)
store (binary value), so it inherits kemus's persistence, TTL and offline→online sync — build once,
reload instantly:

```kotlin
index.saveTo(kemus, "my-index", KeyCodec.string)
val reloaded = loadHybridIndex(kemus, "my-index", KeyCodec.string)
```

## API reference

Full KDoc for every published module is at **[kormium.github.io/kromus](https://kormium.github.io/kromus/)**,
generated from the release tag and cross-linked across `kromus-core`, `kromus-kemus`, `kromus-onnx`
and `kromus-sync`. Most of the reasoning that is not in this readme lives there: what `efSearch`
trades, when `rerank` earns its second pass, why a `remove` leaves a routing hop behind, how a
metadata filter is applied mid-traversal.

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
| `None` | 27.8 MiB | 21.9 s | 0.997 | 145 µs |
| `Int8` | 9.7 MiB | 19.9 s | 0.986 | 149 µs |
| `Binary` | 4.2 MiB | 11.5 s | 0.283 | 73 µs |
| `Binary` + re-rank | 4.2 MiB | — | 0.845 | 189 µs |

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

### Incremental persistence

What a save costs after a batch of edits. Both columns are measured against the same index state, so
the crossover is real rather than an artefact of comparing against a stale snapshot.

| edits | index | full encode | delta | smaller by | full | delta |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 50 001 | 30.7 MiB | 5.6 KiB | 5578× | 99 ms | 3.9 ms |
| 10 | 50 011 | 30.7 MiB | 31.7 KiB | 990× | 71 ms | 1.4 ms |
| 100 | 50 111 | 30.7 MiB | 273.8 KiB | 115× | 99 ms | 4.1 ms |
| 1000 | 51 111 | 31.3 MiB | 2.3 MiB | 14× | 87 ms | 19.4 ms |

The gap narrows as the batch grows, which is the signal to fold: once a batch is worth a sizeable
fraction of the index, a delta stops being a bargain and a fresh snapshot is the better save.

### Graph versus clusters, at equal recall

Comparing the two at fixed settings says nothing — a graph at `efSearch = 64` and clusters at
`nprobe = 8` are two arbitrary points, and whichever scores better is an artefact of the corpus size.
The question that means something is: **to return the same answers, which one reads less?** So both
are tuned to the same target and compared on pages touched.

`spread` is how far the corpus drifts from the centroids it was generated from — low is cleanly
clustered, high is nearly structureless. Read it first: a corpus built *from* centroids is the best
case a clustered index can meet, and saying so is what separates a measurement from an advertisement.

| spread | index | setting | recall@10 | pages/query |
| --- | --- | --- | --- | --- |
| 0.5 | HNSW | efSearch=16 | 1.000 | 43 |
| 0.5 | clusters | nprobe=1 of 70 | 1.000 | **10** |
| 1.0 | HNSW | efSearch=16 | 0.992 | 51 |
| 1.0 | clusters | nprobe=1 of 70 | 1.000 | **10** |
| 2.0 | HNSW | efSearch=128 | 0.956 | 301 |
| 2.0 | clusters | nprobe=26 of 70 | 0.892 | 246 |

Where the corpus has group structure, the clustered index answers as well for a fifth of the reading,
and finds its own `nprobe = 1` without being told. Where it does not, the advantage nearly vanishes:
slightly fewer pages, and less recall for them. **Which case you are in is a property of your data,
not a setting** — build both and compare, it costs one afternoon.

### Parallel search

One index, one searcher per thread, no lock.

| threads | searches/sec | vs 1 thread | mean query |
| --- | --- | --- | --- |
| 1 | 4 986 | 1.00× | 201 µs |
| 2 | 10 334 | 2.07× | 194 µs |
| 4 | 23 115 | 4.64× | 173 µs |
| 8 | 40 150 | 8.05× | 199 µs |
| 16 | 46 005 | 9.23× | 348 µs |

Near-linear to the physical cores. Past them throughput still climbs but latency does too — 16 threads
buy 15% more work for 75% slower queries, which is the wrong trade if anyone is waiting on one. How
far it scales depends on whether the working set still fits cache, so read the row your corpus sits
at rather than the last one.

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
  structures in common code — no coroutines, serialization, crypto or native interop. That holds for
  `kromus-core` in full: reading an index from a file needs platform APIs, so the interface lives in
  core and the implementations live in `kromus-files`, which is optional and separate for exactly
  this reason.
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
8. **kemus storage** ✅ optional [`kromus-kemus`](kromus-kemus/) adapter — persist an index into a
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
17. **Incremental persistence** ✅ `encodeDelta` writes only what changed; decoders replay a base plus
    its deltas, with the chain checked so a stray delta cannot be applied.
18. **Parallel search** ✅ `searcher()` gives a reader its own traversal state, so searches run on every
    core; `kromus-sync`'s wrappers pair that with a writer-preferring lock.
19. **Sectioned format** ✅ every index is a container of named, checksummed sections — denser, locatable
    corruption, and readable in parts.
20. **Clustered index** ✅ `IvfIndex` groups the corpus instead of linking it, trading recall for
    the contiguity a file-backed index needs; `IvfConfig.assignments` and `.routing` make it a SPANN
    one, and `FlatIndex` is exhaustive search for when an index is not needed at all.
21. **Open seams** ✅ `VectorSearch`, `VectorStore`, `ContainerWriter`/`ContainerReader` and
    `ByteSource` are public, so an index type, a quantizer or a source of bytes kromus does not ship
    can be written against the same format and the same guarantees.
22. **Search from a file** ✅ `openIvfIndex`/`openFlatIndex` read through a `ByteSource`, and
    [`kromus-files`](kromus-files/) implements one on JVM, Android, iOS, macOS, Linux, Windows, Node
    and the browser (OPFS, in a worker). The ceiling stops being memory and becomes storage.

Next: multi-value and numeric metadata filters, an incremental "add to a persisted index without a
full re-encode" path, and SIMD-friendly distance kernels where a platform offers them without
breaking the one-implementation rule.

## Contributing

Bug reports, failing tests and measured performance work are all welcome — see
[CONTRIBUTING.md](CONTRIBUTING.md) for the setup, the three properties the library will not trade
away, and what a change to the public API or the persistence format requires.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
