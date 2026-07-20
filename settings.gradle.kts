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

// kromus-kemus — optional adapter that persists an index into a kemus store (embedded / offline /
// online sync). It depends on io.github.kemus:kemus-core, which is not yet on Maven Central, so it
// is only wired in when the sibling checkout is present next to this repo: a plain clone (and CI)
// builds just kromus-core, while local development gets the module via a composite build. Once
// kemus is published, this becomes an unconditional module + Central dependency.
val kemusBuild = file("../kemus")
if (kemusBuild.resolve("settings.gradle.kts").exists()) {
    includeBuild(kemusBuild)
    include("kromus-kemus")
}
