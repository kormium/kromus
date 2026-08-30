package io.github.kromus

/**
 * Thrown when bytes handed to a `decode*` function are not a kromus index this build can read —
 * truncated, corrupted, or written by a different format version.
 *
 * A persisted index outlives the app that wrote it (it is cached on device, shipped as an asset or
 * synced through a store), so an upgrade can meet bytes from an older format. Catch this, discard
 * the cached bytes and rebuild the index from the source data:
 *
 * ```
 * val index = try {
 *     decodeHybridIndex(cached, KeyCodec.string)
 * } catch (e: KromusFormatException) {
 *     buildIndexFromScratch()
 * }
 * ```
 *
 * It extends [IllegalArgumentException], so existing `catch (e: IllegalArgumentException)` still works.
 *
 * The constructor is public because a [ByteSource] of your own has to be able to report the same
 * failures the library's own do — a range outside the file, a read that could not be completed. A
 * caller that catches this to rebuild an index should see the same exception whether the bytes were
 * malformed or the file they were meant to come from could not be read.
 */
public class KromusFormatException(
    message: String,
) : IllegalArgumentException(message)
