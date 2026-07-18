import org.glavo.arkivo.gradle.DownloadVerifiedFile
import org.gradle.api.file.RelativePath
import java.util.Properties

dependencies {
    api(project(":arkivo-codec"))
    implementation(project(":arkivo-base"))
    api(project(":arkivo-codec-lzma"))
    testImplementation("org.tukaani:xz:1.12")
}

val xzTestDataManifestFile = rootProject.file("gradle/test-data/xz.properties")
val xzTestDataManifest = Properties().apply {
    xzTestDataManifestFile.inputStream().use(::load)
}
val xzTestDataVersion = xzTestDataManifest.getProperty("version")
val xzTestDataArchiveRoot = xzTestDataManifest.getProperty("archiveRoot")
val xzTestDataArchiveName = xzTestDataManifest.getProperty("archiveName")
val xzTestDataArchiveSha256 = xzTestDataManifest.getProperty("archiveSha256")
val xzTestDataArchiveSize = xzTestDataManifest.getProperty("archiveSize").toLong()
val testDataCacheDirectory = rootProject.layout.dir(rootProject.providers.provider {
    rootProject.file(
        rootProject.providers.gradleProperty("arkivo.testDataCacheDirectory").orNull
            ?: ".arkivo-cache/test-data"
    )
})
val xzTestDataArchive = rootProject.layout.file(testDataCacheDirectory.map { directory ->
    directory.file("downloads/sha256/$xzTestDataArchiveSha256/$xzTestDataArchiveName").asFile
})
val xzTestDataDirectory = rootProject.layout.buildDirectory.dir("test-data/xz/$xzTestDataVersion")

val downloadXZTestSources = tasks.register<DownloadVerifiedFile>("downloadXZTestSources") {
    group = "verification"
    description = "Downloads and verifies the pinned official XZ Utils source release."
    sourceUrl.set(xzTestDataManifest.getProperty("archiveUrl"))
    expectedSha256.set(xzTestDataArchiveSha256)
    expectedSize.set(xzTestDataArchiveSize)
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(xzTestDataArchive)
}

val prepareXZTestCorpus = tasks.register<Sync>("prepareXZTestCorpus") {
    group = "verification"
    description = "Extracts the pinned official XZ and LZMA decoder corpus."
    dependsOn(downloadXZTestSources)

    from(downloadXZTestSources.flatMap { it.destination }.map { archive ->
        tarTree(resources.gzip(archive.asFile))
    }) {
        include(
            "$xzTestDataArchiveRoot/COPYING",
            "$xzTestDataArchiveRoot/tests/files/**"
        )
        eachFile {
            val segments = relativePath.segments
            require(segments.size >= 2 && segments[0] == xzTestDataArchiveRoot) {
                "Unexpected XZ Utils source archive path: $relativePath"
            }
            relativePath = RelativePath(relativePath.isFile, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(xzTestDataManifestFile) {
        rename { "UPSTREAM.properties" }
    }
    into(xzTestDataDirectory)
}

tasks.named<Test>("tier2Test") {
    group = "verification"
    description = "Runs XZ and LZMA tests against the pinned official decoder corpus."
    dependsOn(prepareXZTestCorpus)
    shouldRunAfter(tasks.test)
    systemProperty("arkivo.xz.testDataDirectory", xzTestDataDirectory.get().asFile.absolutePath)
}
