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
    implementation(project(":kromus-sync"))
    implementation(project(":samples:common"))
    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass.set("io.github.kromus.samples.sync.MainKt")
}
