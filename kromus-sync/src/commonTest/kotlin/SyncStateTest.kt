package io.github.kromus.sync

import io.github.kromus.HybridIndex
import io.github.kromus.Metric
import io.github.kromus.VectorIndex
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class Doc(
    val key: String,
    val version: Int,
    val vec: FloatArray,
)

class SyncStateTest {
    private fun docs(vararg keys: String, version: Int = 1) =
        keys.map { Doc(it, version, floatArrayOf(it.first().code.toFloat(), 1f)) }

    @Test
    fun aSeededStateSkipsWhatIsAlreadyIndexed() = runTest {
        // The realistic startup: the index came back from a store, so re-embedding its documents is
        // pure waste — and embedding is the expensive step the persisted index was meant to skip.
        val index = VectorIndex<String>(2, Metric.Cosine)
        val snapshot = docs("a", "b", "c")
        for (doc in snapshot) index.add(doc.key, doc.vec)

        val state = SyncState<String>(mapOf("a" to 1, "b" to 1, "c" to 1))
        var embedCalls = 0

        flowOf(snapshot).syncTo(index, keyOf = { it.key }, versionOf = { it.version }, state = state) {
            embedCalls++
            it.vec
        }

        assertEquals(0, embedCalls, "nothing changed, so nothing should be re-embedded")
        assertEquals(3, index.size)
        assertEquals(0, index.tombstones, "and nothing should be rewritten into the graph")
    }

    @Test
    fun aSeededStateStillPicksUpChangesAndRemovals() = runTest {
        val index = VectorIndex<String>(2, Metric.Cosine)
        for (doc in docs("a", "b")) index.add(doc.key, doc.vec)
        val state = SyncState<String>(mapOf("a" to 1, "b" to 1))

        val embedded = mutableListOf<String>()
        flowOf(listOf(Doc("a", 2, floatArrayOf(9f, 9f)), Doc("c", 1, floatArrayOf(3f, 3f)))).syncTo(
            index,
            keyOf = { it.key },
            versionOf = { it.version },
            state = state,
        ) {
            embedded.add(it.key)
            it.vec
        }

        assertEquals(listOf("a", "c"), embedded, "a changed version and a new key are indexed")
        assertEquals(setOf("a", "c"), index.keys.toSet(), "b dropped out of the snapshot")
        assertEquals(setOf("a", "c"), state.versions.keys)
    }

    @Test
    fun stateRecordsWhatCanBePersisted() = runTest {
        val index = VectorIndex<String>(2, Metric.Cosine)
        val state = SyncState<String>()

        flowOf(docs("a", "b", version = 7)).syncTo(
            index,
            keyOf = { it.key },
            versionOf = { it.version },
            state = state,
        ) { it.vec }

        assertEquals(mapOf<String, Any?>("a" to 7, "b" to 7), state.versions)
        assertEquals(2, state.size)
    }

    @Test
    fun aFailedUpsertAbortsByDefault() = runTest {
        val index = VectorIndex<String>(2, Metric.Cosine)
        assertFailsWith<IllegalStateException> {
            flowOf(docs("a", "b")).syncTo(index, keyOf = { it.key }, versionOf = { it.version }) {
                error("embedding backend is down")
            }
        }
    }

    @Test
    fun skippedFailuresAreRetriedOnTheNextSnapshot() = runTest {
        val index = VectorIndex<String>(2, Metric.Cosine)
        val failures = mutableListOf<String>()
        var failFirstCall = true

        flowOf(docs("a"), docs("a")).syncTo(
            index,
            keyOf = { it.key },
            versionOf = { it.version },
            onError = { entity, _ ->
                failures.add(entity?.key ?: "<removal>")
                SyncFailurePolicy.Skip
            },
        ) { doc ->
            if (failFirstCall) {
                failFirstCall = false
                error("transient failure")
            }
            doc.vec
        }

        assertEquals(listOf("a"), failures, "the handler sees the entity that failed")
        assertEquals(1, index.size, "a skipped entity stays untracked and is retried, so it lands eventually")
    }

    @Test
    fun anAbortingHandlerRethrows() = runTest {
        val index = VectorIndex<String>(2, Metric.Cosine)
        assertFailsWith<IllegalStateException> {
            flowOf(docs("a")).syncTo(
                index,
                keyOf = { it.key },
                versionOf = { it.version },
                onError = { _, _ -> SyncFailurePolicy.Abort },
            ) { error("fatal") }
        }
    }

    @Test
    fun oneBadDocumentDoesNotStopTheRest() = runTest {
        val index = VectorIndex<String>(2, Metric.Cosine)
        flowOf(docs("a", "b", "c")).syncTo(
            index,
            keyOf = { it.key },
            versionOf = { it.version },
            onError = { _, _ -> SyncFailurePolicy.Skip },
        ) { doc ->
            if (doc.key == "b") error("this one is unindexable")
            doc.vec
        }

        assertEquals(setOf("a", "c"), index.keys.toSet())
    }

    @Test
    fun syncsIntoAConcurrentIndex() = runTest {
        val index = HybridIndex<String>(dimensions = 2).concurrent()

        flowOf(docs("a", "b")).syncTo(index, keyOf = { it.key }, versionOf = { it.version }) {
            HybridDoc(it.vec, "document ${it.key}")
        }

        assertEquals(2, index.size())
        assertTrue(index.searchText("document", k = 5).isNotEmpty())
    }
}
