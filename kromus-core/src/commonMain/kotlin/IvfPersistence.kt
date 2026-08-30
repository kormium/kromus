package io.github.kromus

// Persistence for a clustered index.
//
// The layout is where this index type earns its keep. Centroids and the cluster table are small and
// go together at the front; the vectors follow, laid out cluster by cluster, so answering a query
// means reading a handful of contiguous runs rather than touching pages scattered across the file.
// That is the property a graph index cannot have — its traversal goes wherever the data says, and no
// arrangement of the file changes that.

private const val IVF_FORMAT: Int = 1

private const val S_CONFIG = "CNFG"
private const val S_CENTROIDS = "CNTR"
private const val S_CLUSTERS = "CLST"
private const val S_VECTORS = "VECT"
private const val S_ENTRIES = "ENTR"

/**
 * Serializes this clustered index.
 *
 * @param provenance what produced it — see the `expect` parameter of [decodeIvfIndex].
 */
public fun <K> IvfIndex<K>.encodeToByteArray(
    keyCodec: KeyCodec<K>,
    provenance: String? = null,
): ByteArray {
    // Bound outside the section lambdas: inside them the receiver is a ByteWriter, and `size` there
    // means something else entirely.
    val stored = storedVectors
    val c = ContainerWriter(KIND_IVF, IVF_FORMAT, provenance)

    c.section(S_CONFIG) {
        int(dimensions)
        byte(metric.ordinal)
        int(config.clusters)
        int(config.nprobe)
        float(config.targetRecall)
        int(config.assignments)
        byte(config.routing.ordinal)
        long(config.seed)
        int(config.iterations)
        byte(config.quantization.ordinal)
        int(stored)
        int(clusterCount)
        int(size)
        // The effective probe count and what it was measured to recover. Stored rather than
        // recomputed: measuring needs the corpus, and a loaded index is meant to skip that work.
        int(nprobe)
        float(estimatedRecall)
    }

    c.section(S_CENTROIDS) { for (x in centroids) float(x) }

    // clusterCount + 1 boundaries, so a cluster is the half-open range between neighbours.
    c.section(S_CLUSTERS) { for (start in clusterStarts) int(start) }

    c.section(S_VECTORS) {
        for (id in 0 until stored) {
            store.writeVector(id, this)
        }
    }

    c.section(S_ENTRIES) {
        val pool = StringPoolWriter()
        val entries = ByteWriter()
        for (id in 0 until stored) {
            entries.bytes(keyCodec.encode(keyOf[id]))
            val attrs = attrsOf[id]
            entries.short(attrs.size)
            for ((ak, av) in attrs.canonical()) {
                entries.int(pool.idOf(ak))
                entries.int(pool.idOf(av))
            }
        }
        pool.writeTo(this)
        raw(entries.toByteArray())
    }

    c.section("ORIG") { for (o in originOf) int(o) }
    // Present only when routing is by graph; a reader takes its absence as "scan the centroids".
    router?.let { graph -> c.section("ROUT") { raw(graph.encodeToByteArray(KeyCodec.int)) } }

    return c.toByteArray()
}

/**
 * Reconstructs a clustered index produced by [encodeToByteArray].
 *
 * @param expect refuse the index unless it recorded this provenance; see [decodeVectorIndex].
 * @param store the quantizer the index was built with, if it was one of your own. Nothing in the
 *   bytes names it, so reading with the wrong one is not detected — it decodes and returns nonsense.
 * @throws KromusFormatException if [bytes] are not a clustered index this build can read.
 */
public fun <K> decodeIvfIndex(
    bytes: ByteArray,
    keyCodec: KeyCodec<K>,
    expect: String? = null,
    store: VectorStoreFactory? = null,
): IvfIndex<K> =
    decodeIvfIndex(
        ContainerReader(bytes, KIND_IVF, IVF_FORMAT, expect),
        keyCodec,
        skipVectors = false,
        factory = store,
    ).index

/** A decoded index, plus where its vectors sit in the bytes it came from. */
internal class LoadedIvfIndex<K>(
    val index: IvfIndex<K>,
    val vectorsOffset: Int,
    val vectorsLength: Int,
)

