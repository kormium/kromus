import com.vanniktech.maven.publish.MavenPublishBaseExtension

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Kotlin Gradle plugin for all modules (they apply kotlin("multiplatform") without a version).
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
        // Android Gradle plugin for the modules that declare an android target via the AGP KMP
        // library plugin (on-device search is a first-class Android/iOS use case).
        classpath("com.android.tools.build:gradle:9.2.1")
    }
}

plugins {
    // Applied to the publishable library subprojects below (not to the root itself).
    id("com.vanniktech.maven.publish") version "0.36.0" apply false
    // Locks the public ABI of the stable core (JVM + klib). Changes require `./gradlew apiDump`
    // and a review of the .api diffs.
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.1"
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
            "kromus-onnx", "kromus-kemus", "kromus-sync",
            "common", "quickstart", "hybrid", "quantization", "sync", "onnx",
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
                .redirectErrorStream(true).start()
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

// The optional :kromus-kemus module pulls in the sibling `kemus` build (a composite build). Composite
// builds share ONE yarn root, so kemus's JS/Wasm npm deps get merged into it — a lock that matches
// neither kromus-core's committed lock nor a plain clone's (where kemus isn't included at all). A
// single committed lock therefore can't satisfy the strict (FAIL) mismatch check in both
// configurations, so downgrade it to a warning for JS and Wasm.
plugins.withType(org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin::class.java) {
    the<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec>()
        .yarnLockMismatchReport.set(org.jetbrains.kotlin.gradle.targets.js.yarn.YarnLockMismatchReport.WARNING)
}
plugins.withType(org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin::class.java) {
    the<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootEnvSpec>()
        .yarnLockMismatchReport.set(org.jetbrains.kotlin.gradle.targets.js.yarn.YarnLockMismatchReport.WARNING)
}

// Publishing to Maven Central, shared by the library modules. Credentials
// (mavenCentralUsername/Password) and the GPG key (signingInMemoryKey/Password) are supplied
// out-of-band via ORG_GRADLE_PROJECT_* env vars in CI — see gradle.properties.
val publishableModules = setOf(
    "kromus-core",
)

subprojects {
    if (name !in publishableModules) return@subprojects

    apply(plugin = "com.vanniktech.maven.publish")

    configure<MavenPublishBaseExtension> {
        publishToMavenCentral()
        signAllPublications()
        coordinates(group.toString(), name, version.toString())

        pom {
            name.set("kromus")
            description.set(
                "kromus — an embedded, reflection-free Kotlin Multiplatform hybrid search engine: " +
                    "a pure-Kotlin HNSW vector index (with a full-text/BM25 layer and RRF hybrid " +
                    "queries on the roadmap) that runs on JVM, Android, iOS, Native and the web (Wasm).",
            )
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
