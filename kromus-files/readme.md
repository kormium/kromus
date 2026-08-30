# kromus-files

File-backed [`ByteSource`](../kromus-core/src/commonMain/kotlin/ByteSource.kt) implementations, so a
kromus index can be searched **from a file** instead of being inflated into memory.

```kotlin
implementation("io.github.kormium:kromus-files:0.16.0")
```

## Why this is a separate module

`kromus-core` is common code and nothing else — one implementation of the engine that runs identically
on every target, which is the property the whole library is built around. Reading a file is the
opposite: `java.nio` on the JVM, `fopen`/`fread` on Native, `fs.readSync` under Node, OPFS in a
browser. Four interop surfaces do not belong in an artifact whose whole claim is that it has none.

So the seam lives in core — `ByteSource`, three methods — and the platform half lives here. A source
of your own needs only core.

## What it ships

| type | targets | how it reads |
| --- | --- | --- |
| `FileByteSource` | JVM, Android | positional `FileChannel.read`, which never moves a shared cursor — one open file serves every thread that searches |
| `FileByteSource` | iOS, macOS, Linux, Windows | buffered `fopen`/`fseek`/`fread`; one source per thread, since a seek and a read are two calls |
| `NodeFileByteSource` | Node (Kotlin/JS and Kotlin/Wasm) | `fs.readSync` |
| `OpfsByteSource` | browser (Kotlin/JS and Kotlin/Wasm) | a synchronous OPFS access handle — **worker only** |

Each has `open` for a whole file and `openRange` for an index packed inside a larger one.

## Using it

```kotlin
val source = FileByteSource.open("$filesDir/catalogue.krm")
val index = openIvfIndex(source, KeyCodec.string)

val hits = index.search(queryVector, 10)

source.close()   // the index stops working here, not before
```

Opening reads the header and the small sections — centroids, the cluster table, keys, attributes — and
leaves the vector region on disk. A query then reads only the clusters it probes, each one contiguous.
That is what an `IvfIndex` is *for*: a graph traversal would touch pages scattered across the whole
vector region, and there is nothing to bound a read by.

## Android

An asset packed in an APK is **compressed by default and cannot be read at an offset at all**. Two ways
out, and the choice is about disk rather than correctness:

```kotlin
// 1. Mark the index noCompress in build.gradle, then read it in place — no copy, no second copy of
//    the file on the device.
val fd = assets.openFd("catalogue.krm")
val source = FileByteSource.openRange(applicationInfo.sourceDir, fd.startOffset, fd.length.toInt())

// 2. Or copy it into filesDir once on first run, and open it by path afterwards.
val file = File(filesDir, "catalogue.krm")
if (!file.exists()) assets.open("catalogue.krm").use { input -> file.outputStream().use(input::copyTo) }
val source = FileByteSource.open(file)
```

## Browser

The only synchronous file read in a browser is OPFS's `createSyncAccessHandle`, and it exists **only
inside a Web Worker** — a constraint of the platform, not a choice made here. A file-backed search
therefore runs in a worker and posts its results back, which is where a scan over thousands of vectors
belongs anyway.

`OpfsByteSource` takes an already-open handle rather than opening one, because opening is asynchronous
and `ByteSource` is not. Everything async stays with you:

```kotlin
// inside a worker
val root = navigator.storage.getDirectory().await()
val file = root.getFileHandle("catalogue.krm").await()
val handle = file.createSyncAccessHandle().await()

val index = openIvfIndex(OpfsByteSource.open(handle), KeyCodec.string)
```

A sync access handle holds an exclusive lock on the file while it is open, so close it when the index
is done with.

**Honest about coverage:** the OPFS sources are tested against a stand-in handle that reproduces the
browser's contract, including reads that return fewer bytes than asked for. The binding to the
browser's own object is not exercised by CI, because a test runner drives the main thread and a real
handle cannot be created there.

## Writing your own

`ByteSource` is in `kromus-core`, so a source of your own — a decrypting reader, a virtual file
system, a cache that fetches and spills to local storage — does not need this module at all.

One rule is not optional: **`read` must fill exactly what was asked for.** Returning early is the one
failure the interface cannot detect — a partly-filled buffer leaves whatever was there before in the
tail, and the scan reads it as vector data. Nothing throws, and the query comes back with neighbours
that merely look right. Loop until the range is complete, and throw `KromusFormatException` if it
cannot be.

Every source here is held to one shared contract (`SourceContract` in the tests): it must read the
same bytes an array does at every offset, honour the destination offset, refuse ranges outside itself,
refuse reads after closing, and survive a handle that reads short.
