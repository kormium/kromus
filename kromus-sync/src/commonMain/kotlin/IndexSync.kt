package io.github.kromus.sync

import io.github.kromus.HybridIndex
import io.github.kromus.TextIndex
import io.github.kromus.VectorIndex
import kotlinx.coroutines.flow.Flow

// Keeps a kromus index in step with a stream of *snapshots* — a `Flow<List<T>>` that re-emits the
// current result set whenever the underlying data changes. That is exactly the shape produced by
// `kormium-observe` (`Table.observe(db) { … }`), Room-style `Flow<List<T>>`, or any custom flow, so
// kromus-sync closes the "keep the index fresh" gap without depending on any particular data layer.
//
// On each snapshot it reconciles against what it has already indexed:
// - entries new or whose version changed are re-embedded and upserted,
// - entries no longer present are removed.
//
// Only changed entries hit the (possibly expensive) embedding step. Launch it in a scope; it collects
// until the flow completes or the scope is cancelled.
//
// ```kotlin
// scope.launch {
//     Users.observe(db) { where { Users.active eq true } }
//         .syncTo(index, keyOf = { it.id }, versionOf = { it.updatedAt }) { user ->
//             HybridDoc(embedder.embed(user.bio), text = "${user.name} ${user.bio}")
//         }
// }
// ```
//
// Two things are worth setting up beyond the defaults, both because embedding is the expensive part:
// carry a `SyncState` across runs when you also persist the index, so a reload does not re-embed a
// corpus that has not changed, and pass an `onError` handler so one failed embedding does not tear
// down the whole sync.

/** What to store for an entity in a [HybridIndex]. */
public class HybridDoc(
    public val vector: FloatArray,
    public val text: String,
    public val attributes: Map<String, String> = emptyMap(),
)

/**
 * What a sync has already indexed: entity key -> the version last written.
 *
 * A fresh state assumes an empty index, so the first snapshot re-indexes everything. That is the
 * right default for an index built in memory — and the wrong one when the index itself was reloaded
 * from disk or from a kemus store, where re-embedding the whole corpus on startup throws away exactly
 * what persisting the index bought you. Persist the versions alongside the index and hand them back:
 *
 * ```kotlin
 * val index = loadHybridIndex(kemus, "docs", KeyCodec.string) ?: HybridIndex(dimensions = 384)
 * val state = SyncState(kemus.getMap("docs.versions").orEmpty())
 *
 * scope.launch {
 *     docs.observe(db).syncTo(index, keyOf = { it.id }, versionOf = { it.updatedAt }, state = state) { … }
 * }
 * // on save: index.saveTo(kemus, "docs", KeyCodec.string); kemus.setMap("docs.versions", state.versions)
 * ```
 *
 * For that round trip to work the version has to be something you can store — a `String`, a `Long`,
 * a content hash. Versions are compared with `equals`, so use the same type going out and coming back.
 *
 * A state instance belongs to one sync at a time; it is not thread-safe.
 */
public class SyncState<K : Any>(
    initial: Map<K, Any?> = emptyMap(),
) {
    private val tracked = LinkedHashMap<K, Any?>(initial)

    /** Indexed keys and the version each was last indexed at, in insertion order. */
    public val versions: Map<K, Any?> get() = tracked

    /** Number of tracked entities. */
    public val size: Int get() = tracked.size

    internal fun isCurrent(key: K, version: Any?): Boolean =
        tracked.containsKey(key) && tracked[key] == version

    internal fun put(key: K, version: Any?) {
        tracked[key] = version
    }

    internal fun forget(key: K) {
        tracked.remove(key)
    }

    internal fun trackedKeys(): List<K> = tracked.keys.toList()
}

/** What a sync should do after [SyncErrorHandler] has seen a failure. */
public enum class SyncFailurePolicy {
    /**
     * Leave the entity untracked and carry on with the snapshot. Because it stays untracked, the next
     * snapshot that contains it tries again — a transient failure heals itself.
     */
    Skip,

    /** Rethrow, ending the collection. */
    Abort,
}

/**
 * Decides what a sync does when indexing one entity fails — a network hiccup in an embedding call, a
 * model that rejects a document, a store that is briefly unavailable. Receives the entity being
 * indexed (null when the failure came from removing a key) and the error.
 *
 * Without a handler such a failure propagates and the flow collection ends, which in practice means
 * the index silently stops following the data until something restarts the sync.
 *
 * ```kotlin
 * onError = { entity, error ->
 *     log.warn("indexing ${entity?.id} failed", error)
 *     SyncFailurePolicy.Skip
 * }
 * ```
 */
public typealias SyncErrorHandler<T> = suspend (entity: T?, error: Throwable) -> SyncFailurePolicy

/**
 * The reconciling engine, decoupled from kromus: for each snapshot, calls [upsert] for new/changed
 * entities and [remove] for those that dropped out. Change is detected by comparing [versionOf].
 *
 * @param keyOf stable identity of an entity.
 * @param versionOf a value that changes when the entity changes (e.g. `updatedAt`, a hash, or the
 *   entity itself). Unchanged entities are skipped, so [upsert] isn't re-run needlessly.
 * @param state what has already been indexed; pass a seeded [SyncState] when the index was reloaded
 *   rather than built from scratch.
 * @param onError what to do when [upsert] or [remove] throws; the default rethrows.
 */
