# Changelog

All notable changes to kromus are recorded here. The project is pre-1.0: the public API and the
persistence format may still change between minor versions, and every such change is listed below
with what it takes to migrate.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) once 1.0 lands.

## [0.16.0] — 2026-08-30

### Added

- **An index can now be searched from a file.** `openIvfIndex` and `openFlatIndex` take a `ByteSource`
  and read through it: only the header and the small sections are loaded — centroids, the cluster
  table, keys, attributes — and the vector region, which is the bulk, stays where it is until a query
  asks for a cluster. That cluster then arrives as one contiguous run, which is the arrangement the
  clustered index exists for.

  This is the piece that turns the whole design from an argument into a capability. Until now the only
  source read from the same array the index was loaded from, which proved the path and reclaimed
  nothing; the ceiling was still memory. It is storage now.

  The cost is measured on the access pattern rather than asserted: opening a 600-entry index reads
  less than its vector section occupies, and ten queries at `nprobe = 3` together read less than the
  file. The assertions are against the size of the vector region, not against numbers chosen to pass.

- **`ByteSource`** — where an index's bytes come from, and the fourth public seam. Three methods:
  `size`, `read(offset, length, into, at)` and `close()`. `ByteArraySource` reads from memory;
  anything else — a decrypting reader, an Android `AssetFileDescriptor`, a virtual file system, a
  cache that fetches and spills — is this interface and nothing more.

  It replaces `VectorBlocks`, which was narrower (a vector region, not a container) and never shipped.
  `ContainerReader` now reads from a source as well as from an array, through one header parser rather
  than two, and `KromusFormatException`'s constructor is public so a source of your own can report the
  same failures the library's own do.

  The rule that is not optional: **`read` must fill exactly what was asked for.** Returning early is
  the one failure this interface cannot detect — a partly-filled buffer leaves whatever was there
  before in the tail, and the scan reads it as vector data. Nothing throws, and the query comes back
  with neighbours that are merely plausible.

- **[`kromus-files`](kromus-files/)** — a new optional module with a file-backed source for every
  platform that has synchronous file access: `FileByteSource` on JVM and Android (positional
  `FileChannel` reads, so one open file serves every searching thread) and on iOS, macOS, Linux and
  Windows (`fopen`/`fseek`/`fread`); `NodeFileByteSource` under Node for both Kotlin/JS and
  Kotlin/Wasm; `OpfsByteSource` in the browser for both. `openRange` opens an index packed inside a
  larger file, which is the shape an Android asset arrives in.

  It is a separate artifact because `kromus-core` is common code and nothing else — four interop
  surfaces do not belong in a module whose whole claim is that it has none. The seam stays in core, so
  a source of your own needs only core.

  In the browser the only synchronous read is OPFS's `createSyncAccessHandle`, which exists **only
  inside a Web Worker** — a constraint of the platform. `OpfsByteSource` takes an already-open handle
  rather than opening one, so everything asynchronous stays with the caller.

  Every source is held to one shared contract: read the same bytes an array does at every offset,
  honour the destination offset, refuse ranges outside itself, refuse reads after closing, and loop
  rather than trust a handle that reads short. The OPFS pair is tested against a stand-in handle that
  reproduces the browser's contract, short reads included; the binding to the browser's own object is
  not exercised by CI, because a test runner drives the main thread and a real handle cannot be
  created there.

- **`IvfIndex`** — a second index type that groups the corpus instead of linking it into a
  graph, searching only the groups nearest a query.

  It exists for one property a graph cannot have: **contiguity**. A graph traversal goes wherever the
  data leads, so on a file it touches pages scattered across the whole vector region; a clustered
  search reads a handful of runs. At 20 000 vectors a graph query touches 140 pages and a clustered
  one touches 10, and that ratio — not recall — is what decides whether an index larger than memory is
  usable at all.

  **It needs no tuning to start.** How many groups a query must open is a property of the data, so
  `build` measures the corpus and picks: one group where the corpus partitions cleanly, dozens where
  it barely does. A blind default cannot serve both — the same number is right for one and quietly
  returns half the neighbours for the other. `nprobe` reports the choice and `estimatedRecall` what it
  was measured to be worth.

  That estimate is optimistic and says so. The only queries available at build time are corpus points,
  which sit inside clusters rather than between them where a boundary can fall between a query and its
  neighbours; the trivial self-match is excluded, which removes most of the bias but not all. On a
  corpus with little structure a target of 0.95 lands near 0.89.

  Compared at *equal recall* — both tuned to the same target, which is the only comparison that means
  anything — a clusterable corpus is answered as well for a fifth of the pages read. A corpus without
  group structure is not: slightly fewer pages, and less recall for them.

  Built, not grown: there is no `add`. Clustering needs the corpus in hand and adding without redoing
  it drifts, which suits what this is for — an index assembled on a server or in CI and shipped
  read-only.

  Clustering is reproducible by construction: seeded initialisation, a fixed iteration count rather
  than convergence (which depends on floating-point details that differ between targets), ties to the
  lower index, and a determined re-seed for an emptied group. Without that the layout would vary and
  with it the bytes.

