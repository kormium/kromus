package io.github.kromus.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A suspending readers-writer lock: any number of readers together, or one writer alone.
 *
 * **Readers take an atomic fast path.** An earlier version routed every acquire and release through a
 * single `Mutex`, which was correct and useless: two contended coroutine hand-offs per search cost
 * more than the search, and six readers ran no faster than one. Measured, that version turned 24 000
 * searches/s back into 5 300. So an uncontended read is now two atomic operations and touches no
 * shared lock at all; the queueing machinery is reached only when a writer is present.
 *
 * **Writer-preferring.** Once a writer is waiting, arriving readers stop taking the fast path and
 * queue behind it. Without that, a UI issuing a steady stream of searches would keep the read side
 * permanently occupied and the coroutine keeping the index fresh would never get in.
 *
 * The release path runs under [NonCancellable], so neither a cancelled holder nor a cancelled waiter
 * can strand the lock. Not reentrant: taking it while holding it deadlocks, as with a [Mutex].
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ReadWriteMutex {
    // >= 0: that many readers hold the lock. -1: a writer holds it.
    private val holders = AtomicInt(0)

    // Read by the readers' fast path, which is what makes the lock writer-preferring.
    private val writersWaiting = AtomicInt(0)

    // Guards the wait queues. Touched only when a writer is in the picture — never on the hot path.
    private val queues = Mutex()
    private val writerQueue = ArrayDeque<CompletableDeferred<Unit>>()
    private var readerGate: CompletableDeferred<Unit>? = null

    suspend fun <R> read(block: () -> R): R {
        acquireRead()
        try {
            return block()
        } finally {
            withContext(NonCancellable) { releaseRead() }
        }
    }

    suspend fun <R> write(block: () -> R): R {
        acquireWrite()
        try {
            return block()
        } finally {
            withContext(NonCancellable) { releaseWrite() }
        }
    }

    /** Tries to join the current readers without suspending. */
    private fun tryAcquireReadFast(): Boolean {
        if (writersWaiting.load() != 0) return false
        val current = holders.load()
        return current >= 0 && holders.compareAndSet(current, current + 1)
    }

    private suspend fun acquireRead() {
        while (true) {
            if (tryAcquireReadFast()) return
            val gate = queues.withLock {
                // Re-check inside: the writer may have finished between the failed attempt and here.
                if (tryAcquireReadFast()) return
                readerGate ?: CompletableDeferred<Unit>().also { readerGate = it }
            }
            gate.await()
        }
    }

    private suspend fun releaseRead() {
        val remaining = holders.addAndFetch(-1)
        // The queues are only worth taking when someone is actually waiting for the lock to empty.
        if (remaining == 0 && writersWaiting.load() > 0) {
            queues.withLock { handOff() }
        }
    }

    private suspend fun acquireWrite() {
        writersWaiting.addAndFetch(1)
        if (holders.compareAndSet(0, -1)) {
            writersWaiting.addAndFetch(-1)
            return
        }
        val signal = queues.withLock {
            if (holders.compareAndSet(0, -1)) {
                writersWaiting.addAndFetch(-1)
                return
            }
            CompletableDeferred<Unit>().also { writerQueue.addLast(it) }
        }
        // handOff marks us the holder before completing this, so there is nothing left to claim.
        signal.await()
        writersWaiting.addAndFetch(-1)
    }

    private suspend fun releaseWrite() {
        queues.withLock {
            holders.store(0)
            handOff()
        }
    }

    /**
     * Called holding [queues]: gives the free lock to the next writer, or opens the gate for readers.
     *
     * The CAS is not decoration. A reader can slip through the fast path between a release and this
     * call — it loaded `writersWaiting` a moment before the writer arrived — so the lock may no longer
     * be free. Handing it over anyway would put a writer inside the lock alongside a live reader; if it
     * is taken, that reader's own release runs this again.
     */
    private fun handOff() {
        val nextWriter = writerQueue.firstOrNull()
        if (nextWriter != null) {
            if (!holders.compareAndSet(0, -1)) return
            writerQueue.removeFirst()
            nextWriter.complete(Unit)
            return
        }
        val gate = readerGate ?: return
        readerGate = null
        // The woken readers re-run the fast path and count themselves in, so `holders` never runs
        // ahead of who actually got through.
        gate.complete(Unit)
    }
}
