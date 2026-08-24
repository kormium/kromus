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
 */
public class KromusFormatException internal constructor(
    message: String,
) : IllegalArgumentException(message)
