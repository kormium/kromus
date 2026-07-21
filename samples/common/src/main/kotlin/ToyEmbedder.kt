package io.github.kromus.samples.common

/**
 * A TINY stand-in for a real embedding model — written so you can *see* why semantic search works.
 *
 * A real model (see `kromus-onnx`) turns text into a list of numbers ("a vector") that captures its
 * **meaning**, so two texts about the same thing land close together even if they share no words.
 *
 * This toy does the same with a small keyword lexicon: each dimension is a topic, and a text's value
 * on that dimension is simply how many of its words belong to that topic. So `"asynchronous code"`
 * and `"Kotlin coroutines"` both light up the PROGRAMMING dimension and end up close — with no words
 * in common. Read the lexicon below and you can predict every result by hand.
 *
 * It's deliberately dumb (no ML, no download). Swap in `kromus-onnx` for real semantics.
 */
class ToyEmbedder {
    // Each topic is one dimension of the vector. Add words to teach the toy new associations.
    private val topics = linkedMapOf(
        "programming" to setOf(
            "code", "coding", "coroutine", "coroutines", "async", "asynchronous", "concurrency",
            "concurrent", "thread", "threads", "background", "kotlin", "compile", "function", "api",
            "software", "developer", "bug", "program", "programming", "suspend", "await",
        ),
        "cooking" to setOf(
            "recipe", "recipes", "bake", "baking", "bread", "sourdough", "dough", "flour", "oven",
            "cook", "cooking", "kitchen", "food", "ingredient", "ingredients", "yeast", "knead", "starter",
        ),
        "music" to setOf(
            "jazz", "guitar", "song", "songs", "album", "melody", "chord", "chords", "band",
            "instrument", "rhythm", "music", "piano", "saxophone", "blues",
        ),
        "outdoors" to setOf(
            "hiking", "hike", "trail", "trails", "mountain", "mountains", "camping", "camp", "tent",
            "backpack", "backpacking", "forest", "river", "outdoor", "outdoors", "alps", "trek", "trip",
        ),
    )

    /** Length of every vector this embedder produces (one number per topic). */
    val dimensions: Int = topics.size

    /** Turns text into a topic vector. A text with no known words stays all-zero (kromus handles that). */
    fun embed(text: String): FloatArray {
        val words = text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        val vector = FloatArray(dimensions)
        topics.values.forEachIndexed { i, vocabulary ->
            vector[i] = words.count { it in vocabulary }.toFloat()
        }
        return vector
    }
}
