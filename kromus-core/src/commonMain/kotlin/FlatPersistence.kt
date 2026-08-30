package io.github.kromus

// Persistence for the exhaustive index. The simplest layout in the library: no graph, no partitioning,
// so the sections are the config, the vectors at one fixed stride, and the entries.

private const val FLAT_FORMAT: Int = 1

private const val S_CONFIG = "CNFG"
private const val S_VECTORS = "VECT"
private const val S_ENTRIES = "ENTR"

/** Serializes this exhaustive index. */
public fun <K> FlatIndex<K>.encodeToByteArray(
    keyCodec: KeyCodec<K>,
    provenance: String? = null,
): ByteArray {
    val entryCount = size
    val c = ContainerWriter(KIND_FLAT, FLAT_FORMAT, provenance)

    c.section(S_CONFIG) {
        int(dimensions)
        byte(metric.ordinal)
        byte(quantization.ordinal)
        int(entryCount)
    }

    c.section(S_VECTORS) {
        for (id in 0 until entryCount) {
            store.writeVector(id, this)
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
 * Reconstructs an exhaustive index produced by [encodeToByteArray].
 *
 * @throws KromusFormatException if [bytes] are not a flat index this build can read.
 */
public fun <K> decodeFlatIndex(
    bytes: ByteArray,
    keyCodec: KeyCodec<K>,
    expect: String? = null,
    store: VectorStoreFactory? = null,
): FlatIndex<K> = readFlatIndex(bytes, keyCodec, expect, skipVectors = false, factory = store).index

/**
 * Loads an exhaustive index that reads its vectors from [vectors] instead of holding them, in batches
 * rather than whole — a flat index has no partitions to bound a read by.
 *
 * Pass `null` to read from [bytes] itself; see [viewIvfIndex] for why that form saves nothing.
 *
 * There is no custom-store parameter here, unlike [decodeFlatIndex]: a streamed scan reads the
 * stored bytes directly and only the built-in layouts are known to it, so a store of your own has
 * to be decoded rather than streamed.
 */
public fun <K> viewFlatIndex(
    bytes: ByteArray,
    keyCodec: KeyCodec<K>,
    vectors: VectorBlocks? = null,
    expect: String? = null,
): FlatIndex<K> {
    val loaded = readFlatIndex(bytes, keyCodec, expect, skipVectors = true, factory = null)
    if (!BlockDistance.supports(loaded.index.quantization)) {
        throw KromusFormatException(
            "a ${loaded.index.quantization} flat index cannot be streamed: its query path is built " +
                "per query, and its vectors are small enough that there is nothing to reclaim",
        )
    }
    val blocks = vectors ?: ResidentBlocks(bytes, loaded.vectorsOffset, loaded.vectorsLength)
    return loaded.index.streaming(blocks)
}

private class LoadedFlatIndex<K>(
    val index: FlatIndex<K>,
    val vectorsOffset: Int,
    val vectorsLength: Int,
)

private fun <K> readFlatIndex(
    bytes: ByteArray,
    keyCodec: KeyCodec<K>,
    expect: String?,
    skipVectors: Boolean,
    factory: VectorStoreFactory?,
): LoadedFlatIndex<K> {
    val c = ContainerReader(bytes, KIND_FLAT, FLAT_FORMAT, expect)

    val cfg = c.section(S_CONFIG)
    val dimensions = cfg.int()
    if (dimensions < 1) throw KromusFormatException("corrupt kromus index: dimensions $dimensions")
    val metric = cfg.enumValue(Metric.entries, "metric")
    val quantization = cfg.enumValue(Quantization.entries, "quantization")
    val n = cfg.int()
    if (n < 0) throw KromusFormatException("corrupt kromus index: entry count $n")

    // The store decides its own stride, so a custom quantizer is measured by what it writes rather
    // than by a table of the built-in ones.
    val store = factory?.create(dimensions, metric) ?: Hnsw.newStore(dimensions, metric, quantization)
    val stride = store.strideBytes
    if (c.lengthOf(S_VECTORS).toLong() != n.toLong() * stride) {
        throw KromusFormatException(
            "corrupt kromus index: the vector section is ${c.lengthOf(S_VECTORS)} byte(s), " +
                "but $n entr(ies) at $stride byte(s) each need ${n.toLong() * stride}",
        )
    }

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

    return LoadedFlatIndex(
        FlatIndex(dimensions, metric, quantization, store, keyOf, attrsOf, blocks = null),
        c.offsetOf(S_VECTORS),
        c.lengthOf(S_VECTORS),
    )
}
