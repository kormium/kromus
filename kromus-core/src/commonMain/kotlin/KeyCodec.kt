package io.github.kromus

/**
 * Serializes and deserializes index keys of type [K] so an index can be persisted and reloaded.
 * Supply one to `encodeToByteArray` / `decode*` (see [io.github.kromus] persistence functions).
 * Built-ins cover the common key types; implement your own for anything else.
 */
public interface KeyCodec<K> {
    public fun encode(key: K): ByteArray

    public fun decode(bytes: ByteArray): K

    public companion object {
        /** UTF-8 string keys. */
        public val string: KeyCodec<String> = object : KeyCodec<String> {
            override fun encode(key: String): ByteArray = key.encodeToByteArray()
            override fun decode(bytes: ByteArray): String = bytes.decodeToString()
        }

        /** 4-byte big-endian Int keys. */
        public val int: KeyCodec<Int> = object : KeyCodec<Int> {
            override fun encode(key: Int): ByteArray = ByteArray(4) { (key ushr (24 - it * 8)).toByte() }
            override fun decode(bytes: ByteArray): Int {
                var v = 0
                for (b in bytes) v = (v shl 8) or (b.toInt() and 0xFF)
                return v
            }
        }

        /** 8-byte big-endian Long keys. */
        public val long: KeyCodec<Long> = object : KeyCodec<Long> {
            override fun encode(key: Long): ByteArray = ByteArray(8) { (key ushr (56 - it * 8)).toByte() }
            override fun decode(bytes: ByteArray): Long {
                var v = 0L
                for (b in bytes) v = (v shl 8) or (b.toLong() and 0xFF)
                return v
            }
        }
    }
}
