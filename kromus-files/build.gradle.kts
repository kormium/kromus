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

    android {
        namespace = "io.github.kromus.files"
        compileSdk = 36
        minSdk = 24
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    linuxX64()
    linuxArm64()
    macosX64()
    macosArm64()
    mingwX64()

    // Node gets a real file system; the browser gets OPFS, whose synchronous handles exist only in a
    // worker. Both are in this artifact, and which one a program can use is a property of where it
    // runs rather than of how it was built.
    js {
        browser()
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
        val commonMain by getting {
            dependencies {
                // ByteSource, the interface every source here implements, is part of the public API.
                api(project(":kromus-core"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        // JVM and Android read a file the same way: minSdk 24 rules out java.nio.file, so both go
        // through RandomAccessFile's channel, which has been there since the beginning.
        val jvmAndroidMain by creating {
            dependsOn(commonMain)
        }
        val jvmMain by getting { dependsOn(jvmAndroidMain) }
        val androidMain by getting { dependsOn(jvmAndroidMain) }

        // Wasm cannot share a ByteArray with JavaScript the way Kotlin/JS can — a ByteArray there is
        // an Int8Array, here it is linear memory — so bytes are moved through a typed array, and the
        // declarations for one come from kotlinx-browser rather than the standard library.
        val wasmJsMain by getting {
            dependencies {
                implementation(libs.kotlinx.browser)
            }
        }

        val jvmAndroidTest by creating {
            dependsOn(commonTest)
        }
        val jvmTest by getting { dependsOn(jvmAndroidTest) }
    }
}
