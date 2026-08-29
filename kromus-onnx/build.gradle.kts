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
            // The interop test drives the generated bindings for real, so its executable has to link
            // the library the headers describe. Only the test links it: kromus itself binds nothing,
            // which is what keeps the default build free of any ORT dependency.
            binaries.withType(org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable::class.java)
                .configureEach {
                    linkerOpts("-L$onnxRoot/lib", "-lonnxruntime")
                }
        }
        // Registered only under the flag, so a default build never sees a source file that needs
        // headers it does not have.
        sourceSets.getByName("nativeTest").kotlin.srcDir("src/nativeOrtTest/kotlin")

        // The linked library is resolved at run time too, not just at link time.
        project.tasks.withType(org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest::class.java)
            .configureEach {
                environment("LD_LIBRARY_PATH", "$onnxRoot/lib")
            }
    }

    sourceSets {
        // The whole embedding pipeline — tokenizer, pooling, normalization — is pure Kotlin and lives
        // here, identical on every target. Only OnnxSession (the raw model call) is per-platform.
        // coroutines: CallbackOnnxSession bridges a callback runner to suspend; the web/jvm backends
        // await/dispatch too.
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        // JVM + Android share one backend: onnxruntime and onnxruntime-android expose the same
        // `ai.onnxruntime` Java API, so OrtOnnxSession lives in this intermediate source set. The API
        // is compileOnly here; each target adds the runtime artifact it needs below.
        val jvmAndroidMain by creating {
            dependsOn(commonMain)
            dependencies {
                compileOnly(libs.onnxruntime)
            }
        }
        val jvmMain by getting {
            dependsOn(jvmAndroidMain)
            dependencies {
                implementation(libs.onnxruntime)
            }
        }
        val androidMain by getting {
            dependsOn(jvmAndroidMain)
            dependencies {
                implementation(libs.onnxruntime.android)
            }
        }
        // Web (jsMain/wasmJsMain) and Apple (iosMain) backends need only coroutines, inherited from
        // commonMain. Web awaits onnxruntime-web; iOS uses CallbackOnnxSession over a Swift runner.
    }
}
