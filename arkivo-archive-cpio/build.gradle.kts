/*
 * Copyright (c) 2026 Glavo
 * SPDX-License-Identifier: MPL-2.0
 */

import java.util.Properties
import org.gradle.api.file.RelativePath
import org.glavo.arkivo.gradle.DownloadVerifiedFile

dependencies {
    api(project(":arkivo-archive"))
    implementation(project(":arkivo-archive-codec"))
    implementation(project(":arkivo-base"))
    testImplementation("org.apache.commons:commons-compress:1.28.0")
}

val gnuCpioManifestFile = rootProject.file("gradle/test-data/gnu-cpio.properties")
val gnuCpioManifest = Properties().apply { gnuCpioManifestFile.inputStream().use(::load) }
val gnuCpioRoot = gnuCpioManifest.getProperty("archiveRoot")
val gnuCpioHash = gnuCpioManifest.getProperty("archiveSha256")
val gnuCpioDirectory = rootProject.layout.buildDirectory.dir("test-data/gnu-cpio/${gnuCpioManifest.getProperty("version")}")
val gnuCpioCache = rootProject.layout.dir(rootProject.providers.provider {
    rootProject.file(rootProject.providers.gradleProperty("arkivo.testDataCacheDirectory").orNull
        ?: ".arkivo-cache/test-data")
})
val downloadGnuCpioTestSources = tasks.register<DownloadVerifiedFile>("downloadGnuCpioTestSources") {
    group = "verification"
    description = "Downloads and verifies GNU cpio's pinned regression sources."
    sourceUrl.set(gnuCpioManifest.getProperty("archiveUrl"))
    expectedSha256.set(gnuCpioHash)
    expectedSize.set(gnuCpioManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(gnuCpioCache)
    cacheMarker.set(gnuCpioCache.map { it.file(".arkivo-test-data-cache") })
    destination.set(gnuCpioCache.map {
        it.file("downloads/sha256/$gnuCpioHash/${gnuCpioManifest.getProperty("archiveName")}")
    })
}
val prepareGnuCpioTestCorpus = tasks.register<Sync>("prepareGnuCpioTestCorpus") {
    group = "verification"
    description = "Extracts GNU cpio's embedded symlink reproducer and input-generation scenarios."
    dependsOn(downloadGnuCpioTestSources)
    from(downloadGnuCpioTestSources.flatMap { it.destination }.map { tarTree(resources.gzip(it.asFile)) }) {
        include("$gnuCpioRoot/COPYING", "$gnuCpioRoot/tests/symlink-bad-length.at",
            "$gnuCpioRoot/tests/symlink-long.at", "$gnuCpioRoot/tests/inout.at", "$gnuCpioRoot/tests/genfile.c")
        eachFile {
            val segments = relativePath.segments
            require(segments.size > 1 && segments[0] == gnuCpioRoot) { "Unexpected GNU cpio archive path: $relativePath" }
            relativePath = RelativePath(relativePath.isFile, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(gnuCpioManifestFile) { rename { "UPSTREAM.properties" } }
    into(gnuCpioDirectory)
}
tasks.named<Test>("tier2Test") {
    dependsOn(prepareGnuCpioTestCorpus)
    inputs.dir(gnuCpioDirectory)
    systemProperty("arkivo.gnuCpio.testDataDirectory", gnuCpioDirectory.get().asFile.absolutePath)
}
