package io.github.kromus.concurrent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A suspending readers-writer lock: any number of readers may hold it at once, a writer holds it
 * alone.
 *
 * This is the primitive the concurrent index wrappers are built from, and it is public because the
 * same shape is useful around anything a kromus index is paired with (the full vectors behind a
 * quantized index, a document store, a persisted snapshot). A plain [Mutex] would also make an index
 * safe, but it would serialize *searches* against each other — and searches are the hot path, are
 * read-only in kromus, and are exactly what you want running on every core.
 *
 * ```
 * val lock = ReadWriteMutex()
 * lock.read { index.search(query, k = 10) }   // concurrent with other readers
 * lock.write { index.add(key, vector) }       // exclusive
 * ```
 *
 * **Writer-preferring.** A waiting writer blocks *new* readers from overtaking it, so a steady
 * stream of searches cannot starve an indexing coroutine. Readers already holding the lock always
 * finish first; the writer waits for them to drain.
 *
 * **Not reentrant.** Taking the lock again from inside [read] or [write] deadlocks — the inner
 * `write` waits for the outer holder to release, which cannot happen until the inner call returns.
 * Keep blocks self-contained and never call one guarded API from inside another's block.
 *
 * **Cancellation-safe.** A coroutine cancelled while queued leaves no trace; one cancelled while
 * holding the lock still releases it, because release runs [NonCancellable].
 */
public class ReadWriteMutex {
    // Guards the bookkeeping below. Held only for the few statements it takes to update the state or
    // hand the lock on — never across a caller's block — so it is never itself contended for long.
    private val gate = Mutex()

    private var readers = 0
    private var writing = false

    // Queued waiters, granted the lock by whoever releases it (hand-off): the releaser updates
    // `readers`/`writing` on the waiter's behalf and only then completes its deferred, so a waiter
    // that resumes is already the owner and no third coroutine can slip in between.
    private val readerQueue = ArrayDeque<CompletableDeferred<Unit>>()
    private val writerQueue = ArrayDeque<CompletableDeferred<Unit>>()

    /**
     * Runs [block] with the lock held in shared mode, then releases it. Concurrent with other
     * [read] callers, mutually exclusive with [write].
     *
     * [block] is not a suspending function on purpose: it runs to completion as one indivisible
     * step, so no other coroutine can observe a half-finished operation.
     */
    public suspend fun <T> read(block: () -> T): T {
        acquireRead()
        try {
            return block()
        } finally {
            release(wasWrite = false)
        }
    }

    /**
     * Runs [block] with the lock held exclusively, then releases it. No other [read] or [write] runs
     * for its duration, so a multi-step mutation (say, removing one key and adding another) is
     * observed by searches as a single change.
     */
    public suspend fun <T> write(block: () -> T): T {
        acquireWrite()
        try {
            return block()
        } finally {
            release(wasWrite = true)
        }
    }

    private suspend fun acquireRead() {
        val waiter = gate.withLock {
            // Deferring to queued writers is what makes the lock writer-preferring.
            if (!writing && writerQueue.isEmpty()) {
                readers++
                null
            } else {
                CompletableDeferred<Unit>().also { readerQueue.add(it) }
            }
        } ?: return
        awaitGrant(waiter, readerQueue, wasWrite = false)
    }

    private suspend fun acquireWrite() {
        val waiter = gate.withLock {
            if (!writing && readers == 0 && writerQueue.isEmpty()) {
                writing = true
                null
            } else {
                CompletableDeferred<Unit>().also { writerQueue.add(it) }
            }
        } ?: return
        awaitGrant(waiter, writerQueue, wasWrite = true)
    }

    /**
     * Waits to be handed the lock. If the caller is cancelled first, either it is still queued (drop
     * it) or it has already been granted ownership (release it, so the grant is passed on rather
     * than lost). Both branches run [NonCancellable]: the coroutine is already cancelled, and
     * suspending normally would abort mid-cleanup and strand the lock.
     */
    private suspend fun awaitGrant(
        waiter: CompletableDeferred<Unit>,
        queue: ArrayDeque<CompletableDeferred<Unit>>,
        wasWrite: Boolean,
    ) {
        try {
            waiter.await()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                val granted = gate.withLock { !queue.remove(waiter) }
                if (granted) release(wasWrite)
            }
            throw e
        }
    }

    private suspend fun release(wasWrite: Boolean) {
        withContext(NonCancellable) {
            gate.withLock {
                if (wasWrite) writing = false else readers--
                grantNext()
            }
        }
    }

    /** Hands the free lock to the next waiter(s). Must be called with [gate] held. */
    private fun grantNext() {
        if (writing || readers > 0) return
        val writer = writerQueue.removeFirstOrNull()
        if (writer != null) {
            writing = true
            writer.complete(Unit)
            return
        }
        // No writer waiting: release the whole reader batch at once, which is the point of the lock.
        while (readerQueue.isNotEmpty()) {
            readers++
            readerQueue.removeFirst().complete(Unit)
        }
    }
}
