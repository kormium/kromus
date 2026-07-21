# kromus samples

Small, runnable examples (JVM) that tell a plain-English story. Each embeds **real text** with a tiny,
readable stand-in for an embedding model — [`ToyEmbedder`](common/src/main/kotlin/ToyEmbedder.kt) in
`samples:common` — so every result is understandable and reproducible with no model or network. For
real semantics, swap in [`kromus-onnx`](../kromus-onnx/).

| Sample | Run | What you'll see |
| --- | --- | --- |
| **quickstart** | `./gradlew :samples:quickstart:run` | asking *"how do I write asynchronous code?"* finds the *"Kotlin coroutines guide"* — by meaning, with **no shared words** |
| **hybrid** | `./gradlew :samples:hybrid:run` | vector search answers a question; keyword search finds an exact code (`E-4021`) that embeddings can't; hybrid returns **both** |
| **quantization** | `./gradlew :samples:quantization:run` | shrink vectors ~**32×** (1 bit each), then a two-step *rough search → exact re-rank* keeps results accurate |
| **sync** | `./gradlew :samples:sync:run` | a notes app: as notes are added/edited/deleted the search index **stays in step** ([`kromus-sync`](../kromus-sync/)) |

`ToyEmbedder` maps each word to a topic (programming / cooking / music / outdoors), so you can predict
every match by reading its lexicon — that's the whole point: you can *see* why semantic search works.
