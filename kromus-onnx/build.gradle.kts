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
        namespace = "io.github.kromus.onnx"
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

    // Opt-in ONNX Runtime C API bindings for desktop/native. Off by default so a plain build needs no
    // ORT headers; enable with `-Pkromus.onnxCApi=/path/to/onnxruntime` (a dir with
    // include/onnxruntime_c_api.h). See src/nativeInterop/cinterop/onnxruntime.def and the readme.
    project.providers.gradleProperty("kromus.onnxCApi").orNull?.let { onnxRoot ->
        targets.withType(org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget::class.java).configureEach {
            compilations.getByName("main").cinterops.create("onnxruntime") {
                defFile(project.file("src/nativeInterop/cinterop/onnxruntime.def"))
                includeDirs("$onnxRoot/include")
            }
        }
    }

    sourceSets {
        // The whole embedding pipeline — tokenizer, pooling, normalization — is pure Kotlin and lives
        // here, identical on every target. Only OnnxSession (the raw model call) is per-platform.
        // coroutines: CallbackOnnxSession bridges a callback runner to suspend; the web/jvm backends
        // await/dispatch too.
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
        // JVM + Android share one backend: onnxruntime and onnxruntime-android expose the same
        // `ai.onnxruntime` Java API, so OrtOnnxSession lives in this intermediate source set. The API
        // is compileOnly here; each target adds the runtime artifact it needs below.
        val jvmAndroidMain by creating {
            dependsOn(commonMain)
            dependencies {
                compileOnly("com.microsoft.onnxruntime:onnxruntime:1.20.0")
            }
        }
        val jvmMain by getting {
            dependsOn(jvmAndroidMain)
            dependencies {
                implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
            }
        }
        val androidMain by getting {
            dependsOn(jvmAndroidMain)
            dependencies {
                implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
            }
        }
        // Web (jsMain/wasmJsMain) and Apple (iosMain) backends need only coroutines, inherited from
        // commonMain. Web awaits onnxruntime-web; iOS uses CallbackOnnxSession over a Swift runner.
    }
}
