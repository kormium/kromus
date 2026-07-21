# kromus-sync

**Keep a kromus index fresh from your data — automatically.**

The last mile of "search over my app's data" is keeping the index in step with the source of truth.
`kromus-sync` does that from a **snapshot flow**: a `Flow<List<T>>` that re-emits the current result
set whenever the data changes. That's exactly what [`kormium-observe`](https://github.com/kormium/kormium)
produces (`Table.observe(db) { … }`), what Room-style `Flow<List<T>>` produces, or any flow you build.

So the module needs **no data-layer dependency** — just kromus-core and coroutines. It runs on every
KMP target.

## How it works

On each snapshot it reconciles against what it has already indexed:

- entries that are **new or changed** (by a `version` you pick) are re-embedded and upserted,
- entries that **dropped out** are removed.

Only changed entries pay the embedding cost — an unchanged row is skipped.

## With kormium

This is the piece that lets you write *just the UI + your query*: kormium is the source of truth,
kromus is the search, kromus-sync keeps them aligned.

```kotlin
scope.launch {
    Users.observe(db) { where { Users.active eq true } }        // Flow<List<User>> from kormium-observe
        .syncTo(
            index,                                              // a kromus HybridIndex
            keyOf = { it.id },
            versionOf = { it.updatedAt },                       // re-embed only when this changes
        ) { user ->
            HybridDoc(
                vector = embedder.embed(user.bio),              // kromus-onnx (or any embedder)
                text = "${user.name} ${user.bio}",
                attributes = mapOf("role" to user.role),
            )
        }
}
```

A user row inserted, edited or deleted through the database now shows up in — or disappears from —
search, with nothing else to wire.

`VectorIndex` and `TextIndex` have their own `syncTo` overloads (vector-only / text-only).

## Anything that emits snapshots

`syncTo` / `reconcile` take any `Flow<List<T>>`, so the same code works with a custom flow, a
`StateFlow` of your in-memory list, or a periodic re-query — not just kormium.

```kotlin
// the low-level engine, decoupled from kromus
flow.reconcile(
    keyOf = { it.id },
    versionOf = { it.rev },
    upsert = { index.add(it.id, embed(it)) },
    remove = { index.remove(it) },
)
```

## Status

Pre-1.0, part of the kromus suite. Verified on JVM, Android, iOS, Native and the web. Depends only on
kromus-core + kotlinx-coroutines.

## License

Apache License 2.0.
