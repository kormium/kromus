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

// kromus-core — embedded, multiplatform hybrid search engine. v0 ships the vector layer
// (pure-Kotlin HNSW ANN index, zero dependencies, every KMP target). The full-text (BM25)
// layer and the reciprocal-rank-fusion hybrid query sit on top of the same store — see readme.
include("kromus-core")
