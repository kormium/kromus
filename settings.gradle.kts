pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "kromus"

// kromus-core — embedded, multiplatform hybrid search engine: pure-Kotlin HNSW vector index +
// BM25 full-text + RRF hybrid, quantization, metadata filters, binary persistence. Zero deps.
include("kromus-core")

// kromus-onnx — optional text embedder. The pipeline (tokenizer, pooling, normalization) is shared
// common code on every target; the model runtime is per-platform (JVM backend ships, others plug in).
include("kromus-onnx")

// kromus-sync — keeps an index fresh from a Flow<List<T>> of snapshots (e.g. kormium-observe). Pure
// kromus-core + coroutines, no data-layer dependency, so it needs no composite build.
include("kromus-sync")

// kromus-concurrent — suspending readers-writer guards around the (single-threaded by design) core
// indexes: concurrent searches, exclusive mutations. kromus-core + coroutines.
include("kromus-concurrent")

// Runnable examples (JVM). `./gradlew :samples:<name>:run`. `samples:common` holds the ToyEmbedder
// (a readable stand-in for a real embedding model) the samples share.
include(
    "samples:common",
    "samples:quickstart",
    "samples:hybrid",
    "samples:quantization",
    "samples:sync",
    "samples:onnx",
)

// kromus-kemus — optional adapter that persists an index into a kemus store (embedded / offline /
// online sync). Depends on io.github.kormium:kemus-core, published to Maven Central.
include("kromus-kemus")
