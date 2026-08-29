package io.github.kromus

// Incremental persistence: write what changed instead of the whole index.
//
// `encodeToByteArray` always serializes everything, which is right for "build once, ship it" and
// wrong for an index a sync keeps in step with changing data — a 50 000-vector index is ~30 MiB, and
// paying that after every batch is a write-amplification problem on device, not just a slow one.
//
// A delta cannot simply append the new nodes. Inserting into an HNSW graph is symmetric: the new node
// links to its neighbours and *they* link back, and an over-full neighbour is pruned right after, so
// one insert rewrites the adjacency of tens of existing nodes scattered across the id space. What
// makes a delta small is a different observation: a vector is immutable once stored, because ids are
// never reused (re-adding a key allocates a new node and tombstones the old one). So a changed
// existing node owes only its deleted flag and its adjacency — tens of bytes — while the vector, which
// is the bulk of a node, is never rewritten.
//
// Everything is expressed over internal ids, which is what lets one mechanism carry additions,
// removals, replacements and metadata edits alike. Ids are written ascending, so a delta's bytes
// depend on what changed and not on the order the edits arrived — the same determinism the snapshot
// format has.
//
// A delta names the revision it applies to and decoding refuses a chain that does not line up, so a
// delta can never be replayed onto a snapshot it was not derived from.

private const val VECTOR_DELTA_FORMAT: Int = 1
private const val TEXT_DELTA_FORMAT: Int = 1
private const val HYBRID_DELTA_FORMAT: Int = 1

/**
 * Identity of a persisted blob: an FNV-1a 64 hash of its bytes.
 *
 * A counter would not do. Two indexes that diverged after a shared ancestor reach the same counter
 * with different content, and replaying one's delta onto the other's snapshot would corrupt it
 * silently. Hashing the bytes makes the check depend on the actual history. FNV-1a is integer
 * arithmetic only — no dependency, identical on every target.
 */
internal fun revisionOf(bytes: ByteArray): Long = checksumOf(bytes)

private fun ByteReader.expectParent(actual: Long, what: String) {
    val declared = long()
    if (declared != actual) {
        throw KromusFormatException(
            "kromus $what delta does not apply here: it was written against revision $declared, " +
                "but the index it is being applied to is at revision $actual — replay the deltas in " +
                "order, starting from the snapshot they were derived from",
        )
    }
}

// --- vector ---

/**
 * Encodes everything that changed since the last [encodeToByteArray] or [encodeDelta], or null when
 * nothing has. Typically a few tens of KiB where a full snapshot is tens of MiB.
 *
 * Reload with the [decodeVectorIndex] overload that takes deltas, passing them in the order they were
 * produced. Deltas accumulate, so fold them back into a snapshot periodically — decode the chain and
 * call [encodeToByteArray] on the result, which is also what [VectorIndex.compact] wants anyway.
 *
 * @throws IllegalStateException if [VectorIndex.needsFullSnapshot] — after [VectorIndex.compact] or
 *   [VectorIndex.clear] every id has moved, so no delta could describe the result, and after nothing
 *   has been encoded yet there is no snapshot to chain from.
 */
public fun <K> VectorIndex<K>.encodeDelta(keyCodec: KeyCodec<K>): ByteArray? {
    check(!needsFullSnapshot) {
        "this index needs a full encodeToByteArray: it has been compacted, cleared, or never encoded"
    }
    val g = graph()
    val changedNodes = g.dirtyIdsAscending()
    val changedEntries = dirtyEntriesAscending()
    if (changedNodes.isEmpty() && changedEntries.isEmpty()) return null

    val parent = revision
    val store = g.store()
    val capacity = g.capacity
    // Ids at or above the capacity recorded at the last checkpoint are new and carry their vector;
    // the rest existed already and owe only their mutable state.
    val firstNewId = baseCapacity
    val w = ByteWriter()
    w.deltaHeader(KIND_VECTOR_DELTA, VECTOR_DELTA_FORMAT)
    w.long(parent)
    w.int(capacity)
    w.int(g.entryPointValue)
    w.int(g.topLayerValue)

    w.int(firstNewId)

    var newCount = 0
    for (id in changedNodes) if (id >= firstNewId) newCount++
    w.int(newCount)
    for (id in changedNodes) {
        if (id < firstNewId) continue
        w.int(id)
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
            val links = g.neighborsAtLayer(id, layer)
            w.int(links.size)
            for (i in 0 until links.size) w.int(links[i])
        }
    }

    var changedCount = 0
    for (id in changedNodes) if (id < firstNewId) changedCount++
    w.int(changedCount)
    for (id in changedNodes) {
        if (id >= firstNewId) continue
        w.int(id)
        w.byte(if (g.deletedAt(id)) 1 else 0)
        val level = g.levelAt(id)
        w.int(level)
        for (layer in 0..level) {
            val links = g.neighborsAtLayer(id, layer)
            w.int(links.size)
            for (i in 0 until links.size) w.int(links[i])
        }
    }

    val pool = StringPoolWriter()
    val entries = ByteWriter()
    for (id in changedEntries) {
        entries.int(id)
        val key = keyAt(id)
        if (key == null) {
            entries.byte(0)
            continue
        }
        entries.byte(1)
        entries.bytes(keyCodec.encode(key))
        val attrs = attributesAt(id)
        entries.int(attrs.size)
        for ((ak, av) in attrs.canonical()) {
            entries.int(pool.idOf(ak))
            entries.int(pool.idOf(av))
        }
    }
    pool.writeTo(w)
    w.int(changedEntries.size)
    w.raw(entries.toByteArray())

    val bytes = w.toByteArray()
    checkpoint(revisionOf(bytes))
    return bytes
}

