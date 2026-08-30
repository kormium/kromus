package io.github.kromus

// Compact, zero-dependency binary persistence. Building an HNSW graph is expensive; these let you
// build once and reload instantly — ship a prebuilt index, or cache one on device.
//
// Every index is a container of named sections (see Container.kt) rather than one interleaved stream.
// That buys three things at once. Each section is homogeneous, so its fields are sized for what they
// hold instead of for the widest neighbour — which is most of the density here. The section table says
// what a file is made of without parsing it, and each section is checksummed, so corruption is located
// rather than merely noticed. And a section is a contiguous range, so the graph can be read without
// touching the vectors, and the vector section — one fixed stride per entry — is addressed by
// arithmetic instead of walked.
//
// Bytes are stable across platforms three times over: floats are stored by raw bits; records are
// written in a fixed order (nodes by id, documents by insertion order); and within a record the
// string-keyed maps are sorted by key, so identical content encodes identically whatever Map it
// arrived in and however it was built. That is what makes an index safe to content-hash.
//
// Analyzers and embedding models are not in the bytes and cannot be — see the provenance guard.

private const val VECTOR_FORMAT: Int = 7
private const val TEXT_FORMAT: Int = 6
internal const val HYBRID_FORMAT: Int = 5

// Section tags, four ASCII characters so a hex dump of a file is readable by eye.
private const val S_CONFIG = "CNFG"
private const val S_LEVELS = "LVLS"
private const val S_DELETED = "DELT"
private const val S_ADJACENCY = "ADJC"
private const val S_VECTORS = "VECT"
private const val S_ENTRIES = "ENTR"
private const val S_DOCS = "DOCS"
private const val S_VECTOR_PART = "VIDX"
private const val S_TEXT_PART = "TIDX"

/**
 * Bytes one neighbour link occupies.
 *
 * Unsigned, so two bytes address 65 536 nodes rather than the 32 768 a signed short would — which is
 * the difference between two and four bytes per link for most indexes anyone ships, and links are the
 * whole of the adjacency section.
 */
private fun linkWidth(nodeCount: Int): Int = if (nodeCount <= 0x10000) 2 else 4

private fun ByteWriter.link(value: Int, width: Int) {
    if (width == 2) short(value) else int(value)
}

private fun ByteReader.link(width: Int): Int = if (width == 2) short() else int()

/**
 * Serializes this vector index.
 *
 * This also checkpoints the index: [VectorIndex.dirtyNodes] drops to zero and later [encodeDelta]
 * calls chain onto *these* bytes, so keep what you get back — a delta written against a snapshot you
 * discarded has nothing to be applied to.
 *
 * @param provenance what produced this index — the embedding model, its revision, the corpus date.
 *   Opaque to kromus; see the `expect` parameter of [decodeVectorIndex].
 */
public fun <K> VectorIndex<K>.encodeToByteArray(
    keyCodec: KeyCodec<K>,
    provenance: String? = null,
): ByteArray {
    val g = graph()
    val store = g.store()
    val n = g.capacity
    val width = linkWidth(n)
    val c = ContainerWriter(KIND_VECTOR, VECTOR_FORMAT, provenance)

    c.section(S_CONFIG) {
        int(dimensions)
        byte(metric.ordinal)
        int(config.m)
        int(config.efConstruction)
        int(config.efSearch)
        long(config.seed)
        byte(config.quantization.ordinal)
        int(config.maxVisited)
        int(n)
        // -1 when the index is empty; the one field here that is meaningfully signed.
        int(g.entryPointValue)
        short(g.topLayerValue)
        byte(width)
    }

    // A level fits in two bytes with room to spare: the tallest a node can be is bounded by the
    // level distribution, and even the pathological tail of a very small `m` stays in four figures.
    c.section(S_LEVELS) { for (id in 0 until n) short(g.levelAt(id)) }

    // One bit per node instead of one byte: eight times smaller, and tombstones are common enough on
    // a long-lived index for that to be worth a shift.
    c.section(S_DELETED) {
        var acc = 0
        for (id in 0 until n) {
            if (g.deletedAt(id)) acc = acc or (1 shl (id and 7))
            if (id and 7 == 7) {
                byte(acc)
                acc = 0
            }
        }
        if (n and 7 != 0) byte(acc)
    }

    c.section(S_ADJACENCY) {
        for (id in 0 until n) {
            for (layer in 0..g.levelAt(id)) {
                val links = g.neighborsAtLayer(id, layer)
                short(links.size)
                for (i in 0 until links.size) link(links[i], width)
            }
        }
    }

    // One stride per entry, so a reader can address node id's vector arithmetically.
    c.section(S_VECTORS) {
        for (id in 0 until n) {
            store.writeVector(id, this)
        }
    }

    c.section(S_ENTRIES) {
        // Records go into their own writer first: the pool has to precede them in the section but is
        // only complete once every record has been walked.
        val pool = StringPoolWriter()
        val entries = ByteWriter()
        var liveCount = 0
        for (id in 0 until n) {
            val key = keyAt(id) ?: continue
            liveCount++
            entries.bytes(keyCodec.encode(key))
            entries.int(id)
            val attrs = attributesAt(id)
            entries.short(attrs.size)
            for ((ak, av) in attrs.canonical()) {
                entries.int(pool.idOf(ak))
                entries.int(pool.idOf(av))
            }
        }
        pool.writeTo(this)
        int(liveCount)
        raw(entries.toByteArray())
    }

    val bytes = c.toByteArray()
    checkpoint(checksumOf(bytes))
    return bytes
}

