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
    implementation(project(":samples:common"))
}

application {
    mainClass.set("io.github.kromus.samples.hybrid.MainKt")
}
