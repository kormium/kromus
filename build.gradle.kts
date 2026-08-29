import com.vanniktech.maven.publish.MavenPublishBaseExtension

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Kotlin Gradle plugin for all modules (they apply kotlin("multiplatform") without a version).
        classpath(libs.kotlin.gradle.plugin)
        // Android Gradle plugin for the modules that declare an android target via the AGP KMP
        // library plugin (on-device search is a first-class Android/iOS use case).
        classpath(libs.android.gradle.plugin)
    }
}

plugins {
    // Applied to the publishable library subprojects below (not to the root itself).
    alias(libs.plugins.vanniktech.publish) apply false
    // Locks the public ABI of the stable core (JVM + klib). Changes require `./gradlew apiDump`
    // and a review of the .api diffs.
    alias(libs.plugins.binary.compatibility.validator)
    // Formatting, applied to every module below. `./gradlew ktlintFormat` fixes, `ktlintCheck`
    // verifies (and runs as part of `check`). Rules are configured in .editorconfig.
    alias(libs.plugins.ktlint)
    // API reference. Applied here because the root project is what aggregates the per-module output
    // into one cross-linked site: `./gradlew dokkaGenerate` -> build/dokka/html.
    alias(libs.plugins.dokka)
}

val ktlintVersion = libs.versions.ktlint

allprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(ktlintVersion)
        filter { exclude("**/build/**") }
    }
}

apiValidation {
    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib {
        enabled = true
    }
    // Only kromus-core is API-locked for now: the adapter/companion modules are pre-1.0 and their
    // surfaces still move; the samples are apps, not API.
    ignoredProjects.addAll(
        listOf(
            "kromus-onnx",
            "kromus-kemus",
            "kromus-sync",
            "common",
            "quickstart",
            "hybrid",
            "quantization",
            "sync",
            "onnx",
            "benchmarks",
        ),
    )
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
    // BCV's klib ABI check only holds up on a host that can build every declared target; gate it to
    // macOS (which builds the Apple targets too), matching where releases are cut.
    tasks.matching { it.name == "klibApiCheck" }.configureEach {
        onlyIf("klib ABI is validated on macOS, where every declared target builds") {
            org.jetbrains.kotlin.konan.target.HostManager.hostIsMac
        }
    }
}

// iOS simulator tests need an installed iOS simulator runtime (Xcode). On a machine without one
// the simulator test task fails and breaks `check`. Gate those tasks on runtime availability so
// `check` stays runnable. Override with -PenableIosSimulatorTests=true|false.
val iosSimulatorTestsEnabled: Boolean by lazy {
    when (providers.gradleProperty("enableIosSimulatorTests").orNull) {
        "true" -> true
        "false" -> false
        else -> runCatching {
            val process = ProcessBuilder("xcrun", "simctl", "list", "runtimes")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.lineSequence().any { it.contains("iOS") }
        }.getOrDefault(false)
    }
}

allprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
        onlyIf("no iOS simulator runtime available") { iosSimulatorTestsEnabled }
    }
}

// Publishing to Maven Central, shared by the library modules. Credentials
// (mavenCentralUsername/Password) and the GPG key (signingInMemoryKey/Password) are supplied
// out-of-band via ORG_GRADLE_PROJECT_* env vars in CI — see gradle.properties.
val publishableModules = setOf(
    "kromus-core",
    "kromus-kemus",
    "kromus-onnx",
    "kromus-sync",
)

val moduleDescriptions = mapOf(
    "kromus-core" to
        (
            "kromus — an embedded, reflection-free Kotlin Multiplatform hybrid search engine: a pure-Kotlin " +
                "HNSW vector index with a full-text/BM25 layer and RRF hybrid queries, quantization, metadata " +
                "filters and binary persistence, running on JVM, Android, iOS, Native and the web (Wasm)."
        ),
    "kromus-kemus" to
        (
            "kromus-kemus — an optional kromus adapter that persists a vector/full-text index into a kemus " +
                "store (embedded, offline-first, with online sync)."
        ),
    "kromus-onnx" to
        (
            "kromus-onnx — a text embedder for kromus: one common-code pipeline (WordPiece tokenizer, " +
                "pooling, L2 normalization) with pluggable ONNX Runtime backends on every target."
        ),
    "kromus-sync" to
        (
            "kromus-sync — keeps a kromus index fresh from a Flow<List<T>> snapshot stream, reconciling " +
                "added/changed/removed entries with no data-layer dependency."
        ),
)

// The modules whose API reference is published. Mirrors publishableModules: if it ships to Central,
// its KDoc should be browsable without adding the dependency first.
dependencies {
    publishableModules.forEach { dokka(project(":$it")) }
}

dokka {
    moduleName.set("kromus")
}

subprojects {
    if (name !in publishableModules) return@subprojects

    apply(plugin = "com.vanniktech.maven.publish")
    apply(plugin = "org.jetbrains.dokka")

    // Every declared KDoc link resolves back to the source on GitHub at the tag being documented.
    extensions.configure<org.jetbrains.dokka.gradle.DokkaExtension> {
        dokkaSourceSets.configureEach {
            sourceLink {
                localDirectory.set(projectDir.resolve("src"))
                remoteUrl("https://github.com/kormium/kromus/tree/v$version/${project.name}/src")
                remoteLineSuffix.set("#L")
            }
        }
    }

    configure<MavenPublishBaseExtension> {
        publishToMavenCentral()
        signAllPublications()
        coordinates(group.toString(), name, version.toString())

        pom {
            name.set(project.name)
            description.set(moduleDescriptions.getValue(project.name))
            inceptionYear.set("2026")
            url.set("https://github.com/kormium/kromus")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("knyazevs")
                    name.set("Sergey Knyazev")
                    email.set("sknyazev@vk.com")
                    url.set("https://github.com/knyazevs")
                }
            }
            scm {
                url.set("https://github.com/kormium/kromus")
                connection.set("scm:git:https://github.com/kormium/kromus.git")
                developerConnection.set("scm:git:ssh://git@github.com/kormium/kromus.git")
            }
        }
    }
}
