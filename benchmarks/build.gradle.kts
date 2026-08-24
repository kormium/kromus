plugins {
    kotlin("jvm")
    application
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":kromus-core"))
}

application {
    mainClass.set("io.github.kromus.benchmarks.MainKt")
    // The suite builds several indexes over the same dataset in one process.
    applicationDefaultJvmArgs = listOf("-Xmx4g")
}
