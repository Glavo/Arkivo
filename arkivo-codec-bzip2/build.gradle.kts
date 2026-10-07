import org.glavo.arkivo.gradle.DownloadVerifiedFile
import org.gradle.api.file.RelativePath
import java.util.Properties

dependencies {
    api(project(":arkivo-codec"))
    implementation(project(":arkivo-checksum"))
    testImplementation("org.apache.commons:commons-compress:1.28.0")
}

val bzip2TestDataManifestFile = rootProject.file("gradle/test-data/bzip2.properties")
val bzip2TestDataManifest = Properties().apply {
    bzip2TestDataManifestFile.inputStream().use(::load)
}
val bzip2TestDataVersion = bzip2TestDataManifest.getProperty("version")
val bzip2TestDataArchiveRoot = bzip2TestDataManifest.getProperty("archiveRoot")
val bzip2TestDataArchiveName = bzip2TestDataManifest.getProperty("archiveName")
val bzip2TestDataArchiveSha256 = bzip2TestDataManifest.getProperty("archiveSha256")
val bzip2TestDataArchiveSize = bzip2TestDataManifest.getProperty("archiveSize").toLong()
val testDataCacheDirectory = rootProject.layout.dir(rootProject.providers.provider {
    rootProject.file(
        rootProject.providers.gradleProperty("arkivo.testDataCacheDirectory").orNull
            ?: ".arkivo-cache/test-data"
    )
})
val bzip2TestDataArchive = rootProject.layout.file(testDataCacheDirectory.map { directory ->
    directory.file("downloads/sha256/$bzip2TestDataArchiveSha256/$bzip2TestDataArchiveName").asFile
})
val bzip2TestDataDirectory = rootProject.layout.buildDirectory.dir("test-data/bzip2/$bzip2TestDataVersion")

val downloadBZip2TestSources = tasks.register<DownloadVerifiedFile>("downloadBZip2TestSources") {
    group = "verification"
    description = "Downloads and verifies the pinned official bzip2 source release."
    sourceUrl.set(bzip2TestDataManifest.getProperty("archiveUrl"))
    expectedSha256.set(bzip2TestDataArchiveSha256)
    expectedSize.set(bzip2TestDataArchiveSize)
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(bzip2TestDataArchive)
}

val prepareBZip2TestCorpus = tasks.register<Sync>("prepareBZip2TestCorpus") {
    group = "verification"
    description = "Extracts the pinned official bzip2 reference samples."
    dependsOn(downloadBZip2TestSources)

    from(downloadBZip2TestSources.flatMap { it.destination }.map { archive ->
        tarTree(resources.gzip(archive.asFile))
    }) {
        include(
            "$bzip2TestDataArchiveRoot/LICENSE",
            "$bzip2TestDataArchiveRoot/README",
            "$bzip2TestDataArchiveRoot/sample*.bz2",
            "$bzip2TestDataArchiveRoot/sample*.ref"
        )
        eachFile {
            val segments = relativePath.segments
            require(segments.size >= 2 && segments[0] == bzip2TestDataArchiveRoot) {
                "Unexpected bzip2 source archive path: $relativePath"
            }
            relativePath = RelativePath(relativePath.isFile, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(bzip2TestDataManifestFile) {
        rename { "UPSTREAM.properties" }
    }
    into(bzip2TestDataDirectory)
}

tasks.named<Test>("tier2Test") {
    group = "verification"
    description = "Runs BZip2 tests against the pinned official reference samples."
    dependsOn(prepareBZip2TestCorpus)
    shouldRunAfter(tasks.test)
    inputs.dir(bzip2TestDataDirectory)
    systemProperty("arkivo.bzip2.testDataDirectory", bzip2TestDataDirectory.get().asFile.absolutePath)
}

val bzip2TestsManifestFile = rootProject.file("gradle/test-data/bzip2-tests.properties")
val bzip2TestsManifest = Properties().apply {
    bzip2TestsManifestFile.inputStream().use(::load)
}
val bzip2TestsRevision = bzip2TestsManifest.getProperty("revision")
val bzip2TestsRoot = bzip2TestsManifest.getProperty("archiveRoot")
val bzip2TestsSha256 = bzip2TestsManifest.getProperty("archiveSha256")
val bzip2TestsDirectory = rootProject.layout.buildDirectory.dir("test-data/bzip2-tests/$bzip2TestsRevision")

val downloadBZip2CompatibilitySources = tasks.register<DownloadVerifiedFile>("downloadBZip2CompatibilitySources") {
    group = "verification"
    description = "Downloads the pinned bzip2-tests compatibility corpus from its commit-identical mirror."
    sourceUrl.set(bzip2TestsManifest.getProperty("archiveUrl"))
    expectedSha256.set(bzip2TestsSha256)
    expectedSize.set(bzip2TestsManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(testDataCacheDirectory.map {
        it.file("downloads/sha256/$bzip2TestsSha256/${bzip2TestsManifest.getProperty("archiveName")}")
    })
}

val prepareBZip2CompatibilityCorpus = tasks.register<Sync>("prepareBZip2CompatibilityCorpus") {
    group = "verification"
    description = "Extracts bzip2-tests samples, reference digests, and per-source license notices."
    dependsOn(downloadBZip2CompatibilitySources)
    from(downloadBZip2CompatibilitySources.flatMap { it.destination }.map { archive ->
        tarTree(resources.gzip(archive.asFile))
    }) {
        include("$bzip2TestsRoot/**")
        eachFile {
            val segments = relativePath.segments
            require(segments.size >= 2 && segments[0] == bzip2TestsRoot) {
                "Unexpected bzip2-tests archive path: $relativePath"
            }
            relativePath = RelativePath(relativePath.isFile, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(bzip2TestsManifestFile) {
        rename { "UPSTREAM.properties" }
    }
    into(bzip2TestsDirectory)
}

tasks.named<Test>("tier2Test") {
    dependsOn(prepareBZip2CompatibilityCorpus)
    inputs.dir(bzip2TestsDirectory)
    systemProperty("arkivo.bzip2.compatibilityDirectory", bzip2TestsDirectory.get().asFile.absolutePath)
}
