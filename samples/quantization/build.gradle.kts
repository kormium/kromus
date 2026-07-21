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
    mainClass.set("io.github.kromus.samples.quantization.MainKt")
}