/**
 * Reconstructs a vector index produced by [encodeToByteArray].
 *
 * @param expect refuse the index unless it recorded this provenance. An index is only meaningful
 *   together with the model that produced its vectors; pairing it with another does not fail, it
 *   quietly returns wrong results, which is why this is worth stating.
 * @param store the quantizer the index was built with, if it was one of your own. Nothing in the
 *   bytes names it, so reading with the wrong one is not detected — it decodes and returns nonsense.
 * @throws KromusFormatException if [bytes] are not a vector index this build can read.
 */
public fun <K> decodeVectorIndex(
    bytes: ByteArray,
    keyCodec: KeyCodec<K>,
    expect: String? = null,
    store: VectorStoreFactory? = null,
): VectorIndex<K> {
    val c = ContainerReader(bytes, KIND_VECTOR, VECTOR_FORMAT, expect)

    val cfg = c.section(S_CONFIG)
    val dimensions = cfg.int()
    if (dimensions < 1) throw KromusFormatException("corrupt kromus index: dimensions $dimensions")
    val metric = cfg.enumValue(Metric.entries, "metric")
    val config = HnswConfig(
        m = cfg.int(),
        efConstruction = cfg.int(),
        efSearch = cfg.int(),
        seed = cfg.long(),
        quantization = cfg.enumValue(Quantization.entries, "quantization"),
        maxVisited = cfg.int(),
    )
    val n = cfg.int()
    val entryPoint = cfg.int()
    val topLayer = cfg.short()
    val width = cfg.byte()
    if (width != 2 && width != 4) throw KromusFormatException("corrupt kromus index: link width $width")
    if (n < 0) throw KromusFormatException("corrupt kromus index: node count $n")
    if (entryPoint < -1 || entryPoint >= n) {
        throw KromusFormatException("corrupt kromus index: entry point $entryPoint outside 0..${n - 1}")
    }

    // Every id-indexed section has to describe the same node count, which the table makes checkable
    // before a single record is read.
    // A custom store defines its own stride, so the store is built before the length is checked
    // against it — the check is still worth making, it just cannot assume the built-in layout.
    val vectorStore = store?.create(dimensions, metric) ?: Hnsw.newStore(dimensions, metric, config.quantization)
    val stride = vectorStore.strideBytes
    if (c.lengthOf(S_VECTORS).toLong() != n.toLong() * stride) {
        throw KromusFormatException(
            "corrupt kromus index: the vector section is ${c.lengthOf(S_VECTORS)} byte(s), " +
                "but $n node(s) at $stride byte(s) each need ${n.toLong() * stride}",
        )
    }
    if (c.lengthOf(S_LEVELS) != n * 2) {
        throw KromusFormatException(
            "corrupt kromus index: the level section is ${c.lengthOf(S_LEVELS)} byte(s) for $n node(s)",
        )
    }

    val levelReader = c.section(S_LEVELS)
    val levels = IntArray(n) { levelReader.short() }

    val deletedReader = c.section(S_DELETED)
    val deleted = BooleanArray(n)
    var packed = 0
    for (id in 0 until n) {
        if (id and 7 == 0) packed = deletedReader.byte()
        deleted[id] = (packed ushr (id and 7)) and 1 == 1
    }

    val adjacency = c.section(S_ADJACENCY)
    val neighbors = ArrayList<Array<IntArray>>(n)
    for (id in 0 until n) {
        neighbors.add(
            Array(levels[id] + 1) {
                val links = IntArray(adjacency.shortCount(width, "neighbour")) { adjacency.link(width) }
                for (link in links) {
                    if (link < 0 || link >= n) {
                        throw KromusFormatException("corrupt kromus index: neighbour $link outside 0..${n - 1}")
                    }
                }
                links
            },
        )
    }

    val vectors = c.section(S_VECTORS)
    repeat(n) {
        vectorStore.readVector(vectors)
    }

    val entries = c.section(S_ENTRIES)
    val pool = StringPoolReader(entries)
    val liveCount = entries.count(9, "entry")
    val live = HashMap<K, Int>(liveCount * 2)
    val liveAttrs = HashMap<Int, Map<String, String>>()
    repeat(liveCount) {
        val key = keyCodec.decode(entries.bytes())
        val id = entries.int()
        if (id < 0 || id >= n) {
            throw KromusFormatException("corrupt kromus index: entry id $id outside 0..${n - 1}")
        }
        live[key] = id
        val attrCount = entries.shortCount(8, "attribute")
        if (attrCount > 0) {
            val attrs = HashMap<String, String>(attrCount * 2)
            repeat(attrCount) {
                val ak = pool.get(entries.int())
                attrs[ak] = pool.get(entries.int())
            }
            liveAttrs[id] = attrs
        }
    }

    val hnsw = Hnsw.restore(metric, config, vectorStore, levels, neighbors, deleted, entryPoint, topLayer)
    val index = VectorIndex.fromState(dimensions, metric, config, hnsw, live, liveAttrs, n, store)
    // A freshly decoded index is a checkpoint of exactly these bytes, so deltas can chain onto it.
    index.checkpoint(checksumOf(bytes))
    return index
}

