package io.github.kromus

// Compact, zero-dependency binary persistence for the indexes. Building an HNSW graph is expensive;
// these let you build once and reload instantly (ship a prebuilt index, or cache it on device). The
// format is versioned by a leading tag byte and is stable across platforms (floats are stored by raw
// bits). Analyzers are functions and cannot be serialized, so text/hybrid loaders take the analyzer
// the index was built with — supply the same one for consistent query tokenization.

private const val VECTOR_FORMAT: Int = 2
private const val TEXT_FORMAT: Int = 1
private const val HYBRID_FORMAT: Int = 1

/** Serializes this vector index (graph + key mapping) to a byte array. */
public fun <K> VectorIndex<K>.encodeToByteArray(keyCodec: KeyCodec<K>): ByteArray {
    val g = graph()
    val store = g.store()
    val w = ByteWriter()
    w.byte(VECTOR_FORMAT)
    w.int(dimensions)
    w.byte(metric.ordinal)
    w.int(config.m)
    w.int(config.efConstruction)
    w.int(config.efSearch)
    w.long(config.seed)
    w.byte(config.quantization.ordinal)

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
            else -> error("unknown vector store")
        }
        w.byte(if (g.deletedAt(id)) 1 else 0)
        for (layer in 0..level) {
            val neighbors = g.neighborsAtLayer(id, layer)
            w.int(neighbors.size)
            for (e in neighbors) w.int(e)
        }
    }
    w.int(g.entryPointValue)
    w.int(g.topLayerValue)

    val live = liveEntries()
    w.int(live.size)
    for ((key, id) in live) {
        w.bytes(keyCodec.encode(key))
        w.int(id)
    }
    return w.toByteArray()
}

/** Reconstructs a vector index produced by [encodeToByteArray]. */
public fun <K> decodeVectorIndex(bytes: ByteArray, keyCodec: KeyCodec<K>): VectorIndex<K> {
    val r = ByteReader(bytes)
    require(r.byte() == VECTOR_FORMAT) { "unsupported vector index format" }
    val dimensions = r.int()
    val metric = Metric.entries[r.byte()]
    val config = HnswConfig(r.int(), r.int(), r.int(), r.long(), Quantization.entries[r.byte()])

    val store = Hnsw.newStore(dimensions, metric, config.quantization)
    val n = r.int()
    val levels = IntArray(n)
    val deleted = BooleanArray(n)
    val neighbors = ArrayList<Array<IntArray>>(n)
    for (id in 0 until n) {
        levels[id] = r.int()
        when (store) {
            is Float32VectorStore -> store.load(FloatArray(dimensions) { r.float() })
            is Int8VectorStore -> store.load(ByteArray(dimensions) { r.byte().toByte() }, r.float())
            else -> error("unknown vector store")
        }
        deleted[id] = r.byte() == 1
        neighbors.add(Array(levels[id] + 1) { IntArray(r.int()) { r.int() } })
    }
    val entryPoint = r.int()
    val topLayer = r.int()

    val liveCount = r.int()
    val live = HashMap<K, Int>(liveCount * 2)
    repeat(liveCount) {
        val key = keyCodec.decode(r.bytes())
        live[key] = r.int()
    }

    val hnsw = Hnsw.restore(metric, config, store, levels, neighbors, deleted, entryPoint, topLayer)
    return VectorIndex.fromState(dimensions, metric, config, hnsw, live, n)
}

/** Serializes this full-text index to a byte array. The analyzer is not stored (see [decodeTextIndex]). */
public fun <K> TextIndex<K>.encodeToByteArray(keyCodec: KeyCodec<K>): ByteArray {
    val w = ByteWriter()
    w.byte(TEXT_FORMAT)
    w.float(config.k1)
    w.float(config.b)

    val docs = snapshot()
    w.int(docs.size)
    for ((key, termFreqs, length) in docs) {
        w.bytes(keyCodec.encode(key))
        w.int(length)
        w.int(termFreqs.size)
        for ((term, freq) in termFreqs) {
            w.bytes(term.encodeToByteArray())
            w.int(freq)
        }
    }
    return w.toByteArray()
}

/**
 * Reconstructs a full-text index produced by [encodeToByteArray]. Pass the same [analyzer] the index
 * was built with so queries tokenize consistently with the stored terms.
 */
public fun <K> decodeTextIndex(
    bytes: ByteArray,
    keyCodec: KeyCodec<K>,
    analyzer: Analyzer = Analyzer.standard(),
): TextIndex<K> {
    val r = ByteReader(bytes)
    require(r.byte() == TEXT_FORMAT) { "unsupported text index format" }
    val config = Bm25Config(r.float(), r.float())
    val index = TextIndex<K>(analyzer, config)

    val docCount = r.int()
    repeat(docCount) {
        val key = keyCodec.decode(r.bytes())
        val length = r.int()
        val termCount = r.int()
        val termFreqs = HashMap<String, Int>(termCount * 2)
        repeat(termCount) {
            val term = r.bytes().decodeToString()
            termFreqs[term] = r.int()
        }
        index.loadDoc(key, termFreqs, length)
    }
    return index
}

/** Serializes this hybrid index (both modalities) to a byte array. */
public fun <K> HybridIndex<K>.encodeToByteArray(keyCodec: KeyCodec<K>): ByteArray {
    val w = ByteWriter()
    w.byte(HYBRID_FORMAT)
    w.int(rrfK)
    w.bytes(vectorPart().encodeToByteArray(keyCodec))
    w.bytes(textPart().encodeToByteArray(keyCodec))
    return w.toByteArray()
}

/**
 * Reconstructs a hybrid index produced by [encodeToByteArray]. Pass the same [analyzer] used to build
 * it (see [decodeTextIndex]).
 */
public fun <K> decodeHybridIndex(
    bytes: ByteArray,
    keyCodec: KeyCodec<K>,
    analyzer: Analyzer = Analyzer.standard(),
): HybridIndex<K> {
    val r = ByteReader(bytes)
    require(r.byte() == HYBRID_FORMAT) { "unsupported hybrid index format" }
    val rrfK = r.int()
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
