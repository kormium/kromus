package io.github.kromus.samples.sync

import io.github.kromus.Metric
import io.github.kromus.VectorIndex
import io.github.kromus.samples.common.ToyEmbedder
import io.github.kromus.sync.syncTo
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

/** A note in a notes app. `rev` bumps every time the note is edited. */
private class Note(val id: Int, val rev: Int, val text: String)

/**
 * Keep search in step with your data — automatically.
 *
 * As the user adds, edits and deletes notes, the search index follows along. Here the "notes app" is
 * a flow of three snapshots; in a real app it's `Users.observe(db) { … }` from kormium-observe — the
 * same shape, `Flow<List<T>>`.
 *
 * Run: `./gradlew :samples:sync:run`
 */
fun main() = runBlocking {
    val embedder = ToyEmbedder()
    val index = VectorIndex<Int>(dimensions = embedder.dimensions, metric = Metric.Cosine)

    val afterAdding = listOf(
        Note(1, 1, "buy sourdough starter"),
        Note(2, 1, "read the Kotlin coroutines docs"),
    )
    val afterEditing = listOf(
        Note(1, 2, "buy sourdough starter and flour"), // note 1 edited
        Note(3, 1, "plan a hiking trip in the Alps"),  // note 2 deleted, note 3 added
    )
    val noChange = afterEditing // user opens the app again; nothing changed

    var embedCalls = 0
    val notesByRun = flowOf(afterAdding, afterEditing, noChange)
    notesByRun.syncTo(index, keyOf = { it.id }, versionOf = { it.rev }) { note ->
        embedCalls++
        embedder.embed(note.text)
    }

    println("The index now holds ${index.size} live note(s): ${(1..3).filter { it in index }}")
    println("Embeddings computed: $embedCalls (the unchanged note on the last run was skipped)\n")

    // Search the up-to-date index.
    for (query in listOf("baking", "mountains")) {
        val hit = index.search(embedder.embed(query), k = 1).firstOrNull()?.key
        println("Search \"$query\" → note #$hit")
    }
}