/** Bytes one stored vector occupies, in the layout the encoder writes. */
internal fun vectorBytes(dimensions: Int, quantization: Quantization): Int =
    when (quantization) {
        Quantization.None -> 4 * dimensions
        Quantization.Int8 -> dimensions + 4
        Quantization.Binary -> 8 * ((dimensions + 63) ushr 6)
    }

/**
 * A string-keyed map's entries in key order — the canonical layout every encoder writes.
 *
 * String comparison is by UTF-16 code unit, the same relation on every target, so this orders
 * identically everywhere.
 */
internal fun <V> Map<String, V>.canonical(): List<Map.Entry<String, V>> = entries.sortedBy { it.key }

/**
 * Reads the provenance recorded in an index without decoding it, or null if none was recorded.
 *
 * For telling which index you have — the corpus it was built from, the model it was embedded with —
 * before deciding whether to fetch a newer one.
 *
 * @throws KromusFormatException if [bytes] are not a kromus index this build can read.
 */
public fun provenanceOf(bytes: ByteArray): String? {
    if (bytes.size < 6) throw KromusFormatException("not a kromus index: only ${bytes.size} byte(s)")
    val kind = bytes[4].toInt() and 0xFF
    val version = when (kind) {
        KIND_VECTOR -> VECTOR_FORMAT
        KIND_TEXT -> TEXT_FORMAT
        KIND_HYBRID -> HYBRID_FORMAT
        else -> throw KromusFormatException("not a readable kromus index: unknown kind $kind")
    }
    return ContainerReader(bytes, kind, version, expect = null).provenance
}

/**
 * Serializes this full-text index. The analyzer is not stored (see [decodeTextIndex]).
 *
 * Like the vector encoder this checkpoints the index, so later [encodeDelta] calls chain onto these
 * bytes — keep what you get back.
 */
