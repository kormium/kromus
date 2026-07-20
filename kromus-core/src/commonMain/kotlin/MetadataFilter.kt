package io.github.kromus

/**
 * A predicate over an entry's string attributes, used to restrict search results. Receives the
 * entry's attribute map (empty if it has none) and returns whether the entry may be returned.
 *
 * ```
 * val docsOnly: MetadataFilter = { it["type"] == "doc" && it["lang"] == "en" }
 * index.search(query, k = 10, filter = docsOnly)
 * ```
 *
 * For the vector index the filter is applied during graph traversal, so a filtered query still
 * returns up to `k` matches rather than filtering a fixed candidate set down to fewer.
 */
public typealias MetadataFilter = (attributes: Map<String, String>) -> Boolean
