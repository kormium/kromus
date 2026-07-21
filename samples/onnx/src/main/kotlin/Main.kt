package io.github.kromus.samples.onnx

import io.github.kromus.HybridIndex
import io.github.kromus.onnx.OnnxTextEmbedder
import io.github.kromus.onnx.OrtOnnxSession
import io.github.kromus.onnx.WordPieceTokenizer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * Real semantic search with a real model — `all-MiniLM-L6-v2` (384-dim sentence embeddings) via
 * `kromus-onnx` (ONNX Runtime under the hood).
 *
 * The model (~23 MB, int8) and its vocabulary download once into a local cache, so:
 *
 *     ./gradlew :samples:onnx:run
 *
 * just works. Unlike the toy-embedder samples, this uses genuine embeddings, so paraphrases match
 * even with no shared words — and the hybrid still catches exact codes.
 */
private const val MODEL_URL = "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model_quantized.onnx"
private const val VOCAB_URL = "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/vocab.txt"
private const val DIMENSIONS = 384

fun main() = runBlocking {
    val cache = File(System.getProperty("user.home"), ".cache/kromus/all-MiniLM-L6-v2").apply { mkdirs() }
    val modelFile = ensure(cache.resolve("model.onnx"), MODEL_URL, "model (int8, ~23 MB)")
    val vocabFile = ensure(cache.resolve("vocab.txt"), VOCAB_URL, "vocabulary")

    println("Loading all-MiniLM-L6-v2 …")
    val embedder = OnnxTextEmbedder(
        session = OrtOnnxSession(modelFile.readBytes()),
        tokenizer = WordPieceTokenizer.fromVocabText(vocabFile.readText()),
        dimensions = DIMENSIONS,
    )

    val index = HybridIndex<String>(dimensions = DIMENSIONS)
    val documents = listOf(
        "Kotlin coroutines let you write asynchronous code sequentially",
        "A step-by-step sourdough bread recipe for beginners",
        "The origins and evolution of jazz music",
        "Backpacking gear checklist for a week in the Alps",
        "How to fix error E-4021 that appears on app startup",
    )
    for (doc in documents) index.add(doc, embedder.embed(doc), doc)
    println("Indexed ${index.size} documents.\n")

    // Semantic search: the query shares almost no words with the answer, but real embeddings match
    // by meaning. The score is the cosine similarity.
    println("── Semantic search (by meaning) ──")
    for (query in listOf(
        "how do I write asynchronous code",
        "steps to bake bread at home",
        "the history of jazz musicians",
        "what to pack for a mountain trek",
    )) {
        val top = index.searchVector(embedder.embed(query), k = 1).first()
        println("  \"$query\"")
        println("     → ${score(top.score)}  ${top.key}\n")
    }

    // Hybrid: a natural-language need + an exact code that embeddings can't represent. BM25 catches
    // the code, the vector catches the meaning, and fusion returns both.
    println("── Hybrid (meaning + an exact code) ──")
    val hits = index.search(embedder.embed("help with async programming"), text = "E-4021", k = 2)
    println("  query = \"help with async programming\" + code \"E-4021\"")
    println("     → ${hits.map { it.key }}")
}

private fun score(s: Float): String = "${(s * 100).toInt().coerceIn(0, 100)}%".padStart(4)

private fun ensure(file: File, url: String, what: String): File {
    if (file.exists() && file.length() > 0) return file
    println("Downloading $what → ${file.name} …")
    download(url, file)
    return file
}

/** Minimal downloader that follows Hugging Face's redirect chain to the CDN. */
private fun download(url: String, dest: File) {
    var current = url
    repeat(6) {
        val conn = (URI(current).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", "kromus-sample")
        }
        when (val code = conn.responseCode) {
            in 300..399 -> {
                current = conn.getHeaderField("Location") ?: error("redirect without Location")
                conn.disconnect()
            }
            200 -> {
                conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
                return
            }
            else -> error("download failed: HTTP $code for $current")
        }
    }
    error("too many redirects for $url")
}
