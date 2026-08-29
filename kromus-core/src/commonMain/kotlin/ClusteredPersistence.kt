package io.github.kromus

// Persistence for a clustered index.
//
// The layout is where this index type earns its keep. Centroids and the cluster table are small and
// go together at the front; the vectors follow, laid out cluster by cluster, so answering a query
// means reading a handful of contiguous runs rather than touching pages scattered across the file.
// That is the property a graph index cannot have — its traversal goes wherever the data says, and no
// arrangement of the file changes that.

private const val CLUSTERED_FORMAT: Int = 1

private const val S_CONFIG = "CNFG"
private const val S_CENTROIDS = "CNTR"
private const val S_CLUSTERS = "CLST"
private const val S_VECTORS = "VECT"
private const val S_ENTRIES = "ENTR"

/**
 * Serializes this clustered index.
 *
 * @param provenance what produced it — see the `expect` parameter of [decodeClusteredIndex].
 */
public fun <K> ClusteredIndex<K>.encodeToByteArray(
    keyCodec: KeyCodec<K>,
    provenance: String? = null,
): ByteArray {
    // Bound outside the section lambdas: inside them the receiver is a ByteWriter, and `size` there
    // means something else entirely.
    val entryCount = size
    val c = ContainerWriter(KIND_CLUSTERED, CLUSTERED_FORMAT, provenance)

    c.section(S_CONFIG) {
        int(dimensions)
        byte(metric.ordinal)
        int(config.clusters)
        int(config.nprobe)
        float(config.targetRecall)
        long(config.seed)
        int(config.iterations)
        byte(config.quantization.ordinal)
        int(entryCount)
        int(clusterCount)
        // The effective probe count and what it was measured to recover. Stored rather than
        // recomputed: measuring needs the corpus, and a loaded index is meant to skip that work.
        int(nprobe)
        float(estimatedRecall)
    }

    c.section(S_CENTROIDS) { for (x in centroids) float(x) }

    // clusterCount + 1 boundaries, so a cluster is the half-open range between neighbours.
    c.section(S_CLUSTERS) { for (start in clusterStarts) int(start) }

    c.section(S_VECTORS) {
        for (id in 0 until entryCount) {
            when (store) {
                is Float32VectorStore -> for (x in store.vectorAt(id)) float(x)
                is Int8VectorStore -> {
                    for (b in store.codeAt(id)) byte(b.toInt())
                    float(store.scaleAt(id))
                }
                is BinaryVectorStore -> for (word in store.codeAt(id)) long(word)
                else -> error("unknown vector store")
            }
        }
    }

    c.section(S_ENTRIES) {
        val pool = StringPoolWriter()
        val entries = ByteWriter()
        for (id in 0 until entryCount) {
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

    return c.toByteArray()
}

/**
 * Reconstructs a clustered index produced by [encodeToByteArray].
 *
 * @param expect refuse the index unless it recorded this provenance; see [decodeVectorIndex].
 * @throws KromusFormatException if [bytes] are not a clustered index this build can read.
 */
public fun <K> decodeClusteredIndex(
    bytes: ByteArray,
    keyCodec: KeyCodec<K>,
    expect: String? = null,
): ClusteredIndex<K> {
    val c = ContainerReader(bytes, KIND_CLUSTERED, CLUSTERED_FORMAT, expect)

    val cfg = c.section(S_CONFIG)
    val dimensions = cfg.int()
    if (dimensions < 1) throw KromusFormatException("corrupt kromus index: dimensions $dimensions")
    val metric = cfg.enumValue(Metric.entries, "metric")
    val config = ClusterConfig(
        clusters = cfg.int(),
        nprobe = cfg.int(),
        targetRecall = cfg.float(),
        seed = cfg.long(),
        iterations = cfg.int(),
        quantization = cfg.enumValue(Quantization.entries, "quantization"),
    )
    val n = cfg.int()
    val clusterCount = cfg.int()
    val nprobe = cfg.int()
    val estimatedRecall = cfg.float()
    if (nprobe < 1) throw KromusFormatException("corrupt kromus index: nprobe $nprobe")
    if (n < 0) throw KromusFormatException("corrupt kromus index: entry count $n")
    if (clusterCount < 0) throw KromusFormatException("corrupt kromus index: cluster count $clusterCount")

    // The table makes every one of these checkable before a single record is read.
    val stride = vectorBytes(dimensions, config.quantization)
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

    val store = Hnsw.newStore(dimensions, metric, config.quantization)
    val vectors = c.section(S_VECTORS)
    repeat(n) {
        when (store) {
            is Float32VectorStore -> store.load(FloatArray(dimensions) { vectors.float() })
            is Int8VectorStore -> store.load(ByteArray(dimensions) { vectors.byte().toByte() }, vectors.float())
            is BinaryVectorStore -> store.load(LongArray((dimensions + 63) ushr 6) { vectors.long() })
            else -> error("unknown vector store")
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

    return ClusteredIndex(
        dimensions,
        metric,
        config,
        centroids,
        clusterCount,
        starts,
        store,
        keyOf,
        attrsOf,
        nprobe,
        estimatedRecall,
    )
}
