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
        browser()
        nodejs()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
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
