package io.github.kromus.sync

import io.github.kromus.HybridIndex
import io.github.kromus.TextIndex
import io.github.kromus.VectorIndex
import kotlinx.coroutines.flow.Flow

/**
 * Keeps a kromus index in step with a stream of *snapshots* — a `Flow<List<T>>` that re-emits the
 * current result set whenever the underlying data changes. That is exactly the shape produced by
 * `kormium-observe` (`Table.observe(db) { … }`), Room-style `Flow<List<T>>`, or any custom flow, so
 * kromus-sync closes the "keep the index fresh" gap without depending on any particular data layer.
 *
 * On each snapshot it reconciles against what it has already indexed:
 * - entries new or whose [version][reconcile] changed are re-embedded and upserted,
 * - entries no longer present are removed.
 *
 * Only changed entries hit the (possibly expensive) embedding step. Launch it in a scope; it collects
 * until the flow completes or the scope is cancelled.
 *
 * ```kotlin
 * scope.launch {
 *     Users.observe(db) { where { Users.active eq true } }
 *         .syncTo(index, keyOf = { it.id }, versionOf = { it.updatedAt }) { user ->
 *             HybridDoc(embedder.embed(user.bio), text = "${user.name} ${user.bio}")
 *         }
 * }
 * ```
 */

/** What to store for an entity in a [HybridIndex]. */
public class HybridDoc(
    public val vector: FloatArray,
    public val text: String,
    public val attributes: Map<String, String> = emptyMap(),
)

/**
 * The reconciling engine, decoupled from kromus: for each snapshot, calls [upsert] for new/changed
 * entities and [remove] for those that dropped out. Change is detected by comparing [versionOf].
 *
 * @param keyOf stable identity of an entity.
 * @param versionOf a value that changes when the entity changes (e.g. `updatedAt`, a hash, or the
 *   entity itself). Unchanged entities are skipped, so [upsert] isn't re-run needlessly.
 */
public suspend fun <K : Any, T> Flow<List<T>>.reconcile(
    keyOf: (T) -> K,
    versionOf: (T) -> Any?,
    upsert: suspend (T) -> Unit,
    remove: suspend (K) -> Unit,
) {
    val tracked = HashMap<K, Any?>()
    collect { snapshot ->
        val seen = HashSet<K>(snapshot.size)
        for (entity in snapshot) {
            val key = keyOf(entity)
            seen.add(key)
            val version = versionOf(entity)
            if (!tracked.containsKey(key) || tracked[key] != version) {
                upsert(entity)
                tracked[key] = version
            }
        }
        val removed = tracked.keys.filter { it !in seen }
        for (key in removed) {
            remove(key)
            tracked.remove(key)
        }
    }
}

/** Syncs a [HybridIndex] from a snapshot flow; [document] produces the vector + text (+ attributes). */
public suspend fun <K : Any, T> Flow<List<T>>.syncTo(
    index: HybridIndex<K>,
    keyOf: (T) -> K,
    versionOf: (T) -> Any? = { it },
    document: suspend (T) -> HybridDoc,
): Unit = reconcile(
    keyOf = keyOf,
    versionOf = versionOf,
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
    vector: suspend (T) -> FloatArray,
): Unit = reconcile(
    keyOf = keyOf,
    versionOf = versionOf,
    upsert = { entity -> index.add(keyOf(entity), vector(entity), attributes(entity)) },
    remove = { key -> index.remove(key) },
)

/** Syncs a [TextIndex] from a snapshot flow; [text] produces the document text. */
public suspend fun <K : Any, T> Flow<List<T>>.syncTo(
    index: TextIndex<K>,
    keyOf: (T) -> K,
    versionOf: (T) -> Any? = { it },
    attributes: (T) -> Map<String, String> = { emptyMap() },
    text: suspend (T) -> String,
): Unit = reconcile(
    keyOf = keyOf,
    versionOf = versionOf,
    upsert = { entity -> index.add(keyOf(entity), text(entity), attributes(entity)) },
    remove = { key -> index.remove(key) },
)