public fun <K> TextIndex<K>.encodeToByteArray(
    keyCodec: KeyCodec<K>,
    provenance: String? = null,
): ByteArray {
    val docs = snapshot()
    val c = ContainerWriter(KIND_TEXT, TEXT_FORMAT, provenance)

    c.section(S_CONFIG) {
        float(config.k1)
        float(config.b)
        int(docs.size)
    }

    c.section(S_DOCS) {
        // Terms and attribute strings are pooled: a term is repeated in every document that contains
        // it, which is otherwise the bulk of the section.
        val pool = StringPoolWriter()
        val entries = ByteWriter()
        for (doc in docs) {
            entries.bytes(keyCodec.encode(doc.key))
            entries.int(doc.length)
            entries.int(doc.termFreqs.size)
            for ((term, freq) in doc.termFreqs.canonical()) {
                entries.int(pool.idOf(term))
                entries.int(freq)
            }
            entries.short(doc.attributes.size)
            for ((ak, av) in doc.attributes.canonical()) {
                entries.int(pool.idOf(ak))
                entries.int(pool.idOf(av))
            }
        }
        pool.writeTo(this)
        raw(entries.toByteArray())
    }

    val bytes = c.toByteArray()
    checkpoint(checksumOf(bytes))
    return bytes
}

/**
 * Reconstructs a full-text index. Pass the same [analyzer] the index was built with so queries
 * tokenize consistently with the stored terms.
 *
 * @throws KromusFormatException if [bytes] are not a text index this build can read.
 */
public fun <K> decodeTextIndex(
    bytes: ByteArray,
    keyCodec: KeyCodec<K>,
    analyzer: Analyzer = Analyzer.standard(),
    expect: String? = null,
): TextIndex<K> {
    val c = ContainerReader(bytes, KIND_TEXT, TEXT_FORMAT, expect)

    val cfg = c.section(S_CONFIG)
    val config = Bm25Config(cfg.float(), cfg.float())
    val docCount = cfg.int()
    if (docCount < 0) throw KromusFormatException("corrupt kromus index: document count $docCount")
    val index = TextIndex<K>(analyzer, config)

    val docs = c.section(S_DOCS)
    val pool = StringPoolReader(docs)
    repeat(docCount) {
        val key = keyCodec.decode(docs.bytes())
        val length = docs.int()
        if (length < 0) throw KromusFormatException("corrupt kromus index: negative document length $length")
        val termCount = docs.count(8, "term")
        val termFreqs = HashMap<String, Int>(termCount * 2)
        repeat(termCount) {
            val term = pool.get(docs.int())
            termFreqs[term] = docs.int()
        }
        val attrCount = docs.shortCount(8, "attribute")
        val attributes = HashMap<String, String>(attrCount * 2)
        repeat(attrCount) {
            val ak = pool.get(docs.int())
            attributes[ak] = pool.get(docs.int())
        }
        index.loadDoc(key, termFreqs, length, attributes)
    }
    index.checkpoint(checksumOf(bytes))
    return index
}

/** Serializes this hybrid index: a container holding one of each half. */
public fun <K> HybridIndex<K>.encodeToByteArray(
    keyCodec: KeyCodec<K>,
    provenance: String? = null,
): ByteArray {
    val c = ContainerWriter(KIND_HYBRID, HYBRID_FORMAT, provenance)
    // Recorded once, at the level the caller loads; the halves carry none of their own.
    c.section(S_CONFIG) { int(rrfK) }
    c.section(S_VECTOR_PART) { raw(vectorPart().encodeToByteArray(keyCodec)) }
    c.section(S_TEXT_PART) { raw(textPart().encodeToByteArray(keyCodec)) }
    return c.toByteArray()
}

/**
 * Reconstructs a hybrid index. Pass the same [analyzer] used to build it (see [decodeTextIndex]).
 *
 * @throws KromusFormatException if [bytes] are not a hybrid index this build can read.
 */
public fun <K> decodeHybridIndex(
    bytes: ByteArray,
    keyCodec: KeyCodec<K>,
    analyzer: Analyzer = Analyzer.standard(),
    expect: String? = null,
): HybridIndex<K> {
    val c = ContainerReader(bytes, KIND_HYBRID, HYBRID_FORMAT, expect)
    val rrfK = c.section(S_CONFIG).int()
    if (rrfK < 1) throw KromusFormatException("corrupt kromus index: rrfK $rrfK")
    val vectorIndex = decodeVectorIndex(c.sectionBytes(S_VECTOR_PART), keyCodec)
    val textIndex = decodeTextIndex(c.sectionBytes(S_TEXT_PART), keyCodec, analyzer)
    return HybridIndex.fromParts(
        vectorIndex.dimensions,
        vectorIndex.metric,
        vectorIndex.config,
        analyzer,
        textIndex.config,
        rrfK,
        vectorIndex,
        textIndex,
    )
}
