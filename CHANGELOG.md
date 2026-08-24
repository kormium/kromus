# Changelog

All notable changes to kromus are recorded here. The project is pre-1.0: the public API and the
persistence format may still change between minor versions, and every such change is listed below
with what it takes to migrate.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) once 1.0 lands.

## [0.15.0] — 2026-08-24

### Added

- **`VectorIndex.compact()` / `HybridIndex.compact()`** — rebuilds the graph over the live entries
  only, reclaiming the slots left behind by `remove` and by re-adding an existing key. Until now
  those tombstones were permanent: an index kept in step with changing data grew without bound and
  its queries slowed down with it. `tombstones` reports how much a rebuild would reclaim. Quantized
  codes survive a rebuild bit-exact; a full-precision index comes out byte-identical to one built
  from the same entries.
- **`updateAttributes(key, attributes)`** on all three indexes — changes an entry's metadata without
  re-indexing it. The previous route, re-adding the entry, cost a whole new graph node and left a
  tombstone behind.
- **`HnswConfig.maxVisited`**, with a per-query `maxVisited` override — a ceiling on how many nodes
  one query may touch. A metadata filter that matches almost nothing keeps the search from ever
  filling its candidate list, so it walks the entire graph; a budget bounds that at the price of
  possibly returning fewer than `k` hits.
- **`VectorIndex.vectorOf(key)`** — reads a stored vector back, so `rerank` can be fed from the index
  itself instead of a second copy of the corpus.
- **`keys`** and **`clear()`** on `VectorIndex`, `TextIndex` and `HybridIndex`.
- **`KromusFormatException`**, thrown by every `decode*` function when the bytes are not an index this
  build can read. Blobs now carry a magic header, a kind byte and a format version, every read is
  bounds-checked, and every length is validated against the bytes that remain — so a stale cache or a
  truncated file reports what is wrong instead of crashing with an index-out-of-bounds.
- **kromus-sync: `SyncState`** — the versions a sync has already indexed, seedable from a previous
  run. Without it, reloading a persisted index and attaching a sync re-embedded the entire corpus on
  the first snapshot, which is exactly the work persisting the index was meant to avoid.
- **kromus-sync: `onError` / `SyncFailurePolicy`** — one failed embedding no longer tears down the
  whole sync. A skipped entity stays untracked and is retried on the next snapshot.
- **kromus-sync: `ConcurrentVectorIndex` / `ConcurrentTextIndex` / `ConcurrentHybridIndex`**, created
  with `.concurrent()` — a `Mutex` around an index shared between a background writer and a
  foreground reader. The `syncTo` overloads for them run the embedding outside the lock.
- **A benchmark suite** (`./gradlew :benchmarks:run`) covering recall against exact search, query
  latency, what each quantization mode costs and buys, the effect of a traversal budget on selective
  filters, and what churn does to an index. Results are in the readme.

### Changed

- **Binary quantization is 4–5× faster.** Query distances are now table-driven (`q · s` decomposed
  into per-nibble partial sums of the query) instead of walking the sign bits one dimension at a
  time, which had left binary quantization *slower* than the full precision it exists to trade away.
  Build time drops with it.
- **HNSW traversal allocates almost nothing.** The visited set is a mark array with a monotonic epoch
  rather than a `HashSet<Int>`, adjacency lists are primitive arrays rather than boxed
  `MutableList<Int>`, the candidate heaps are reused across searches, and results no longer recompute
  the distances the search already knew.
- **Ranking is deterministic across platforms.** BM25 accumulated its scores in hash-iteration order
  and broke ties the same way, so equal-scoring documents could rank differently on different
  targets; scoring now follows query order and ties resolve by insertion order. `TextIndex` also
  selects its top `k` with a bounded heap instead of sorting every scored document.
- **Serialized indexes are byte-stable.** Records are written in a fixed order (vector entries by
  internal id, documents by insertion order), so encoding the same content twice — or on two
  different platforms — produces identical bytes, which makes an index safe to content-hash.
- **Serialized text indexes are substantially smaller.** Terms and attribute strings are pooled and
  stored once instead of being repeated in every record that references them.

### Fixed

- `HnswConfig.maxVisited` aside, a query no longer recomputes the distance of every result it is
  about to return.

### Migration

- **The persistence format changed** (vector v4, text v3, hybrid v2) and older blobs cannot be read.
  They are rejected with a `KromusFormatException` that says so, so catch it and rebuild:

  ```kotlin
  val index = try {
      decodeHybridIndex(cached, KeyCodec.string)
  } catch (e: KromusFormatException) {
      buildIndexFromScratch()
  }
  ```

- **`VectorIndex.search` and `HybridIndex.search`/`searchVector` take `maxVisited` before `filter`.**
  Calls that passed `filter` positionally after `efSearch` no longer compile; name the argument
  (`filter = …`) or pass it as a trailing lambda.
- **`reconcile` and `syncTo` take `state` and `onError` before the trailing lambda.** Calls using
  named arguments — the documented form — are unaffected.

## [0.14.0]

Published to Maven Central: `kromus-core`, `kromus-kemus`, `kromus-onnx` and `kromus-sync`.

Vector (HNSW), full-text (BM25) and hybrid (RRF) layers; binary persistence; int8 and binary
quantization; metadata filters; pluggable analyzers; full-precision re-rank; the optional kemus
storage adapter, ONNX embedder and Flow-based sync modules.