public suspend fun <K : Any, T> Flow<List<T>>.reconcile(
    keyOf: (T) -> K,
    versionOf: (T) -> Any?,
    state: SyncState<K> = SyncState(),
    onError: SyncErrorHandler<T>? = null,
    upsert: suspend (T) -> Unit,
    remove: suspend (K) -> Unit,
) {
    collect { snapshot ->
        val seen = HashSet<K>(snapshot.size)
        for (entity in snapshot) {
            val key = keyOf(entity)
            seen.add(key)
            val version = versionOf(entity)
            if (state.isCurrent(key, version)) continue
            try {
                upsert(entity)
                // Tracked only after a successful upsert, so a skipped failure is retried next time.
                state.put(key, version)
            } catch (e: Throwable) {
                if (handle(onError, entity, e) == SyncFailurePolicy.Abort) throw e
            }
        }
        for (key in state.trackedKeys()) {
            if (key in seen) continue
            try {
                remove(key)
                state.forget(key)
            } catch (e: Throwable) {
                if (handle(onError, null, e) == SyncFailurePolicy.Abort) throw e
            }
        }
    }
}

private suspend fun <T> handle(
    onError: SyncErrorHandler<T>?,
    entity: T?,
    error: Throwable,
): SyncFailurePolicy {
    if (error is kotlinx.coroutines.CancellationException) throw error
    return onError?.invoke(entity, error) ?: SyncFailurePolicy.Abort
}

/** Syncs a [HybridIndex] from a snapshot flow; [document] produces the vector + text (+ attributes). */
public suspend fun <K : Any, T> Flow<List<T>>.syncTo(
    index: HybridIndex<K>,
    keyOf: (T) -> K,
    versionOf: (T) -> Any? = { it },
    state: SyncState<K> = SyncState(),
    onError: SyncErrorHandler<T>? = null,
    document: suspend (T) -> HybridDoc,
): Unit = reconcile(
    keyOf = keyOf,
    versionOf = versionOf,
    state = state,
    onError = onError,
    upsert = { entity ->
        val doc = document(entity)
        index.add(keyOf(entity), doc.vector, doc.text, doc.attributes)
    },
    remove = { key -> index.remove(key) },
)

/** Syncs a [VectorIndex] from a snapshot flow; [vector] produces the embedding. */
public suspend fun <K : Any, T> Flow<List<T>>.syncTo(
    index: VectorIndex<K>,
    keyOf: (T) -> K,
    versionOf: (T) -> Any? = { it },
    attributes: (T) -> Map<String, String> = { emptyMap() },
    state: SyncState<K> = SyncState(),
    onError: SyncErrorHandler<T>? = null,
    vector: suspend (T) -> FloatArray,
): Unit = reconcile(
    keyOf = keyOf,
    versionOf = versionOf,
    state = state,
    onError = onError,
    upsert = { entity -> index.add(keyOf(entity), vector(entity), attributes(entity)) },
    remove = { key -> index.remove(key) },
)

/** Syncs a [TextIndex] from a snapshot flow; [text] produces the document text. */
public suspend fun <K : Any, T> Flow<List<T>>.syncTo(
    index: TextIndex<K>,
    keyOf: (T) -> K,
    versionOf: (T) -> Any? = { it },
    attributes: (T) -> Map<String, String> = { emptyMap() },
    state: SyncState<K> = SyncState(),
    onError: SyncErrorHandler<T>? = null,
    text: suspend (T) -> String,
): Unit = reconcile(
    keyOf = keyOf,
    versionOf = versionOf,
    state = state,
    onError = onError,
    upsert = { entity -> index.add(keyOf(entity), text(entity), attributes(entity)) },
    remove = { key -> index.remove(key) },
)

/**
 * Syncs a [ConcurrentHybridIndex]: [document] runs outside the lock, so a slow embedding never blocks
 * a search in progress — the index is only held for the write itself.
 */
public suspend fun <K : Any, T> Flow<List<T>>.syncTo(
    index: ConcurrentHybridIndex<K>,
    keyOf: (T) -> K,
    versionOf: (T) -> Any? = { it },
    state: SyncState<K> = SyncState(),
    onError: SyncErrorHandler<T>? = null,
    document: suspend (T) -> HybridDoc,
): Unit = reconcile(
    keyOf = keyOf,
    versionOf = versionOf,
    state = state,
    onError = onError,
    upsert = { entity ->
        val doc = document(entity)
        index.add(keyOf(entity), doc.vector, doc.text, doc.attributes)
    },
    remove = { key -> index.remove(key) },
)

/** Syncs a [ConcurrentVectorIndex]; [vector] runs outside the lock. */
public suspend fun <K : Any, T> Flow<List<T>>.syncTo(
    index: ConcurrentVectorIndex<K>,
    keyOf: (T) -> K,
    versionOf: (T) -> Any? = { it },
    attributes: (T) -> Map<String, String> = { emptyMap() },
    state: SyncState<K> = SyncState(),
    onError: SyncErrorHandler<T>? = null,
    vector: suspend (T) -> FloatArray,
): Unit = reconcile(
    keyOf = keyOf,
    versionOf = versionOf,
    state = state,
    onError = onError,
    upsert = { entity ->
        val embedding = vector(entity)
        index.add(keyOf(entity), embedding, attributes(entity))
    },
    remove = { key -> index.remove(key) },
)
