// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

import java.util.Properties
import org.glavo.arkivo.gradle.DownloadVerifiedFile

sourceSets.named("tier2Test") {
    compileClasspath += sourceSets.test.get().output
    runtimeClasspath += sourceSets.test.get().output
}

dependencies {
    "tier2TestImplementation"(project(":arkivo-archive-zip"))
    "tier2TestImplementation"("org.apache.commons:commons-compress:1.28.0")
}

tasks.named<Test>("tier2Test") {
    dependsOn(":arkivo-all:prepareJSZipTestCorpus", ":arkivo-all:prepareZip4jTestCorpus")
    for (corpus in listOf("jszip", "zip4j")) {
        val manifestFile = rootProject.file("gradle/test-data/$corpus.properties")
        val manifest = Properties().apply { manifestFile.inputStream().use(::load) }
        val directory = rootProject.layout.buildDirectory.dir("test-data/$corpus/${manifest.getProperty("version")}")
        inputs.file(manifestFile)
        inputs.dir(directory)
        systemProperty("arkivo.$corpus.testDataDirectory", directory.get().asFile.absolutePath)
    }
}

tasks.withType<Jar>().configureEach {
    from("NOTICE") { into("META-INF") }
    from("UPSTREAM.properties") { into("META-INF/chardetng") }
    from(rootProject.file("LICENSES/Chardetng-MIT.txt")) { into("META-INF/LICENSES") }
}

val kalaManifestFile = rootProject.file("gradle/test-data/kala-encoding-detector.properties")
val kalaManifest = Properties().apply { kalaManifestFile.inputStream().use(::load) }
val kalaSha256 = kalaManifest.getProperty("archiveSha256")
val testDataCacheDirectory = rootProject.layout.dir(rootProject.providers.provider {
    rootProject.file(rootProject.providers.gradleProperty("arkivo.testDataCacheDirectory").orNull
        ?: ".arkivo-cache/test-data")
})
val downloadKalaModels = tasks.register<DownloadVerifiedFile>("downloadKalaModels") {
    group = "verification"
    description = "Downloads the pinned Kala artifact for metadata scoring comparisons."
    sourceUrl.set(kalaManifest.getProperty("archiveUrl"))
    expectedSha256.set(kalaSha256)
    expectedSize.set(kalaManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(testDataCacheDirectory.map {
        it.file("downloads/sha256/$kalaSha256/${kalaManifest.getProperty("archiveName")}")
    })
}
val prepareKalaModels = tasks.register<Sync>("prepareKalaModels") {
    description = "Extracts statistical models without adding Kala classes to the test classpath."
    from(downloadKalaModels.flatMap { it.destination }.map { zipTree(it.asFile) }) {
        include("kala/encdet/internal/models.bin", "kala/encdet/internal/idf.bin")
        includeEmptyDirs = false
    }
    from(kalaManifestFile) { into("kala"); rename { "UPSTREAM.properties" } }
    from(rootProject.file("LICENSES/Chardet-0BSD.txt")) { into("kala") }
    into(layout.buildDirectory.dir("generated/kala-models"))
}
sourceSets.named("tier2Test") {
    resources.srcDir(prepareKalaModels)
}
