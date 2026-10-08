/*
 * Copyright (c) 2026 Glavo
 * SPDX-License-Identifier: MPL-2.0
 */

import org.glavo.arkivo.gradle.DownloadVerifiedFile
import org.gradle.api.file.RelativePath
import java.util.Properties

dependencies {
    api(project(":arkivo-codec"))
    implementation(project(":arkivo-base"))
    implementation(project(":arkivo-codec-lzma"))
    testImplementation("org.tukaani:xz:1.12")
}

val xzTestDataManifest = Properties().apply {
    rootProject.file("gradle/test-data/xz.properties").inputStream().use(::load)
}
val xzTestDataDirectory = rootProject.layout.buildDirectory.dir(
    "test-data/xz/${xzTestDataManifest.getProperty("version")}"
)

tasks.named<Test>("tier2Test") {
    group = "verification"
    description = "Runs lzip tests against the pinned official XZ Utils decoder corpus."
    dependsOn(":arkivo-codec-xz:prepareXZTestCorpus")
    shouldRunAfter(tasks.test)
    inputs.dir(xzTestDataDirectory)
    systemProperty("arkivo.lzip.testDataDirectory", xzTestDataDirectory.get().asFile.absolutePath)
}

val lzipManifestFile = rootProject.file("gradle/test-data/lzip.properties")
val lzipManifest = Properties().apply { lzipManifestFile.inputStream().use(::load) }
val lzipArchiveRoot = lzipManifest.getProperty("archiveRoot")
val lzipArchiveSha256 = lzipManifest.getProperty("archiveSha256")
val lzipDataDirectory = rootProject.layout.buildDirectory.dir("test-data/lzip/${lzipManifest.getProperty("version")}")
val testDataCacheDirectory = rootProject.layout.dir(rootProject.providers.provider {
    rootProject.file(rootProject.providers.gradleProperty("arkivo.testDataCacheDirectory").orNull
        ?: ".arkivo-cache/test-data")
})

val downloadLzipTestSources = tasks.register<DownloadVerifiedFile>("downloadLzipTestSources") {
    group = "verification"
    description = "Downloads and verifies the pinned lzip source release."
    sourceUrl.set(lzipManifest.getProperty("archiveUrl"))
    expectedSha256.set(lzipArchiveSha256)
    expectedSize.set(lzipManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(testDataCacheDirectory.map {
        it.file("downloads/sha256/$lzipArchiveSha256/${lzipManifest.getProperty("archiveName")}")
    })
}

val prepareLzipTestCorpus = tasks.register<Sync>("prepareLzipTestCorpus") {
    group = "verification"
    description = "Extracts lzip reference members, plaintext, tests, and license notices."
    dependsOn(downloadLzipTestSources)
    from(downloadLzipTestSources.flatMap { it.destination }.map { tarTree(resources.gzip(it.asFile)) }) {
        include("$lzipArchiveRoot/COPYING", "$lzipArchiveRoot/README", "$lzipArchiveRoot/main.cc",
            "$lzipArchiveRoot/testsuite/**")
        eachFile {
            val segments = relativePath.segments
            require(segments.size >= 2 && segments[0] == lzipArchiveRoot) {
                "Unexpected lzip source archive path: $relativePath"
            }
            relativePath = RelativePath(relativePath.isFile, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(lzipManifestFile) { rename { "UPSTREAM.properties" } }
    into(lzipDataDirectory)
}

tasks.named<Test>("tier2Test") {
    description = "Runs lzip tests against the pinned lzip and XZ Utils corpora."
    dependsOn(prepareLzipTestCorpus)
    inputs.dir(lzipDataDirectory)
    systemProperty("arkivo.lzip.referenceDirectory", lzipDataDirectory.get().asFile.absolutePath)
}