/**
 * Reconstructs a vector index from a snapshot plus the [deltas] recorded after it, applied in order.
 *
 * @throws KromusFormatException if the bytes are not readable, or if a delta does not chain onto what
 *   precedes it — a delta names the revision it was written against, so replaying them out of order,
 *   skipping one, or mixing in a delta from a different index is caught rather than silently applied.
 */
public fun <K> decodeVectorIndex(
    bytes: ByteArray,
    deltas: List<ByteArray>,
    keyCodec: KeyCodec<K>,
    expect: String? = null,
): VectorIndex<K> {
    val index = decodeVectorIndex(bytes, keyCodec, expect)
    var revision = revisionOf(bytes)
    index.checkpoint(revision)
    for (delta in deltas) {
        applyVectorDelta(index, delta, revision, keyCodec)
        revision = revisionOf(delta)
        index.checkpoint(revision)
    }
    return index
}

private fun <K> applyVectorDelta(
    index: VectorIndex<K>,
    delta: ByteArray,
    parent: Long,
    keyCodec: KeyCodec<K>,
) {
    val r = ByteReader(delta)
    r.deltaHeader(KIND_VECTOR_DELTA, VECTOR_DELTA_FORMAT)
    r.expectParent(parent, "vector")

    val g = index.graph()
    val store = g.store()
    val dimensions = index.dimensions
    val capacity = r.int()
    if (capacity < g.capacity) {
        throw KromusFormatException(
            "corrupt kromus delta: capacity $capacity is below the ${g.capacity} the index already has",
        )
    }
    val entryPoint = r.int()
    val topLayer = r.int()
    val firstNewId = r.int()
    if (firstNewId != g.capacity) {
        throw KromusFormatException(
            "kromus delta does not apply here: it starts new nodes at $firstNewId but the index has " +
                "${g.capacity} of them",
        )
    }

    val newCount = r.count(9, "new node")
    repeat(newCount) {
        val id = r.int()
        if (id != g.capacity) {
            throw KromusFormatException(
                "corrupt kromus delta: new node $id does not follow the ${g.capacity} already present",
            )
        }
        val level = r.level(id)
        when (store) {
            is Float32VectorStore -> store.load(FloatArray(dimensions) { r.float() })
            is Int8VectorStore -> store.load(ByteArray(dimensions) { r.byte().toByte() }, r.float())
            is BinaryVectorStore -> store.load(LongArray((dimensions + 63) ushr 6) { r.long() })
            else -> error("unknown vector store")
        }
        val deleted = r.byte() == 1
        g.appendRestoredNode(level, deleted, readLayers(r, level, capacity))
    }

    val changedCount = r.count(9, "changed node")
    repeat(changedCount) {
        val id = r.int()
        if (id < 0 || id >= firstNewId) {
            throw KromusFormatException("corrupt kromus delta: changed node $id outside 0..${firstNewId - 1}")
        }
        val deleted = r.byte() == 1
        val level = r.int()
        if (level != g.levelAt(id)) {
            throw KromusFormatException(
                "corrupt kromus delta: node $id has level $level but the index has ${g.levelAt(id)}",
            )
        }
        g.replaceNodeState(id, deleted, readLayers(r, level, capacity))
    }

    if (entryPoint < -1 || entryPoint >= capacity) {
        throw KromusFormatException("corrupt kromus delta: entry point $entryPoint outside 0..${capacity - 1}")
    }
    g.setEntry(entryPoint, topLayer)

    val pool = StringPoolReader(r)
    val entryCount = r.count(5, "entry")
    repeat(entryCount) {
        val id = r.int()
        if (id < 0 || id >= capacity) {
            throw KromusFormatException("corrupt kromus delta: entry id $id outside 0..${capacity - 1}")
        }
        if (r.byte() == 0) {
            index.applyEntry(id, null, emptyMap())
        } else {
            val key = keyCodec.decode(r.bytes())
            val attrCount = r.count(8, "attribute")
            val attrs = if (attrCount == 0) emptyMap() else HashMap<String, String>(attrCount * 2)
            repeat(attrCount) {
                val ak = pool.get(r.int())
                (attrs as HashMap)[ak] = pool.get(r.int())
            }
            index.applyEntry(id, key, attrs)
        }
    }
}

