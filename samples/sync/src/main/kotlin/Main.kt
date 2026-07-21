package io.github.kromus.samples.sync

import io.github.kromus.Metric
import io.github.kromus.VectorIndex
import io.github.kromus.sync.syncTo
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

/** A row from your data layer. `rev` bumps whenever the row changes. */
private class Note(val id: Int, val rev: Int, val vector: FloatArray)

/**
 * Keep an index in step with changing data. Here the "data layer" is a flow of three snapshots; in a
 * real app it's `Users.observe(db) { … }` from kormium-observe — same shape, `Flow<List<T>>`.
 *
 * Run: `./gradlew :samples:sync:run`
 */
fun main() = runBlocking {
    val index = VectorIndex<Int>(dimensions = 3, metric = Metric.Cosine)

    val snapshot1 = listOf(Note(1, 1, v(1f, 0f, 0f)), Note(2, 1, v(0f, 1f, 0f)))
    val snapshot2 = listOf(Note(1, 2, v(0f, 0f, 1f)), Note(3, 1, v(1f, 1f, 0f))) // 1 edited, 2 deleted, 3 added
    val snapshot3 = listOf(Note(1, 2, v(0f, 0f, 1f)))                             // 3 deleted, 1 unchanged

    var embedCalls = 0
    flowOf(snapshot1, snapshot2, snapshot3).syncTo(
        index,
        keyOf = { it.id },
        versionOf = { it.rev },        // re-embed only when the row actually changed
    ) { note ->
        embedCalls++
        note.vector
    }

    println("final index: ${index.size} note(s), keys = ${(1..3).filter { it in index }}")
    println("embed calls: $embedCalls  (the unchanged row in snapshot 3 was skipped)")
}

private fun v(vararg xs: Float) = floatArrayOf(*xs)
