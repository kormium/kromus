package io.github.kromus

// Compact, zero-dependency binary persistence for the indexes. Building an HNSW graph is expensive;
// these let you build once and reload instantly (ship a prebuilt index, or cache it on device).
//
// Every blob starts with a "KRMS" magic, a kind byte and a format version (see ByteBuffers.kt), and
// every read is bounds-checked, so stale or corrupt bytes surface as a KromusFormatException the
// caller can catch and rebuild from — not as a crash.
//
// The bytes are stable across platforms twice over: floats are stored by raw bits, and records are
// written in a fixed order (vector entries by internal id, documents by insertion order) rather than
// in hash-map iteration order, so encoding the same index content anywhere yields the same bytes.
// That makes an index safe to content-hash, cache by digest and compare in tests.
//
// Analyzers are functions and cannot be serialized, so text/hybrid loaders take the analyzer the
// index was built with — supply the same one for consistent query tokenization.

private const val VECTOR_FORMAT: Int = 4
private const val TEXT_FORMAT: Int = 3
private const val HYBRID_FORMAT: Int = 2

/** Serializes this vector index (graph + key mapping) to a byte array. */
public fun <K> VectorIndex<K>.encodeToByteArray(keyCodec: KeyCodec<K>): ByteArray {
    val g = graph()
    val store = g.store()
    val w = ByteWriter()
    w.header(KIND_VECTOR, VECTOR_FORMAT)
    w.int(dimensions)
    w.byte(metric.ordinal)
    w.int(config.m)
    w.int(config.efConstruction)
    w.int(config.efSearch)
    w.long(config.seed)
    w.byte(config.quantization.ordinal)
    w.int(config.maxVisited)

    val n = g.capacity
    w.int(n)
    for (id in 0 until n) {
        val level = g.levelAt(id)
        w.int(level)
        when (store) {
            is Float32VectorStore -> for (x in store.vectorAt(id)) w.float(x)
            is Int8VectorStore -> {
                for (b in store.codeAt(id)) w.byte(b.toInt())
                w.float(store.scaleAt(id))
            }
            is BinaryVectorStore -> for (word in store.codeAt(id)) w.long(word)
            else -> error("unknown vector store")
        }
        w.byte(if (g.deletedAt(id)) 1 else 0)
        for (layer in 0..level) {
            val neighbors = g.neighborsAtLayer(id, layer)
            w.int(neighbors.size)
            for (i in 0 until neighbors.size) w.int(neighbors[i])
        }
    }
    w.int(g.entryPointValue)
    w.int(g.topLayerValue)

    // Live entries in ascending id order, with attribute strings pooled. The records go into their
    // own writer first because the pool has to precede them in the file but is only complete once
    // every record has been walked.
    val pool = StringPoolWriter()
    val entries = ByteWriter()
    var liveCount = 0
    for (id in 0 until n) {
        val key = keyAt(id) ?: continue
        liveCount++
        entries.bytes(keyCodec.encode(key))
        entries.int(id)
        val attrs = attributesAt(id)
        entries.int(attrs.size)
        for ((ak, av) in attrs) {
            entries.int(pool.idOf(ak))
            entries.int(pool.idOf(av))
        }
    }
    pool.writeTo(w)
    w.int(liveCount)
    w.raw(entries.toByteArray())
    return w.toByteArray()
}

/**
 * Reconstructs a vector index produced by [encodeToByteArray].
 *
 * @throws KromusFormatException if [bytes] are not a vector index this build can read.
 */
public fun <K> decodeVectorIndex(bytes: ByteArray, keyCodec: KeyCodec<K>): VectorIndex<K> {
    val r = ByteReader(bytes)
    r.header(KIND_VECTOR, VECTOR_FORMAT)
    val dimensions = r.int()
    if (dimensions < 1) throw KromusFormatException("corrupt kromus index: dimensions $dimensions")
    val metric = r.enumValue(Metric.entries, "metric")
    val config = HnswConfig(
        m = r.int(),
        efConstruction = r.int(),
        efSearch = r.int(),
        seed = r.long(),
        quantization = r.enumValue(Quantization.entries, "quantization"),
        maxVisited = r.int(),
    )

    val store = Hnsw.newStore(dimensions, metric, config.quantization)
    // Each node costs at least a level, a deleted flag, one neighbour count and its vector payload.
    val n = r.count(bytesPerNode(dimensions, config.quantization), "node")
    val levels = IntArray(n)
    val deleted = BooleanArray(n)
    val neighbors = ArrayList<Array<IntArray>>(n)
    for (id in 0 until n) {
        val level = r.int()
        if (level < 0) throw KromusFormatException("corrupt kromus index: negative level $level for node $id")
        levels[id] = level
        when (store) {
            is Float32VectorStore -> store.load(FloatArray(dimensions) { r.float() })
            is Int8VectorStore -> store.load(ByteArray(dimensions) { r.byte().toByte() }, r.float())
            is BinaryVectorStore -> store.load(LongArray((dimensions + 63) ushr 6) { r.long() })
            else -> error("unknown vector store")
        }
        deleted[id] = r.byte() == 1
        neighbors.add(
            Array(level + 1) {
                val links = IntArray(r.count(4, "neighbour")) { r.int() }
                for (link in links) {
                    if (link < 0 || link >= n) {
                        throw KromusFormatException("corrupt kromus index: neighbour $link outside 0..${n - 1}")
                    }
                }
                links
            },
        )
    }
    val entryPoint = r.int()
    if (entryPoint < -1 || entryPoint >= n) {
        throw KromusFormatException("corrupt kromus index: entry point $entryPoint outside 0..${n - 1}")
    }
    val topLayer = r.int()

    val pool = StringPoolReader(r)
    val liveCount = r.count(8, "entry")
    val live = HashMap<K, Int>(liveCount * 2)
    val liveAttrs = HashMap<Int, Map<String, String>>()
    repeat(liveCount) {
        val key = keyCodec.decode(r.bytes())
        val id = r.int()
        if (id < 0 || id >= n) {
            throw KromusFormatException("corrupt kromus index: entry id $id outside 0..${n - 1}")
        }
        live[key] = id
        val attrCount = r.count(8, "attribute")
        if (attrCount > 0) {
            val attrs = HashMap<String, String>(attrCount * 2)
            repeat(attrCount) {
                val ak = pool.get(r.int())
                attrs[ak] = pool.get(r.int())
            }
            liveAttrs[id] = attrs
        }
    }

    val hnsw = Hnsw.restore(metric, config, store, levels, neighbors, deleted, entryPoint, topLayer)
    return VectorIndex.fromState(dimensions, metric, config, hnsw, live, liveAttrs, n)
}

