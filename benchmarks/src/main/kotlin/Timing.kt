package io.github.kromus.benchmarks

/** Wall-clock timing helpers. Everything here reports milliseconds or microseconds, never raw nanos. */
object Timing {
    inline fun millis(block: () -> Unit): Double {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000.0
    }

    /**
     * Runs [block] once per index in `0 until count` and returns the per-call latencies in
     * microseconds, after enough warmup iterations for the JIT to have compiled the hot path —
     * without it the first measured queries are interpreted and the numbers are noise.
     */
    inline fun latencies(count: Int, warmup: Int = maxOf(count, 200), block: (Int) -> Unit): DoubleArray {
        repeat(warmup) { block(it % count) }
        val out = DoubleArray(count)
        for (i in 0 until count) {
            val start = System.nanoTime()
            block(i)
            out[i] = (System.nanoTime() - start) / 1000.0
        }
        return out
    }
}

fun DoubleArray.mean(): Double = if (isEmpty()) 0.0 else sum() / size

fun DoubleArray.percentile(p: Double): Double {
    if (isEmpty()) return 0.0
    val sorted = sortedArray()
    val index = ((sorted.size - 1) * p).toInt()
    return sorted[index]
}

/** Best-effort heap usage; the JVM only approximates this, so it is reported as such. */
fun usedHeapBytes(): Long {
    val runtime = Runtime.getRuntime()
    repeat(3) {
        System.gc()
        Thread.sleep(50)
    }
    return runtime.totalMemory() - runtime.freeMemory()
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GiB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MiB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.1f KiB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}