- **`FlatIndex`** — exhaustive search, exact by construction. Faster than any index below a few
  thousand vectors, and the thing recall is measured *against*, which is worth having on your own data
  rather than only in a benchmark. Nothing to tune, so it also answers "do I need an index yet".

- **`IvfIndex` is what `ClusteredIndex` was called.** It is the standard inverted-file structure in its
  standard parts — k-means centroids as the coarse quantizer, one list per centroid, `nprobe` lists
  opened per query — and naming it after the thing it is means someone who knows IVF recognises what
  they have and what tuning applies. `ClusterConfig`/`ClusterEntry` follow to `IvfConfig`/`IvfEntry`.

- **Redundant assignment and graph routing**, the two pieces that make an IVF index a
  [SPANN](https://arxiv.org/abs/2111.08566) one. `IvfConfig.assignments` places a vector in its nearest
  few lists, so a neighbour near a boundary is no longer lost to whichever side a query arrives from —
  it costs storage, deliberately, and the duplicate is what keeps each list contiguous.
  `IvfConfig.routing` navigates a graph over the centroids instead of scanning them, which is what
  scaling past a few thousand lists requires; `Routing.Auto` switches on the count, because below it
  the exact scan is both cheaper and exact. `IvfPresets.spann` sets all three together.

- **Four public seams, so an index or a quantizer kromus does not ship can be added anyway.**
  `VectorSearch` is what an index is from a caller's side; `VectorStore` is the quantizer seam, now
  carrying its own byte layout so persistence no longer switches on the built-in types;
  `ContainerWriter`/`ContainerReader` hand a third-party index the whole file format, checksums and
  provenance included; `ByteSource` is where an index's bytes come from.

  A custom store is accepted by all three index types and by each `decode…` function; `VectorIndex`
  keeps the factory, since `compact()` and `clear()` rebuild the graph and would otherwise rebuild it
  on different storage. Section lengths are now checked against the store's own `strideBytes` rather
  than a table of the built-in layouts, so a file read with a store of the wrong width is refused
  before a record is read. Streaming (`openIvfIndex`/`openFlatIndex`) does not take one: that scan
  reads stored bytes directly and knows only the built-in layouts.

  A test builds an int4 quantizer entirely from public API — under flat, graph and inverted file, it
  round-trips byte-identically, survives a compaction, and lands between binary and full precision on
  recall — which is the evidence that the seam carries something rather than merely existing.

  `Metric` stays closed on purpose: exhaustive `when` over it is what lets each quantizer specialize
  its arithmetic, the binary store's lookup table among them.

- **A clustered index reads its vectors a cluster at a time** instead of holding them. Each probed
  cluster is a contiguous run, so it arrives as one read and the distance loop runs over a plain array
  with no indirection per vector.

  A streamed index returns identical results to a resident one — the byte-scanning distances are a
  second implementation of the same arithmetic, and a second implementation that disagreed anywhere
  would return quietly wrong neighbours rather than fail, so the tests hold them to matching score for
  score across quantizations and metrics. Binary quantization is refused: its query path is built per
  query, and its codes are small enough that there is nothing to reclaim.

- **`IvfSearcher`** — holds the buffer a streamed cluster is read into, so repeated queries do not
  each allocate one a whole cluster wide. One per thread; different searchers run in parallel, and
  nothing writes to the index.

- **`IvfIndex.probedClusters`** and **`clusterSize`** — what a query will actually read. Each
  cluster is a contiguous run, so the probe list *is* the read plan; useful for sizing a cache and for
  measuring a file-backed index without assuming clusters are evenly sized, which k-means never
  promises.

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

- **Every index is now a container of named sections** rather than one interleaved stream. Sections
  are homogeneous, so fields are sized for what they hold rather than for the widest thing beside
  them; each carries a checksum, so corruption is located rather than merely noticed; and each is
  contiguous, so a reader can take the graph without the vectors.

  A node level is two bytes rather than four, a deleted flag one bit rather than eight, a neighbour
  count two bytes, and a neighbour link two bytes wherever the index holds 65 536 nodes or fewer.
  Every integer is read unsigned — with a signed short that last threshold would be 32 768, and links
  are the whole of the adjacency section. Measured at 50 000 vectors × 128 dimensions: **30.7 → 27.8
  MiB** full precision, **12.5 → 9.7 MiB** int8, **7.0 → 4.2 MiB** binary.

  Tags are four ASCII characters, so a hex dump of an index is readable by eye.

- `encodeToByteArray` now also **checkpoints** the index: `dirtyNodes` drops to zero and later
  `encodeDelta` calls chain onto those bytes. Keep what it returns — a delta written against a
  snapshot you discarded has nothing to be applied to.

### Migration

- **The persistence format changed** (vector v7, text v6, hybrid v5) and 0.15.0 blobs cannot be read.
  The layout moved twice during development — first to the sectioned container, then to the denser
  field widths inside it — but only the end of that appears here, because nothing in between was ever
  published. They are rejected with a `KromusFormatException`, so catch it and rebuild:

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
