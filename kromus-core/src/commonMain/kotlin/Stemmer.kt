package io.github.kromus

/**
 * Reduces a term to a stem so morphological variants collide in the index (e.g. `cats` and `cat`).
 * Plug into [Analyzer.standard]. For serious linguistic stemming supply your own (e.g. a Snowball
 * port); the built-in [englishLight] is a small, dependency-free heuristic.
 */
public fun interface Stemmer {
    public fun stem(term: String): String

    public companion object {
        /**
         * A light, heuristic English stemmer: strips common plural and verb suffixes. Not a full
         * Porter/Snowball algorithm — it trades linguistic accuracy for zero dependencies and
         * predictable behaviour. Only touches reasonably long terms so short words are left alone.
         */
        public fun englishLight(): Stemmer = Stemmer { term ->
            var s = term
            when {
                s.length > 4 && s.endsWith("ies") -> s = s.dropLast(3) + "y"
                s.length > 4 && (s.endsWith("ses") || s.endsWith("xes") || s.endsWith("zes") ||
                    s.endsWith("ches") || s.endsWith("shes")) -> s = s.dropLast(2)
                s.length > 3 && s.endsWith("s") && !s.endsWith("ss") -> s = s.dropLast(1)
            }
            when {
                s.length > 5 && s.endsWith("ing") -> s = s.dropLast(3)
                s.length > 4 && s.endsWith("ed") -> s = s.dropLast(2)
                s.length > 4 && s.endsWith("ly") -> s = s.dropLast(2)
            }
            s
        }
    }
}