internal fun <K> decodeIvfIndex(
    c: ContainerReader,
    keyCodec: KeyCodec<K>,
    skipVectors: Boolean,
    factory: VectorStoreFactory? = null,
): LoadedIvfIndex<K> {
    val cfg = c.section(S_CONFIG)
    val dimensions = cfg.int()
    if (dimensions < 1) throw KromusFormatException("corrupt kromus index: dimensions $dimensions")
    val metric = cfg.enumValue(Metric.entries, "metric")
    val config = IvfConfig(
        clusters = cfg.int(),
        nprobe = cfg.int(),
        targetRecall = cfg.float(),
        assignments = cfg.int(),
        routing = cfg.enumValue(Routing.entries, "routing"),
        seed = cfg.long(),
        iterations = cfg.int(),
        quantization = cfg.enumValue(Quantization.entries, "quantization"),
    )
    val n = cfg.int()
    val clusterCount = cfg.int()
    val distinctEntries = cfg.int()
    val nprobe = cfg.int()
    val estimatedRecall = cfg.float()
    if (nprobe < 1) throw KromusFormatException("corrupt kromus index: nprobe $nprobe")
    if (n < 0) throw KromusFormatException("corrupt kromus index: entry count $n")
    if (clusterCount < 0) throw KromusFormatException("corrupt kromus index: cluster count $clusterCount")

    // The table makes every one of these checkable before a single record is read.
    // A custom store defines its own stride, so the store is built before the length is checked
    // against it — the check is still worth making, it just cannot assume the built-in layout.
    val store = factory?.create(dimensions, metric) ?: Hnsw.newStore(dimensions, metric, config.quantization)
    val stride = store.strideBytes
    if (c.lengthOf(S_VECTORS).toLong() != n.toLong() * stride) {
        throw KromusFormatException(
            "corrupt kromus index: the vector section is ${c.lengthOf(S_VECTORS)} byte(s), " +
                "but $n entr(ies) at $stride byte(s) each need ${n.toLong() * stride}",
        )
    }
    if (c.lengthOf(S_CENTROIDS).toLong() != clusterCount.toLong() * dimensions * 4) {
        throw KromusFormatException(
            "corrupt kromus index: the centroid section is ${c.lengthOf(S_CENTROIDS)} byte(s) for " +
                "$clusterCount centroid(s) of $dimensions dimension(s)",
        )
    }
    if (c.lengthOf(S_CLUSTERS) != (clusterCount + 1) * 4) {
        throw KromusFormatException(
            "corrupt kromus index: the cluster table is ${c.lengthOf(S_CLUSTERS)} byte(s) for " +
                "$clusterCount cluster(s)",
        )
    }

    val centroidReader = c.section(S_CENTROIDS)
    val centroids = FloatArray(clusterCount * dimensions) { centroidReader.float() }

    val clusterReader = c.section(S_CLUSTERS)
    val starts = IntArray(clusterCount + 1) { clusterReader.int() }
    if (starts.first() != 0 || starts.last() != n) {
        throw KromusFormatException(
            "corrupt kromus index: the cluster table runs ${starts.first()}..${starts.last()}, " +
                "but the index holds $n entr(ies)",
        )
    }
    for (i in 1..clusterCount) {
        if (starts[i] < starts[i - 1]) {
            throw KromusFormatException("corrupt kromus index: cluster ${i - 1} runs backwards")
        }
    }

    // Skipping this is the point of a streamed load: the vectors are the bulk of the file, and a
    // reader that will page them in a cluster at a time has no reason to inflate them first.
    if (!skipVectors) {
        val vectors = c.section(S_VECTORS)
        repeat(n) {
            store.readVector(vectors)
        }
    }

    val entries = c.section(S_ENTRIES)
    val pool = StringPoolReader(entries)
    val keyOf = ArrayList<K>(n)
    val attrsOf = ArrayList<Map<String, String>>(n)
    repeat(n) {
        keyOf.add(keyCodec.decode(entries.bytes()))
        val attrCount = entries.shortCount(8, "attribute")
        if (attrCount == 0) {
            attrsOf.add(emptyMap())
        } else {
            val attrs = HashMap<String, String>(attrCount * 2)
            repeat(attrCount) {
                val ak = pool.get(entries.int())
                attrs[ak] = pool.get(entries.int())
            }
            attrsOf.add(attrs)
        }
    }

    val originReader = c.section("ORIG")
    val originOf = IntArray(n) { originReader.int() }
    val router = if (c.has("ROUT")) decodeVectorIndex(c.sectionBytes("ROUT"), KeyCodec.int) else null

    return LoadedIvfIndex(
        IvfIndex(
            dimensions,
            metric,
            config,
            centroids,
            clusterCount,
            starts,
            store,
            keyOf,
            attrsOf,
            originOf,
            router,
            distinctEntries,
            blocks = null,
            nprobe = nprobe,
            estimatedRecall = estimatedRecall,
        ),
        c.offsetOf(S_VECTORS),
        c.lengthOf(S_VECTORS),
    )
}

/**
 * Opens a clustered index that reads its vectors from [source] instead of holding them.
 *
 * Only the header and the small sections are read: centroids, the cluster table, keys and attributes.
 * On a 50 000-entry index that is a few megabytes against tens for the vectors, and the vectors are
 * never read at all until a query asks for a cluster — which then arrives as one contiguous run, the
 * arrangement this whole index type exists for.
 *
 * Pass a [ByteArraySource] over a blob already in memory and this is an honest baseline rather than a
 * saving: the array is still held. The memory is reclaimed when [source] reads from somewhere that is
 * not the heap — a file, through `kromus-files` or a [ByteSource] of your own.
 *
 * The index keeps [source] open for as long as it can be searched; closing it invalidates the index.
 *
 * There is no custom-store parameter here, unlike [decodeIvfIndex], for the same reason binary is
 * refused below: a streamed scan reads the stored bytes directly, and only the built-in layouts are
 * known to it. A store of your own has to be decoded rather than streamed.
 *
 * @throws KromusFormatException if [source] does not hold a clustered index this build can read, or if
 *   the quantization is [Quantization.Binary], whose query path is a per-query lookup table that a
 *   byte-scanning copy could only duplicate or silently disagree with — and whose codes are small
 *   enough that streaming them saves nothing worth the risk.
 */
public fun <K> openIvfIndex(
    source: ByteSource,
    keyCodec: KeyCodec<K>,
    expect: String? = null,
): IvfIndex<K> {
    val c = ContainerReader(source, KIND_IVF, IVF_FORMAT, expect)
    val loaded = decodeIvfIndex(c, keyCodec, skipVectors = true, factory = null)
    if (!BlockDistance.supports(loaded.index.config.quantization)) {
        throw KromusFormatException(
            "a ${loaded.index.config.quantization} clustered index cannot be streamed: its query path " +
                "is built per query, and its vectors are small enough that there is nothing to reclaim",
        )
    }
    return loaded.index.streaming(source.slice(loaded.vectorsOffset, loaded.vectorsLength))
}
