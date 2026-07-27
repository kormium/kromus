# kromus-concurrent

**Use a kromus index from many coroutines at once — searches in parallel, writes alone.**

kromus's indexes are single-threaded by design: that is what keeps `kromus-core` zero-dependency and
byte-for-byte identical on every target. But the shape almost every app ends up with is *index in the
background, search from the UI*, and a bare `VectorIndex` shared across threads corrupts — parallel
`add`s trip its own consistency check (`index desynchronized: id=3, keyOf.size=0`) or leave a torn
HNSW graph.

`kromus-concurrent` is the missing half: thin `suspend` wrappers that guard the core indexes with a
readers-writer lock.

```kotlin
val index = ConcurrentHybridIndex<String>(dimensions = 384)

scope.launch {                                             // background indexing
    index.add("doc-1", embedder.embed(text), text)         // exclusive
}
val hits = index.search(embedder.embed(query), query, k = 10)  // concurrent with other searches
```

## Why not just a Mutex

A single `Mutex` would also make the index safe — by serializing *searches* against each other. In
kromus, search is the hot path and is strictly read-only (`Hnsw.query` allocates all of its scratch
per call), so searches are exactly the work you want running on every core. [`ReadWriteMutex`](src/commonMain/kotlin/ReadWriteMutex.kt)
lets them:

| | concurrent searches | exclusive writes | blocks a thread |
| --- | :---: | :---: | :---: |
| bare index | ✓ (unsafe) | ✗ | — |
| `Mutex` | ✗ | ✓ | no |
| `ReadWriteMutex` | ✓ | ✓ | no |

It is **writer-preferring**: a queued writer stops new readers from overtaking it, so a steady stream
of searches can never starve the coroutine that keeps the index fresh.

## What you get

`ConcurrentVectorIndex`, `ConcurrentTextIndex` and `ConcurrentHybridIndex` mirror their core
counterparts, with every operation a `suspend` function. Either wrap an index you already have (a
decoded snapshot, say) or construct one directly:

```kotlin
val fromDisk = ConcurrentVectorIndex(decodeVectorIndex(bytes, KeyCodec.string))
val fresh = ConcurrentVectorIndex<String>(dimensions = 384, metric = Metric.Cosine)
```

**Batch writes** take the lock once, and searchers never see a half-applied batch:

```kotlin
index.addAll(documents.map { HybridEntry(it.id, embedder.embed(it.body), it.body) })
```

**`read` / `write`** are the escape hatches for anything the wrappers don't cover — persistence, for
instance, or a multi-step change that must land as one:

```kotlin
val bytes = index.read { it.encodeToByteArray(KeyCodec.string) }   // shared lock
index.write { raw ->                                               // exclusive lock
    raw.remove(oldKey)
    raw.add(newKey, vector, text)
}
```

Don't let the index escape its block, and don't touch the wrapped instance directly afterwards.

## Two rules

- **Blocks don't suspend.** `read { }` and `write { }` take a plain lambda so the guarded section is
  one indivisible step. Do the expensive, suspending part — embedding, I/O — *outside* the lock and
  pass the result in.
- **The lock is not reentrant.** Calling a guarded operation from inside another's block deadlocks.
  Use `write { }` once, with the whole change inside it, instead of nesting.

## With kromus-sync

No extra glue: `reconcile` in [`kromus-sync`](../kromus-sync/) takes `suspend` callbacks, so a
guarded index drops straight in.

```kotlin
flow.reconcile(
    keyOf = { it.id },
    versionOf = { it.updatedAt },
    upsert = { index.add(it.id, embedder.embed(it.body), it.body) },  // embeds outside the lock
    remove = { index.remove(it) },
)
```

## Status

Pre-1.0, part of the kromus suite. Depends only on kromus-core + kotlinx-coroutines, and runs on
every kromus target. Parallelism guarantees — concurrent readers, writer exclusion, writer
preference, cancellation safety — are tested on the JVM against real threads, where they can actually
be observed; the web targets are single-threaded, so there the lock simply never contends.

`implementation("io.github.kormium:kromus-concurrent:0.15.0")`

## License

Apache License 2.0.
