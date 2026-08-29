package io.github.kromus.sync

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A small reuse pool for searchers, claimed by compare-and-set rather than under a lock.
 *
 * A searcher carries a visited-mark array sized to the graph — 200 KB on a 50 000-node index — so
 * allocating one per query would hand back exactly the allocation that keeping traversal state
 * reusable was meant to avoid.
 *
 * The obvious implementation, a `Mutex` around a deque, does not work here. Measured against six
 * concurrent readers it cost more than the searches did: two contended coroutine hand-offs per query
 * dropped throughput from 23 000 searches/s to 5 300 — the same mistake, and the same magnitude, as
 * routing the lock's read path through a shared mutex. Claiming a slot is one CAS instead.
 *
 * A borrow past [capacity] simply allocates and is dropped on return rather than retained, so a burst
 * of readers costs memory only while it lasts. Scanning the slots is O([capacity]) of plain CAS
 * attempts, which at these sizes is far below what one search costs.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class SearcherPool<S : Any>(
    private val capacity: Int = 16,
    private val factory: () -> S,
) {
    private val slots = Array(capacity) { AtomicReference<S?>(null) }

    fun borrow(): S {
        for (slot in slots) {
            val existing = slot.load() ?: continue
            if (slot.compareAndSet(existing, null)) return existing
        }
        return factory()
    }

    fun giveBack(searcher: S) {
        for (slot in slots) {
            if (slot.load() == null && slot.compareAndSet(null, searcher)) return
        }
        // Every slot is taken: let this one go rather than growing without bound.
    }
}