private fun readLayers(r: ByteReader, level: Int, capacity: Int): Array<IntArray> =
    Array(level + 1) {
        val links = IntArray(r.count(4, "neighbour")) { r.int() }
        for (link in links) {
            if (link < 0 || link >= capacity) {
                throw KromusFormatException("corrupt kromus delta: neighbour $link outside 0..${capacity - 1}")
            }
        }
        links
    }

// --- text ---

// Record kinds. A metadata edit is distinguished from a content change so replay can leave the
// document's ordinal alone: ordinals are the tie-break between equally scored documents, so folding
// the two together would quietly reorder results rather than fail.
private const val TEXT_REMOVED = 0
private const val TEXT_UPSERTED = 1
private const val TEXT_ATTRIBUTES = 2

/**
 * Encodes every document that changed since the last [encodeToByteArray] or [encodeDelta], or null
 * when nothing has.
 *
 * @throws IllegalStateException if [TextIndex.needsFullSnapshot].
 */
public fun <K> TextIndex<K>.encodeDelta(keyCodec: KeyCodec<K>): ByteArray? {
    check(!needsFullSnapshot) {
        "this index needs a full encodeToByteArray: it has been cleared, or never encoded"
    }
    val changes = dirtyChanges()
    if (changes.isEmpty()) return null

    val w = ByteWriter()
    w.deltaHeader(KIND_TEXT_DELTA, TEXT_DELTA_FORMAT)
    w.long(revision)

    val pool = StringPoolWriter()
    val records = ByteWriter()
    for ((key, change) in changes) {
        val entry = entryOf(key)
        // A key removed and re-added inside one window is Upserted and present; one that is gone is
        // Removed whatever it was marked, which also covers an attribute edit undone by a removal.
        if (entry == null) {
            records.byte(TEXT_REMOVED)
            records.bytes(keyCodec.encode(key))
            continue
        }
        when (change) {
            TextIndex.DocChange.Removed -> {
                records.byte(TEXT_REMOVED)
                records.bytes(keyCodec.encode(key))
            }
            TextIndex.DocChange.AttributesOnly -> {
                records.byte(TEXT_ATTRIBUTES)
                records.bytes(keyCodec.encode(key))
                records.int(entry.attributes.size)
                for ((ak, av) in entry.attributes.canonical()) {
                    records.int(pool.idOf(ak))
                    records.int(pool.idOf(av))
                }
            }
            TextIndex.DocChange.Upserted -> {
                records.byte(TEXT_UPSERTED)
                records.bytes(keyCodec.encode(key))
                records.int(ordinalOf(key)!!)
                records.int(entry.length)
                records.int(entry.termFreqs.size)
                for ((term, freq) in entry.termFreqs.canonical()) {
                    records.int(pool.idOf(term))
                    records.int(freq)
                }
                records.int(entry.attributes.size)
                for ((ak, av) in entry.attributes.canonical()) {
                    records.int(pool.idOf(ak))
                    records.int(pool.idOf(av))
                }
            }
        }
    }
    pool.writeTo(w)
    w.int(changes.size)
    w.raw(records.toByteArray())

    val bytes = w.toByteArray()
    checkpoint(revisionOf(bytes))
    return bytes
}

/**
 * Reconstructs a full-text index from a snapshot plus the [deltas] recorded after it, in order. Pass
 * the same [analyzer] the index was built with, exactly as for [decodeTextIndex].
 *
 * @throws KromusFormatException if the bytes are unreadable or a delta does not chain onto what
 *   precedes it.
 */
public fun <K> decodeTextIndex(
    bytes: ByteArray,
    deltas: List<ByteArray>,
    keyCodec: KeyCodec<K>,
    analyzer: Analyzer = Analyzer.standard(),
    expect: String? = null,
): TextIndex<K> {
    val index = decodeTextIndex(bytes, keyCodec, analyzer, expect)
    var revision = revisionOf(bytes)
    index.checkpoint(revision)
    for (delta in deltas) {
        applyTextDelta(index, delta, revision, keyCodec)
        revision = revisionOf(delta)
        index.checkpoint(revision)
    }
    return index
}

