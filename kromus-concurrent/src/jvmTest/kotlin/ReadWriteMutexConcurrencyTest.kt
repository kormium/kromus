package io.github.kromus.concurrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The guarantees of [ReadWriteMutex] only show up under real parallelism, which the JVM target has
 * and the single-threaded web targets do not — so these run here, on [Dispatchers.Default].
 */
class ReadWriteMutexConcurrencyTest {
    /** Two readers must be able to sit inside the lock at the same time; a plain Mutex could not. */
    @Test
    fun readersRunConcurrently() = runBlocking(Dispatchers.Default) {
        if (Runtime.getRuntime().availableProcessors() < 2) return@runBlocking

        val lock = ReadWriteMutex()
        val inFlight = AtomicInteger(0)
        val bothInside = AtomicBoolean(false)

        val readers = List(2) {
            async {
                lock.read {
                    val n = inFlight.incrementAndGet()
                    if (n == 2) bothInside.set(true)
                    // Wait for the peer, but never hang the suite if the machine won't run both.
                    val deadline = System.nanoTime() + 2_000_000_000L
                    while (inFlight.get() < 2 && System.nanoTime() < deadline) Thread.onSpinWait()
                    inFlight.decrementAndGet()
                }
            }
        }
        readers.awaitAll()

        assertTrue(bothInside.get(), "two readers never held the lock at the same time")
    }

    /** A writer must see the index to itself: no reader inside, no second writer. */
    @Test
    fun writerExcludesEveryoneElse() = runBlocking(Dispatchers.Default) {
        val lock = ReadWriteMutex()
        val activeReaders = AtomicInteger(0)
        val writing = AtomicBoolean(false)
        val violations = AtomicInteger(0)

        val jobs = List(16) { i ->
            async {
                repeat(40) {
                    if (i % 4 == 0) {
                        lock.write {
                            if (!writing.compareAndSet(false, true)) violations.incrementAndGet()
                            if (activeReaders.get() != 0) violations.incrementAndGet()
                            Thread.onSpinWait()
                            writing.set(false)
                        }
                    } else {
                        lock.read {
                            activeReaders.incrementAndGet()
                            if (writing.get()) violations.incrementAndGet()
                            Thread.onSpinWait()
                            activeReaders.decrementAndGet()
                        }
                    }
                }
            }
        }
        jobs.awaitAll()

        assertEquals(0, violations.get(), "reader/writer overlap observed")
        assertEquals(0, activeReaders.get())
    }

    /** Writer preference: a steady stream of searches must not keep an indexing coroutine out. */
    @Test
    fun writerIsNotStarvedByContinuousReaders() = runBlocking(Dispatchers.Default) {
        val lock = ReadWriteMutex()
        val readerScope = this
        val stop = AtomicBoolean(false)

        val readers = List(2) {
            readerScope.launch {
                while (isActive && !stop.get()) {
                    lock.read { Thread.onSpinWait() }
                    // An uncontended read never suspends; yield so this loop cannot monopolize a
                    // dispatcher thread on a small machine and mask what's being tested.
                    yield()
                }
            }
        }

        val acquired = withTimeoutOrNull(5_000) {
            repeat(20) { lock.write { Thread.onSpinWait() } }
            true
        }
        stop.set(true)
        readers.forEach { it.join() }

        assertNotNull(acquired, "writer starved by readers")
    }

    /** A coroutine cancelled while queued must not leave the lock held or a phantom waiter behind. */
    @Test
    fun cancellingAQueuedWaiterDoesNotStrandTheLock() = runBlocking(Dispatchers.Default) {
        val lock = ReadWriteMutex()
        val holderInside = AtomicBoolean(false)
        val releaseHolder = AtomicBoolean(false)

        val holder = launch {
            lock.write {
                holderInside.set(true)
                while (!releaseHolder.get()) Thread.onSpinWait()
            }
        }
        while (!holderInside.get()) delay(1)

        // These queue up behind the holder, then go away.
        val queued = List(3) { launch { lock.read { } } }
        val queuedWriter = launch { lock.write { } }
        delay(50)
        queued.forEach { it.cancel() }
        queuedWriter.cancel()

        releaseHolder.set(true)
        holder.join()

        // If a cancelled waiter had leaked ownership, this would never complete.
        withTimeout(5_000) {
            lock.write { }
            lock.read { }
        }
    }

    /** Cancelling a coroutine that already holds the lock must still release it. */
    @Test
    fun cancellingAHolderStillReleasesTheLock() = runBlocking(Dispatchers.Default) {
        val lock = ReadWriteMutex()
        val inside = AtomicBoolean(false)

        val holder = launch {
            lock.write {
                inside.set(true)
                Thread.sleep(100)
            }
            // The block finishes; release runs NonCancellable even though this job is cancelled.
        }
        while (!inside.get()) delay(1)
        holder.cancel()
        holder.join()

        withTimeout(5_000) { lock.write { } }
    }

    /** Nothing above may leak: after the dust settles the lock is free for a fresh writer. */
    @Test
    fun lockIsReusableAfterAThrowingBlock() = runBlocking(Dispatchers.Default) {
        val lock = ReadWriteMutex()
        val failures = List(8) {
            async { runCatching { lock.write { error("boom") } } }
        }
        failures.awaitAll()

        withTimeout(5_000) { assertEquals(1, lock.write { 1 }) }
    }
}
