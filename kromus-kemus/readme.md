# kromus-kemus

**Build the index once, reload it instantly — from a store that already handles persistence.**

Building an HNSW graph is the expensive part of using kromus: it is the work you do so that queries
can be cheap. Doing it again on every cold start throws that away. kromus-core can already serialize
an index to a compact `ByteArray`; what it does not have — deliberately, it is zero-dependency — is
somewhere to put those bytes.

`kromus-kemus` is the six-function adapter that puts them in a [kemus](https://github.com/kormium/kemus)
store. That is the whole module. What makes it worth a dependency is not the adapter but what the
index inherits once it lives there: kemus's persistence, its TTL, and its offline→online sync.

## How it works

kromus encodes to raw bytes; kemus stores raw bytes (`setBytes` / `getBytes` — base64 appears only at
kemus's own text boundaries, never in memory). So there is no translation layer and nothing to go
wrong between them — the blob kemus holds is byte-for-byte the blob `encodeToByteArray` produced.

```kotlin
index.saveTo(store, "docs", KeyCodec.string)
val reloaded = loadHybridIndex(store, "docs", KeyCodec.string)   // null if the key is absent
```

`saveTo` is an extension on all three index types. The loaders are `loadVectorIndex`,
`loadTextIndex` and `loadHybridIndex`, and each returns `null` rather than throwing when the key
isn't there, so first run and later runs are the same code path:

```kotlin
val index = loadHybridIndex(store, "docs", KeyCodec.string)
    ?: HybridIndex<String>(dimensions = 384).also { buildFromScratch(it) }
```

**Analyzers are functions, so they are not serialized.** `loadTextIndex` and `loadHybridIndex` take
the analyzer the index was built with — pass the same one, or the loaded index will tokenize queries
differently from the documents already in it. It defaults to `Analyzer.standard()`, which is right
only if that is what you built with.

## Pair it with kromus-sync

Reloading an index and then attaching a sync re-embeds the entire corpus on the first snapshot, which
is exactly the cost persisting it was meant to avoid. `SyncState` is the other half: store it beside
the index, hand it back on load.

```kotlin
val index = loadHybridIndex(store, "docs", KeyCodec.string) ?: HybridIndex(dimensions = 384)
val state = SyncState(loadVersions())

scope.launch {
    docs.observe(db).syncTo(index, keyOf = { it.id }, versionOf = { it.updatedAt }, state = state) { … }
}
```

## The format will move under you

A persisted index outlives the build that wrote it, and pre-1.0 the binary format changes between
versions — 0.16.0 alone moved vector to v5, text to v4 and hybrid to v3. A store is where this bites,
because the stale blob is sitting in it waiting for the next release to read.

Decoding is defensive about it: blobs carry a magic header, a kind byte and a format version, and
anything unreadable raises `KromusFormatException` instead of failing somewhere deep. Treat a rebuild
as the migration path.

```kotlin
val index = try {
    loadHybridIndex(store, "docs", KeyCodec.string)
} catch (e: KromusFormatException) {
    null                                    // stale format — fall through and rebuild
} ?: HybridIndex<String>(dimensions = 384).also { buildFromScratch(it); it.saveTo(store, "docs", KeyCodec.string) }
```

## What it costs

`saveTo` writes the whole index: every entry, every time, O(index size) whether one document changed
or all of them. On a 50 000-vector index that is tens of megabytes, which on device is a write-
amplification problem and not merely a slow one.

Since 0.16.0 you do not have to pay it after every edit. `encodeDelta` writes only what changed, and
the loaders take a base plus the deltas recorded after it:

```kotlin
// once, on a fresh index or when folding the log back up
store.setBytes("docs", index.encodeToByteArray(KeyCodec.string))

// after a batch of edits
index.encodeDelta(KeyCodec.string)?.let { store.setBytes("docs.d$n", it) }

// on load
val index = decodeHybridIndex(store.getBytes("docs")!!, deltas, KeyCodec.string)
```

Deltas accumulate, so fold periodically — decode the chain and `encodeToByteArray` the result. Use
`dirtyNodes` to decide when a save is worth making at all, and `needsFullSnapshot` to know when a
delta is not an option: `compact()` and `clear()` renumber ids, and deltas are written in terms of
them.

Encoding is also **deterministic** — the same content produces the same bytes on every platform, and
that holds across a reload — so you can content-hash a blob and skip a write when nothing changed.

Worth knowing: `remove` and re-adding a key leave tombstones, and tombstones are serialized like
everything else. `compact()` before a save reclaims them; `tombstones` tells you whether it is worth
the rebuild — at the price of forcing the next save to be a full snapshot.

## Status

Pre-1.0, part of the kromus suite. Runs on every target kromus-core does — JVM, Android, iOS, Native,
JS and Wasm. The only module in the suite with an external dependency: `io.github.kormium:kemus-core`,
exposed as `api`, so a consumer gets the kemus types without declaring it twice.

```kotlin
implementation("io.github.kormium:kromus-kemus:0.16.0")
```

## License

Apache License 2.0.
