package io.github.kromus.onnx

/**
 * A pure-Kotlin WordPiece tokenizer (BERT / sentence-transformers family), so the tokenizer half of an
 * embedder is shared across every target. Build it from the model's `vocab.txt` via [fromVocabText].
 *
 * Basic tokenization lowercases (when [doLowerCase]) and splits on whitespace and punctuation; each
 * word is then greedily split into the longest matching vocab pieces (`##` continuations), falling
 * back to the unknown token. CJK segmentation is not special-cased — supply a char n-gram vocab or a
 * different tokenizer for CJK-heavy corpora.
 */
public class WordPieceTokenizer(
    private val vocab: Map<String, Int>,
    private val doLowerCase: Boolean = true,
    unkToken: String = "[UNK]",
    clsToken: String = "[CLS]",
    sepToken: String = "[SEP]",
    private val maxCharsPerWord: Int = 100,
) {
    private val unkId = requireId(vocab, unkToken)
    private val clsId = requireId(vocab, clsToken)
    private val sepId = requireId(vocab, sepToken)
    private val unk = unkToken

    /**
     * Tokenizes [text] into model inputs, capped at [maxTokens] (including `[CLS]`/`[SEP]`). Returns the
     * token ids plus the attention mask (all 1) and token-type ids (all 0).
     */
    public fun tokenize(text: String, maxTokens: Int): TokenizedInput {
        val pieces = ArrayList<String>()
        for (word in basicTokenize(text)) wordPiece(word, pieces)

        val limit = if (maxTokens - 2 < 0) 0 else maxTokens - 2
        val kept = if (pieces.size > limit) pieces.subList(0, limit) else pieces

        val ids = IntArray(kept.size + 2)
        ids[0] = clsId
        for (i in kept.indices) ids[i + 1] = vocab[kept[i]] ?: unkId
        ids[ids.size - 1] = sepId

        val ones = IntArray(ids.size) { 1 }
        val zeros = IntArray(ids.size)
        return TokenizedInput(ids, ones, zeros)
    }

    private fun basicTokenize(text: String): List<String> {
        val out = ArrayList<String>()
        val token = StringBuilder()
        fun flush() { if (token.isNotEmpty()) { out.add(token.toString()); token.setLength(0) } }
        for (ch in text) {
            val c = if (doLowerCase) ch.lowercaseChar() else ch
            when {
                c.isWhitespace() -> flush()
                !c.isLetterOrDigit() -> { flush(); out.add(c.toString()) } // punctuation is its own token
                else -> token.append(c)
            }
        }
        flush()
        return out
    }

    private fun wordPiece(word: String, into: MutableList<String>) {
        if (word.length > maxCharsPerWord) { into.add(unk); return }
        var start = 0
        val sub = ArrayList<String>()
        while (start < word.length) {
            var end = word.length
            var match: String? = null
            while (start < end) {
                val piece = if (start > 0) "##" + word.substring(start, end) else word.substring(start, end)
                if (vocab.containsKey(piece)) { match = piece; break }
                end--
            }
            if (match == null) { into.add(unk); return } // any unmatchable piece -> whole word is unknown
            sub.add(match)
            start = end
        }
        into.addAll(sub)
    }

    public companion object {
        /** Builds a tokenizer from a `vocab.txt` (one token per line; the line number is the token id). */
        public fun fromVocabText(vocabText: String, doLowerCase: Boolean = true): WordPieceTokenizer {
            val map = HashMap<String, Int>()
            var id = 0
            for (line in vocabText.lineSequence()) {
                val token = line.trimEnd('\r', '\n')
                if (token.isEmpty() && id == 0) continue
                map[token] = id++
            }
            return WordPieceTokenizer(map, doLowerCase)
        }

        private fun requireId(vocab: Map<String, Int>, token: String): Int =
            vocab[token] ?: throw IllegalArgumentException("vocab is missing the special token '$token'")
    }
}

/** Tokenized model inputs; all three arrays share one length (the sequence length). */
public class TokenizedInput(
    public val inputIds: IntArray,
    public val attentionMask: IntArray,
    public val tokenTypeIds: IntArray,
)