/** Lower bound on the bytes one persisted node occupies, used to reject absurd node counts. */
private fun bytesPerNode(dimensions: Int, quantization: Quantization): Int {
    val vector = when (quantization) {
        Quantization.None -> 4 * dimensions
        Quantization.Int8 -> dimensions + 4
        Quantization.Binary -> 8 * ((dimensions + 63) ushr 6)
    }
    // level + deleted flag + the layer-0 neighbour count.
    return vector + 4 + 1 + 4
}

/** Serializes this full-text index to a byte array. The analyzer is not stored (see [decodeTextIndex]). */
public fun <K> TextIndex<K>.encodeToByteArray(keyCodec: KeyCodec<K>): ByteArray {
    val w = ByteWriter()
    w.header(KIND_TEXT, TEXT_FORMAT)
    w.float(config.k1)
    w.float(config.b)

    // Terms and attribute strings are pooled: a term is repeated in every document that contains it,
    // which is otherwise the bulk of the file.
    val pool = StringPoolWriter()
    val entries = ByteWriter()
    val docs = snapshot()
    for (doc in docs) {
        entries.bytes(keyCodec.encode(doc.key))
        entries.int(doc.length)
        entries.int(doc.termFreqs.size)
        for ((term, freq) in doc.termFreqs) {
            entries.int(pool.idOf(term))
            entries.int(freq)
        }
        entries.int(doc.attributes.size)
        for ((ak, av) in doc.attributes) {
            entries.int(pool.idOf(ak))
            entries.int(pool.idOf(av))
        }
    }
    pool.writeTo(w)
    w.int(docs.size)
    w.raw(entries.toByteArray())
    return w.toByteArray()
}

/**
 * Reconstructs a full-text index produced by [encodeToByteArray]. Pass the same [analyzer] the index
 * was built with so queries tokenize consistently with the stored terms.
 *
 * @throws KromusFormatException if [bytes] are not a text index this build can read.
 */
public fun <K> decodeTextIndex(
    bytes: ByteArray,
    keyCodec: KeyCodec<K>,
    analyzer: Analyzer = Analyzer.standard(),
): TextIndex<K> {
    val r = ByteReader(bytes)
    r.header(KIND_TEXT, TEXT_FORMAT)
    val config = Bm25Config(r.float(), r.float())
    val index = TextIndex<K>(analyzer, config)

    val pool = StringPoolReader(r)
    val docCount = r.count(12, "document")
    repeat(docCount) {
        val key = keyCodec.decode(r.bytes())
        val length = r.int()
        if (length < 0) throw KromusFormatException("corrupt kromus index: negative document length $length")
        val termCount = r.count(8, "term")
        val termFreqs = HashMap<String, Int>(termCount * 2)
        repeat(termCount) {
            val term = pool.get(r.int())
            termFreqs[term] = r.int()
        }
        val attrCount = r.count(8, "attribute")
        val attributes = HashMap<String, String>(attrCount * 2)
        repeat(attrCount) {
            val ak = pool.get(r.int())
            attributes[ak] = pool.get(r.int())
        }
        index.loadDoc(key, termFreqs, length, attributes)
    }
    return index
}

/** Serializes this hybrid index (both modalities) to a byte array. */
public fun <K> HybridIndex<K>.encodeToByteArray(keyCodec: KeyCodec<K>): ByteArray {
    val w = ByteWriter()
    w.header(KIND_HYBRID, HYBRID_FORMAT)
    w.int(rrfK)
    w.bytes(vectorPart().encodeToByteArray(keyCodec))
    w.bytes(textPart().encodeToByteArray(keyCodec))
    return w.toByteArray()
}

/**
 * Reconstructs a hybrid index produced by [encodeToByteArray]. Pass the same [analyzer] used to build
 * it (see [decodeTextIndex]).
 *
 * @throws KromusFormatException if [bytes] are not a hybrid index this build can read.
 */
public fun <K> decodeHybridIndex(
    bytes: ByteArray,
    keyCodec: KeyCodec<K>,
    analyzer: Analyzer = Analyzer.standard(),
): HybridIndex<K> {
    val r = ByteReader(bytes)
    r.header(KIND_HYBRID, HYBRID_FORMAT)
    val rrfK = r.int()
    if (rrfK < 1) throw KromusFormatException("corrupt kromus index: rrfK $rrfK")
    val vectorIndex = decodeVectorIndex<K>(r.bytes(), keyCodec)
    val textIndex = decodeTextIndex<K>(r.bytes(), keyCodec, analyzer)
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
