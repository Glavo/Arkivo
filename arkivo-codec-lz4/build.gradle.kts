/*
 * Copyright (c) 2026 Glavo
 * SPDX-License-Identifier: MPL-2.0
 */

import java.nio.file.Files
import java.util.Properties
import org.glavo.arkivo.gradle.DownloadVerifiedFile
import org.gradle.api.file.RelativePath

dependencies {
    api(project(":arkivo-codec"))
    implementation(project(":arkivo-base"))
    testImplementation("org.lz4:lz4-pure-java:1.8.0")
}

val lz4JavaTestDataManifestFile = rootProject.file("gradle/test-data/lz4-java.properties")
val lz4JavaTestDataManifest = Properties().apply {
    lz4JavaTestDataManifestFile.inputStream().use(::load)
}
val lz4JavaTestDataVersion = lz4JavaTestDataManifest.getProperty("version")
val lz4JavaTestDataArchiveRoot = lz4JavaTestDataManifest.getProperty("archiveRoot")
val lz4JavaTestDataArchiveName = lz4JavaTestDataManifest.getProperty("archiveName")
val lz4JavaTestDataArchiveSha256 = lz4JavaTestDataManifest.getProperty("archiveSha256")
val lz4JavaTestDataArchiveSize = lz4JavaTestDataManifest.getProperty("archiveSize").toLong()
val lz4JavaTestResourceFileCount = lz4JavaTestDataManifest.getProperty("resourceFileCount").toLong()
val lz4JavaTestResourceTotalSize = lz4JavaTestDataManifest.getProperty("resourceTotalSize").toLong()
val testDataCacheDirectory = rootProject.layout.dir(rootProject.providers.provider {
    rootProject.file(
        rootProject.providers.gradleProperty("arkivo.testDataCacheDirectory").orNull
            ?: ".arkivo-cache/test-data"
    )
})
val lz4JavaTestDataArchive = rootProject.layout.file(testDataCacheDirectory.map { directory ->
    directory.file(
        "downloads/sha256/$lz4JavaTestDataArchiveSha256/$lz4JavaTestDataArchiveName"
    ).asFile
})
val lz4JavaTestDataDirectory = rootProject.layout.buildDirectory.dir(
    "test-data/lz4-java/$lz4JavaTestDataVersion"
)

val downloadLZ4JavaTestSources = tasks.register<DownloadVerifiedFile>("downloadLZ4JavaTestSources") {
    group = "verification"
    description = "Downloads and verifies the pinned lz4-java source release."
    sourceUrl.set(lz4JavaTestDataManifest.getProperty("archiveUrl"))
    expectedSha256.set(lz4JavaTestDataArchiveSha256)
    expectedSize.set(lz4JavaTestDataArchiveSize)
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(lz4JavaTestDataArchive)
}

val prepareLZ4JavaTestCorpus = tasks.register<Sync>("prepareLZ4JavaTestCorpus") {
    group = "verification"
    description = "Extracts the pinned lz4-java regression source and Calgary test corpus."
    dependsOn(downloadLZ4JavaTestSources)
    inputs.property("resourceFileCount", lz4JavaTestResourceFileCount)
    inputs.property("resourceTotalSize", lz4JavaTestResourceTotalSize)

    from(downloadLZ4JavaTestSources.flatMap { it.destination }.map { archive ->
        tarTree(resources.gzip(archive.asFile))
    }) {
        include(
            "$lz4JavaTestDataArchiveRoot/LICENSE.txt",
            "$lz4JavaTestDataArchiveRoot/src/test/net/jpountz/lz4/LZ4Test.java",
            "$lz4JavaTestDataArchiveRoot/src/test-resources/**"
        )
        eachFile {
            val segments = relativePath.segments
            require(segments.size >= 2 && segments[0] == lz4JavaTestDataArchiveRoot) {
                "Unexpected lz4-java source archive path: $relativePath"
            }
            relativePath = RelativePath(relativePath.isFile, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(lz4JavaTestDataManifestFile) {
        rename { "UPSTREAM.properties" }
    }
    into(lz4JavaTestDataDirectory)

    doLast {
        val resourceRoot = lz4JavaTestDataDirectory.get()
            .dir("src/test-resources")
            .asFile
            .toPath()
        var actualFileCount = 0L
        var actualTotalSize = 0L
        Files.walk(resourceRoot).use { paths ->
            paths.filter(Files::isRegularFile).forEach { path ->
                actualFileCount++
                actualTotalSize = Math.addExact(actualTotalSize, Files.size(path))
            }
        }
        check(actualFileCount == lz4JavaTestResourceFileCount) {
            "lz4-java resource count is $actualFileCount instead of $lz4JavaTestResourceFileCount"
        }
        check(actualTotalSize == lz4JavaTestResourceTotalSize) {
            "lz4-java resource size is $actualTotalSize instead of $lz4JavaTestResourceTotalSize"
        }
    }
}

tasks.named<Test>("tier2Test") {
    group = "verification"
    description = "Runs compatibility tests ported from lz4-java against its pinned Calgary corpus."
    dependsOn(prepareLZ4JavaTestCorpus)
    shouldRunAfter(tasks.test)
    inputs.dir(lz4JavaTestDataDirectory)
    systemProperty(
        "arkivo.lz4Java.testDataDirectory",
        lz4JavaTestDataDirectory.get().asFile.absolutePath
    )
}
