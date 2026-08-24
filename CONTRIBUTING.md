# Contributing to kromus

Thanks for looking. kromus is a small library with a deliberately small surface, so the most useful
contributions are usually narrow: a bug with a failing test, a measured performance win, a platform
that misbehaves.

## Getting set up

You need a JDK 21. Everything else the build fetches itself.

```bash
./gradlew build          # compiles every target the host supports and runs its tests
./gradlew :kromus-core:jvmTest   # the fast inner loop while working on the engine
./gradlew ktlintFormat   # formatting; ktlintCheck runs as part of `build`
```

On Linux and Windows the Apple targets simply do not build and their tasks are skipped. macOS builds
everything, which is why CI runs both.

Android targets need an SDK: set `ANDROID_HOME`, or put `sdk.dir=…` in a `local.properties` (which is
git-ignored).

## What the library promises

Three properties are load-bearing. A change that breaks one needs a very good reason:

- **Zero dependencies in the core.** `kromus-core` is arithmetic over `FloatArray` and graph
  structures in common code — no coroutines, no serialization library, no native interop. Anything
  that needs a dependency belongs in a companion module, the way `kromus-sync` holds the coroutines
  and `kromus-onnx` holds the model runtime.
- **One implementation, identical behaviour everywhere.** No `expect`/`actual` in the engine, no
  per-platform fast paths that change results. An index built on any platform must rank identically
  on every other, and encode to the same bytes.
- **Determinism.** Level assignment is seeded, floating-point accumulation happens in a fixed order,
  and anything that iterates a hash map must not be able to affect an outcome. If you find yourself
  ranking, serializing or rebuilding from a `HashMap` iteration order, that is a bug waiting to
  happen — sort by id, insertion order, or something else stable.

## Tests

New behaviour needs a test in `commonTest`, so it runs on every target rather than just the JVM.

The engine is exercised against properties rather than golden outputs where possible: recall against
brute force, a compacted index against a freshly built one, a persisted index against the original.
That style survives refactoring; asserting exact hit lists does not.

If you change anything on the hot path or in the graph, run the benchmarks before and after:

```bash
./gradlew :benchmarks:run --args="--quick"                     # seconds, for a smoke check
./gradlew :benchmarks:run --args="--vectors 50000 --dim 128"   # minutes, for real numbers
```

Recall is only meaningful next to the corpus it was measured on — the suite reports the dataset's
contrast alongside it for that reason. A recall figure from a tightly clustered corpus proves very
little.

## Public API

`kromus-core` is ABI-locked. If you add or change public API, run:

```bash
./gradlew apiDump
```

and include the `.api` diff in the pull request — it is the part reviewers read most carefully. The
klib dump is validated on macOS, where every declared target builds.

Anything public needs KDoc that says what it does *and when to reach for it*. The existing docs are
the standard to match: they explain the trade-off, not just the signature.

## Persistence format

Changing the binary format means bumping the version constant in `Persistence.kt`. Old blobs are then
rejected with a `KromusFormatException` that tells the caller to rebuild — that is the intended
migration path pre-1.0, but it costs every user a rebuild, so batch format changes rather than
shipping them one at a time.

## Commits and pull requests

Conventional-commit prefixes (`fix:`, `feat:`, `perf:`, `docs:`, `build:`, `ci:`) — the history uses
them. Keep formatting changes out of behaviour changes; a diff that is half reformatting is a diff
nobody can review.

Note anything user-visible in `CHANGELOG.md` under `## [Unreleased]`.

## License

By contributing you agree that your contribution is licensed under the Apache License 2.0, the same
as the rest of the project.
