# kromus samples

Small, self-contained, runnable examples (JVM). Vectors are hand-made 3-D "topic" coordinates so every
sample runs with no model or network; in a real app you'd get vectors from an embedding model — see
[`kromus-onnx`](../kromus-onnx/).

| Sample | Run | Shows |
| --- | --- | --- |
| **quickstart** | `./gradlew :samples:quickstart:run` | `VectorIndex` basics + binary persistence |
| **hybrid** | `./gradlew :samples:hybrid:run` | vector + BM25 fused with RRF, and a metadata filter — why hybrid beats either alone |
| **quantization** | `./gradlew :samples:quantization:run` | binary quantization (~32× smaller vectors) + a full-precision re-rank |
| **sync** | `./gradlew :samples:sync:run` | keep an index fresh from a `Flow<List<T>>` snapshot stream ([`kromus-sync`](../kromus-sync/)) |

Expected output is deterministic — e.g. `hybrid` prints how vector search finds the semantically
related doc, BM25 finds the exact-keyword doc, and the hybrid returns both.
