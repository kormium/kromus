package io.github.kromus.kemus

import io.github.kemus.Kemus
import io.github.kromus.Analyzer
import io.github.kromus.HybridIndex
import io.github.kromus.KeyCodec
import io.github.kromus.TextIndex
import io.github.kromus.VectorIndex
import io.github.kromus.decodeHybridIndex
import io.github.kromus.decodeTextIndex
import io.github.kromus.decodeVectorIndex
import io.github.kromus.encodeToByteArray

// Persist a kromus index into a kemus store. kromus serializes to a compact ByteArray; kemus holds
// it as a binary value (Kemus.setBytes/getBytes — raw bytes in memory, base64 only at kemus's text
// boundaries), so the index inherits kemus's persistence, TTL and offline→online sync. Building an
// HNSW graph is expensive; this lets you build once and reload it instantly on the next run.
//
// Analyzers are functions and are not serialized: the text/hybrid loaders take the analyzer the
// index was built with — pass the same one.

/** Stores [this] vector index under [key] in [store]. */
public suspend fun <K> VectorIndex<K>.saveTo(store: Kemus, key: String, keyCodec: KeyCodec<K>) {
    store.setBytes(key, encodeToByteArray(keyCodec))
}

/** Loads a vector index from [key] in [store], or null if the key is absent. */
public suspend fun <K> loadVectorIndex(store: Kemus, key: String, keyCodec: KeyCodec<K>): VectorIndex<K>? =
    store.getBytes(key)?.let { decodeVectorIndex(it, keyCodec) }

/** Stores [this] full-text index under [key] in [store]. */
public suspend fun <K> TextIndex<K>.saveTo(store: Kemus, key: String, keyCodec: KeyCodec<K>) {
    store.setBytes(key, encodeToByteArray(keyCodec))
}

/** Loads a full-text index from [key] in [store], or null if absent. Pass the index's [analyzer]. */
public suspend fun <K> loadTextIndex(
    store: Kemus,
    key: String,
    keyCodec: KeyCodec<K>,
    analyzer: Analyzer = Analyzer.standard(),
): TextIndex<K>? = store.getBytes(key)?.let { decodeTextIndex(it, keyCodec, analyzer) }

/** Stores [this] hybrid index under [key] in [store]. */
public suspend fun <K> HybridIndex<K>.saveTo(store: Kemus, key: String, keyCodec: KeyCodec<K>) {
    store.setBytes(key, encodeToByteArray(keyCodec))
}

/** Loads a hybrid index from [key] in [store], or null if absent. Pass the index's [analyzer]. */
public suspend fun <K> loadHybridIndex(
    store: Kemus,
    key: String,
    keyCodec: KeyCodec<K>,
    analyzer: Analyzer = Analyzer.standard(),
): HybridIndex<K>? = store.getBytes(key)?.let { decodeHybridIndex(it, keyCodec, analyzer) }
