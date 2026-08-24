package io.github.kromus.benchmarks

/**
 * The kromus benchmark suite: recall against exact search, query latency, what quantization costs,
 * what a traversal budget saves on selective filters, and what churn does to an index.
 *
 * ```
 * ./gradlew :benchmarks:run                                  # defaults below
 * ./gradlew :benchmarks:run --args="--vectors 100000 --dim 384"
 * ./gradlew :benchmarks:run --args="--quick"                 # smoke run, seconds
 * ```
 *
 * Numbers are single-threaded and machine-specific; what travels between machines is the shape —
 * recall against `efSearch`, the ratios between quantization modes, the effect of a budget.
 */
fun main(args: Array<String>) {
    val options = Options.parse(args)
    println("kromus benchmarks — ${options.vectors} vectors, ${options.dimensions} dims, ${options.queries} queries")
    println("(single-threaded, JVM ${System.getProperty("java.version")})")

    print("generating the dataset… ")
    val dataset = Dataset.generate(options.vectors, options.dimensions, options.queries, spread = options.spread)
    println("done")

    print("computing exact top-${options.k} by brute force… ")
    var truth: List<IntArray> = emptyList()
    val bruteForceMs = Timing.millis { truth = dataset.groundTruth(options.k) }
    val bruteForcePerQuery = bruteForceMs * 1000 / options.queries
    println("done in ${"%.0f".format(bruteForceMs)} ms (${"%.0f".format(bruteForcePerQuery)} µs per query)")
    val contrast = dataset.contrast(options.k)
    println(
        "dataset contrast: ${"%.2f".format(contrast)}× " +
            "(how much closer the true k-th neighbour is than an average point)",
    )

    val reports = buildList {
        println("\nrunning: recall sweep")
        add(recallSweep(dataset, truth, options.k, options.efValues))
        println("running: quantization")
        add(quantizationComparison(dataset, truth, options.k, options.ef))
        println("running: filters and budget")
        add(filterBudget(dataset, options.k))
        println("running: churn and compaction")
        add(compaction(dataset, options.k))
        println("running: corpus hardness")
        add(hardnessSweep(minOf(options.vectors, 10_000), options.dimensions, options.queries, options.k, options.ef))
    }

    val output = buildString {
        append("\n\n## Benchmarks\n\n")
        append(
            "`${options.vectors}` vectors × `${options.dimensions}` dims, `${options.queries}` queries, " +
                "single-threaded, cluster spread `${options.spread}` " +
                "(contrast ${"%.2f".format(contrast)}× — how much closer the true k-th neighbour is than an " +
                "average corpus member; the lower it is, the harder the corpus is for any approximate " +
                "index). Exact brute-force search over the same corpus costs " +
                "**${"%.0f".format(bruteForcePerQuery)} µs** per query — the baseline an approximate " +
                "index has to beat.\n",
        )
        for (report in reports) append(report.render())
    }
    println(output)
}

class Options(
    val vectors: Int,
    val dimensions: Int,
    val queries: Int,
    val k: Int,
    val ef: Int,
    val spread: Float,
    val efValues: List<Int>,
) {
    companion object {
        fun parse(args: Array<String>): Options {
            var vectors = 50_000
            var dimensions = 128
            var queries = 200
            var k = 10
            var ef = 64
            var spread = 1f

            var i = 0
            while (i < args.size) {
                when (val arg = args[i]) {
                    "--quick" -> {
                        vectors = 5_000
                        queries = 50
                    }
                    "--vectors", "-n" -> vectors = args[++i].toInt()
                    "--dim", "-d" -> dimensions = args[++i].toInt()
                    "--queries", "-q" -> queries = args[++i].toInt()
                    "-k" -> k = args[++i].toInt()
                    "--ef" -> ef = args[++i].toInt()
                    "--spread" -> spread = args[++i].toFloat()
                    "--help", "-h" -> {
                        println(
                            """
                            usage: benchmarks [--quick] [-n vectors] [-d dimensions] [-q queries]
                                              [-k topK] [--ef efSearch] [--spread clusterSpread]
                            """.trimIndent(),
                        )
                        kotlin.system.exitProcess(0)
                    }
                    else -> error("unknown argument '$arg' (try --help)")
                }
                i++
            }
            return Options(vectors, dimensions, queries, k, ef, spread, listOf(16, 32, 64, 128, 256))
        }
    }
}
