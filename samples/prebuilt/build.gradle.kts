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

// --- build the index at build time ---
//
// The shape this sample exists to show: the corpus is embedded and indexed once, here, and the app
// only ever loads the result. That moves the two costs a device is worst at — running an embedding
// model over the whole corpus, and building an HNSW graph — onto a machine that does not mind them,
// and it is why a phone can ship semantic search over content it never processes.
//
// A real project would run this in CI against its own corpus and publish the blob as an asset (or,
// past a certain size, fetch it on first launch and keep it warm with encodeDelta).

val corpus = layout.projectDirectory.file("corpus.txt")
val generatedResources = layout.buildDirectory.dir("generated/index")

val buildIndex by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Embeds corpus.txt and writes a prebuilt kromus index into the resources"

    // Declared so Gradle reruns this when the corpus changes and skips it when nothing did.
    inputs.file(corpus).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(generatedResources)

    // Compiled classes plus external dependencies — deliberately *not* runtimeClasspath, which
    // includes this module's processed resources and would make the task depend on the thing it
    // produces.
    classpath = sourceSets["main"].output.classesDirs + configurations["runtimeClasspath"]
    mainClass.set("io.github.kromus.samples.prebuilt.BuildIndexKt")
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(corpus.asFile.absolutePath, generatedResources.get().asFile.absolutePath)
        },
    )
}

// The blob is a resource, so it is packaged with the app and reached with getResourceAsStream.
sourceSets["main"].resources.srcDir(generatedResources)
tasks.named("processResources") { dependsOn(buildIndex) }

application {
    mainClass.set("io.github.kromus.samples.prebuilt.MainKt")
}
