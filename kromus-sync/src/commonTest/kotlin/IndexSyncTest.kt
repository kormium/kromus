package io.github.kromus.sync

import io.github.kromus.Metric
import io.github.kromus.VectorIndex
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class Item(val key: String, val version: Int, val vec: FloatArray)

class IndexSyncTest {

    @Test
    fun reconcileUpsertsChangedAndRemovesGone() = runTest {
        val s1 = listOf(Item("a", 1, floatArrayOf()), Item("b", 1, floatArrayOf()))
        val s2 = listOf(Item("a", 2, floatArrayOf()), Item("c", 1, floatArrayOf())) // a changed, b gone, c new
        val s3 = listOf(Item("a", 2, floatArrayOf()))                                // c gone, a unchanged

        val upserts = mutableListOf<String>()
        val removes = mutableListOf<String>()

        flowOf(s1, s2, s3).reconcile(
            keyOf = { it.key },
            versionOf = { it.version },
            upsert = { upserts.add(it.key) },
            remove = { removes.add(it) },
        )

        assertEquals(listOf("a", "b", "a", "c"), upserts, "only new/changed entities are upserted")
        assertEquals(listOf("b", "c"), removes, "entities that drop out are removed")
    }

    @Test
    fun syncToVectorIndexReflectsSnapshotsAndSkipsUnchanged() = runTest {
        val dim = 4
        fun v(vararg xs: Float) = floatArrayOf(*xs)
        val index = VectorIndex<String>(dim, Metric.Cosine)

        val s1 = listOf(Item("a", 1, v(1f, 0f, 0f, 0f)), Item("b", 1, v(0f, 1f, 0f, 0f)))
        val s2 = listOf(Item("a", 2, v(0f, 0f, 1f, 0f)), Item("c", 1, v(0f, 0f, 0f, 1f)))
        val s3 = listOf(Item("a", 2, v(0f, 0f, 1f, 0f)))

        var embedCalls = 0
        flowOf(s1, s2, s3).syncTo(index, keyOf = { it.key }, versionOf = { it.version }) {
            embedCalls++
            it.vec
        }

        assertEquals(4, embedCalls, "embed only for a(s1), b(s1), a(s2 changed), c(s2) — not a(s3, unchanged)")
        assertEquals(1, index.size)
        assertTrue("a" in index)
        assertFalse("b" in index)
        assertFalse("c" in index)
    }

    @Test
    fun initialSnapshotIndexesEverything() = runTest {
        val index = VectorIndex<Int>(2, Metric.Cosine)
        val snapshot = (0 until 10).map { Item(it.toString(), 1, floatArrayOf(it.toFloat(), 1f)) }
        flowOf(snapshot).syncTo(index, keyOf = { it.key.toInt() }, versionOf = { it.version }) { it.vec }
        assertEquals(10, index.size)
    }
}
