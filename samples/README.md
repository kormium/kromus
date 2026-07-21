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
| **onnx** | `./gradlew :samples:onnx:run` | the **real deal**: `all-MiniLM-L6-v2` via [`kromus-onnx`](../kromus-onnx/) — real semantic search where queries match by meaning (needs network on first run; auto-downloads a ~23 MB model to a local cache) |

The first four use `ToyEmbedder` — it maps each word to a topic (programming / cooking / music /
outdoors), so you can predict every match by reading its lexicon; that's the whole point: you can
*see* why semantic search works. The **onnx** sample then shows the same thing with a genuine model:

```
Search: "how do I write asynchronous code"
   → 58%  Kotlin coroutines let you write asynchronous code sequentially
Search: "the history of jazz musicians"
   → 83%  The origins and evolution of jazz music
```

