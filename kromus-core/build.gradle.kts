import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

repositories {
    google()
    mavenCentral()
}

kotlin {
    explicitApi()

    jvmToolchain(21)

    jvm {
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    // On-device search is a first-class Android/iOS use case.
    android {
        namespace = "io.github.kromus"
        compileSdk = 36
        minSdk = 24
    }

    // Apple clients.
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    // Desktop / server / edge native.
    linuxX64()
    linuxArm64()
    macosX64()
    macosArm64()
    mingwX64()

    // Browser / Node web clients. The engine is pure computation over FloatArray, so it compiles
    // to the whole web stack; nodejs() is enough to run the tests, browser() lets consumers ship it.
    js {
        // The recall/purity tests index thousands of vectors and take ~2s on JS, which is Mocha's
        // default per-test timeout — fast enough on a dev machine, over the line on a CI runner.
        // They are honest tests of a slow-by-nature operation, so raise the limit rather than
        // shrink the corpus and weaken what they measure.
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                    // Karma runs Mocha in the browser; its timeout is set through the client config.
                    useConfigDirectory(project.file("karma.config.d"))
                }
            }
        }
        nodejs {
            testTask {
                useMocha { timeout = "60s" }
            }
        }
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs {
            testTask {
                useMocha { timeout = "60s" }
            }
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        // The vector layer is deliberately zero-dependency: HNSW is arithmetic over FloatArray and
        // graph structures in pure common code — no coroutines, serialization, crypto or native
        // interop — so one implementation runs identically on every target (that is the whole moat).
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
