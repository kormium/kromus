# Changelog

All notable changes to kromus are recorded here. The project is pre-1.0: the public API and the
persistence format may still change between minor versions, and every such change is listed below
with what it takes to migrate.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) once 1.0 lands.

## [0.16.0] — 2026-08-29

### Added

- **`encodeDelta`** on all three indexes, with `decodeVectorIndex` / `decodeTextIndex` /
  `decodeHybridIndex` overloads that take a base snapshot plus the deltas recorded after it. A full
  encode rewrites everything however little moved — tens of megabytes after every batch on a 50 000-
  vector index, which on device is a write-amplification problem and not just a slow one.

  An insert into an HNSW graph is symmetric, so it relinks tens of *existing* nodes scattered across
  the id space; a delta could not simply append the new ones. What makes it small instead is that a
  stored vector is immutable — ids are never reused, so re-adding a key allocates a new node and
  tombstones the old one — and a changed existing node therefore owes only its deleted flag and its
  adjacency, never the vector that is the bulk of it.

  A delta names the revision it applies to, so replaying one out of order, skipping one in a chain, or
  applying one built from a different index is rejected with a `KromusFormatException` rather than
  silently corrupting the result.

  Deltas accumulate: fold the chain back into a snapshot periodically by decoding it and re-encoding.

- **`VectorIndex.searcher()` / `HybridIndex.searcher()`**, returning a reader that owns the working
  state one traversal needs, so several searchers can query one index at the same time.

  Searches were never safe to run concurrently, and not by oversight: 0.15.0 moved a traversal's
  visited marks, candidate heaps and layer buffers onto the index and reused them between calls, which
  is what made a query allocate almost nothing — and equally what made two concurrent queries corrupt
  each other. Unguarded on eight threads, most searches threw and the rest quietly returned the wrong
  neighbours. That state now belongs to a searcher, and the index is merely read during a search.

  The contract that remains is the index's: nothing may write to it while a search runs. Searchers
  make *reads* parallel, not reads-and-writes concurrent.

- **kromus-sync: parallel searches through the `.concurrent()` wrappers.** They now use a
  writer-preferring readers-writer lock and a pool of searchers instead of a single `Mutex`, so
  searches run alongside each other while a writer still gets exclusive access — and is not starved by
  a steady stream of readers. `read { }` joins other readers; `use { }` remains exclusive, because a
  caller-supplied block may write.

- **Index provenance.** `encodeToByteArray(codec, provenance = "…")` records what produced an index,
  and the decoders take `expect = "…"` and refuse a blob that does not match; `provenanceOf(bytes)`
  reads it without decoding.

  An index is only meaningful together with the embedding model that produced its vectors and the
  analyzer that tokenized its text, and neither is part of the bytes. Search a corpus embedded by one
  model with vectors from another and nothing fails — the results are simply wrong, quietly. That
  failure gets much easier to reach once an index is built somewhere other than where it is used, so
  the guard is opt-in but the mismatch is refused outright.

- **`dirtyNodes`** on `VectorIndex` and `HybridIndex`, **`dirtyDocuments`** on `TextIndex` — how much
  has changed since the last save, for deciding when one is worth making.
- **`needsFullSnapshot`** on all three — true when no delta can express what happened, which is after
  `compact()` or `clear()` (both renumber or drop the internal ids deltas are written in terms of) and
  before anything has been encoded at all.

### Fixed

- **A corrupt node level no longer exhausts the heap.** A level is a count in disguise — `level + 1`
  adjacency lists follow it — but it was validated only for being non-negative, so a corrupt two
  billion reached `Array(level + 1)` and took the process down before any other check ran. That is the
  exact failure every other length in the format is guarded against, reached by the one field that did
  not go through the guard. Found by the existing fuzz test once an unrelated layout change moved
  which byte it happened to corrupt.
- **Searches on several threads no longer corrupt the index** — see `searcher()` above. This was
  reachable before only by ignoring the documented single-threaded contract, but it failed silently
  rather than loudly, which is the worse way to be wrong.
- **Byte-stability now survives a reload.** Encoding a reloaded index did not reproduce the bytes of
  the index it came from, so an index could not be compared to a cached copy by digest — which is what
  the guarantee exists for. Attribute and term-frequency maps were written in map-iteration order, and
  `add` builds those maps differently from the decoder (and a caller can pass any `Map` at all), so
  identical content encoded differently. Both are now written sorted by key, making the layout a
  function of the content alone.

### Changed

- `encodeToByteArray` now also **checkpoints** the index: `dirtyNodes` drops to zero and later
  `encodeDelta` calls chain onto those bytes. Keep what it returns — a delta written against a
  snapshot you discarded has nothing to be applied to.

### Migration

- **The persistence format changed** (vector v6, text v5, hybrid v4) and 0.15.0 blobs cannot be read.
  They are rejected with a `KromusFormatException`, so catch it and rebuild, exactly as for the 0.15.0
  move:

  ```kotlin
  val index = try {
      decodeHybridIndex(cached, KeyCodec.string)
  } catch (e: KromusFormatException) {
      buildIndexFromScratch()
  }
  ```

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