private fun <K> applyTextDelta(index: TextIndex<K>, delta: ByteArray, parent: Long, keyCodec: KeyCodec<K>) {
    val r = ByteReader(delta)
    r.deltaHeader(KIND_TEXT_DELTA, TEXT_DELTA_FORMAT)
    r.expectParent(parent, "text")

    val pool = StringPoolReader(r)
    val count = r.count(5, "document change")
    repeat(count) {
        when (val kind = r.byte()) {
            TEXT_REMOVED -> index.remove(keyCodec.decode(r.bytes()))
            TEXT_ATTRIBUTES -> {
                val key = keyCodec.decode(r.bytes())
                index.updateAttributes(key, readAttributes(r, pool))
            }
            TEXT_UPSERTED -> {
                val key = keyCodec.decode(r.bytes())
                val ordinal = r.int()
                if (ordinal < 0) throw KromusFormatException("corrupt kromus delta: negative ordinal $ordinal")
                val length = r.int()
                if (length < 0) throw KromusFormatException("corrupt kromus delta: negative document length $length")
                val termCount = r.count(8, "term")
                val termFreqs = HashMap<String, Int>(termCount * 2)
                repeat(termCount) {
                    val term = pool.get(r.int())
                    termFreqs[term] = r.int()
                }
                val attributes = readAttributes(r, pool)
                index.remove(key)
                index.loadDocAt(ordinal, key, termFreqs, length, attributes)
            }
            else -> throw KromusFormatException("corrupt kromus delta: unknown document change kind $kind")
        }
    }
    // Replay went through the public mutators, which marked everything dirty again; the caller
    // checkpoints straight after, which clears that.
}

private fun readAttributes(r: ByteReader, pool: StringPoolReader): Map<String, String> {
    val n = r.count(8, "attribute")
    if (n == 0) return emptyMap()
    val attrs = HashMap<String, String>(n * 2)
    repeat(n) {
        val ak = pool.get(r.int())
        attrs[ak] = pool.get(r.int())
    }
    return attrs
}

// --- hybrid ---

/**
 * Encodes what changed in both modalities since the last [encodeToByteArray] or [encodeDelta], or
 * null when neither changed.
 *
 * @throws IllegalStateException if [HybridIndex.needsFullSnapshot].
 */
public fun <K> HybridIndex<K>.encodeDelta(keyCodec: KeyCodec<K>): ByteArray? {
    check(!needsFullSnapshot) {
        "this index needs a full encodeToByteArray: it has been compacted, cleared, or never encoded"
    }
    val vectorDelta = vectorPart().encodeDelta(keyCodec)
    val textDelta = textPart().encodeDelta(keyCodec)
    if (vectorDelta == null && textDelta == null) return null

    val w = ByteWriter()
    w.deltaHeader(KIND_HYBRID_DELTA, HYBRID_DELTA_FORMAT)
    // Either half can be unchanged on its own — a metadata edit through one modality, say — so each
    // is optional rather than the pair being all-or-nothing.
    w.byte(if (vectorDelta != null) 1 else 0)
    if (vectorDelta != null) w.bytes(vectorDelta)
    w.byte(if (textDelta != null) 1 else 0)
    if (textDelta != null) w.bytes(textDelta)
    return w.toByteArray()
}

/**
 * Reconstructs a hybrid index from a snapshot plus the [deltas] recorded after it, in order. Pass the
 * same [analyzer] used to build it.
 *
 * @throws KromusFormatException if the bytes are unreadable or a delta does not chain onto what
 *   precedes it.
 */
public fun <K> decodeHybridIndex(
    bytes: ByteArray,
    deltas: List<ByteArray>,
    keyCodec: KeyCodec<K>,
    analyzer: Analyzer = Analyzer.standard(),
    expect: String? = null,
): HybridIndex<K> {
    // A hybrid snapshot is a container; its halves are sections, not a length-prefixed stream.
    val c = ContainerReader(bytes, KIND_HYBRID, HYBRID_FORMAT, expect)
    val rrfK = c.section("CNFG").int()
    if (rrfK < 1) throw KromusFormatException("corrupt kromus index: rrfK $rrfK")
    val vectorSnapshot = c.sectionBytes("VIDX")
    val textSnapshot = c.sectionBytes("TIDX")

    // Split each hybrid delta into its two halves and hand them to the layers, which do their own
    // chaining checks against their own snapshots.
    val vectorDeltas = ArrayList<ByteArray>()
    val textDeltas = ArrayList<ByteArray>()
    for (delta in deltas) {
        val dr = ByteReader(delta)
        dr.deltaHeader(KIND_HYBRID_DELTA, HYBRID_DELTA_FORMAT)
        if (dr.byte() == 1) vectorDeltas.add(dr.bytes())
        if (dr.byte() == 1) textDeltas.add(dr.bytes())
    }

    val vectorIndex = decodeVectorIndex(vectorSnapshot, vectorDeltas, keyCodec)
    val textIndex = decodeTextIndex(textSnapshot, textDeltas, keyCodec, analyzer)
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
