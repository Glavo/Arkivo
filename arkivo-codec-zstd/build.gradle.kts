import org.glavo.arkivo.gradle.DownloadVerifiedFile
import org.gradle.api.file.RelativePath
import java.util.Properties

dependencies {
    api(project(":arkivo-codec"))
    implementation(project(":arkivo-base"))
    implementation(project(":arkivo-checksum"))
    implementation(project(":arkivo-checksum-xxhash"))
    testImplementation("io.airlift:aircompressor:2.0.3")
    testImplementation("com.github.luben:zstd-jni:1.5.7-9")
}

val zstdTestDataManifestFile = rootProject.file("gradle/test-data/zstd.properties")
val zstdTestDataManifest = Properties().apply {
    zstdTestDataManifestFile.inputStream().use(::load)
}
val zstdTestDataVersion = zstdTestDataManifest.getProperty("version")
val zstdTestDataArchiveRoot = zstdTestDataManifest.getProperty("archiveRoot")
val zstdTestDataArchiveName = zstdTestDataManifest.getProperty("archiveName")
val zstdTestDataArchiveSha256 = zstdTestDataManifest.getProperty("archiveSha256")
val zstdTestDataArchiveSize = zstdTestDataManifest.getProperty("archiveSize").toLong()
val testDataCacheDirectory = rootProject.layout.dir(rootProject.providers.provider {
    rootProject.file(
        rootProject.providers.gradleProperty("arkivo.testDataCacheDirectory").orNull
            ?: ".arkivo-cache/test-data"
    )
})
val zstdTestDataArchive = rootProject.layout.file(testDataCacheDirectory.map { directory ->
    directory.file("downloads/sha256/$zstdTestDataArchiveSha256/$zstdTestDataArchiveName").asFile
})
val zstdTestDataDirectory = rootProject.layout.buildDirectory.dir("test-data/zstd/$zstdTestDataVersion")

val downloadZstdTestSources = tasks.register<DownloadVerifiedFile>("downloadZstdTestSources") {
    group = "verification"
    description = "Downloads and verifies the pinned official Zstandard source release."
    sourceUrl.set(zstdTestDataManifest.getProperty("archiveUrl"))
    expectedSha256.set(zstdTestDataArchiveSha256)
    expectedSize.set(zstdTestDataArchiveSize)
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(zstdTestDataArchive)
}

val prepareZstdTestCorpus = tasks.register<Sync>("prepareZstdTestCorpus") {
    group = "verification"
    description = "Extracts the pinned official Zstandard golden test corpus."
    dependsOn(downloadZstdTestSources)

    from(downloadZstdTestSources.flatMap { it.destination }.map { archive ->
        tarTree(resources.gzip(archive.asFile))
    }) {
        include(
            "$zstdTestDataArchiveRoot/LICENSE",
            "$zstdTestDataArchiveRoot/tests/golden-compression/**",
            "$zstdTestDataArchiveRoot/tests/golden-decompression/**",
            "$zstdTestDataArchiveRoot/tests/golden-decompression-errors/**",
            "$zstdTestDataArchiveRoot/tests/golden-dictionaries/**",
            "$zstdTestDataArchiveRoot/tests/dict-files/**"
        )
        eachFile {
            val segments = relativePath.segments
            require(segments.size >= 2 && segments[0] == zstdTestDataArchiveRoot) {
                "Unexpected Zstandard source archive path: $relativePath"
            }
            relativePath = RelativePath(relativePath.isFile, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(zstdTestDataManifestFile) {
        rename { "UPSTREAM.properties" }
    }
    into(zstdTestDataDirectory)
}

tasks.named<Test>("tier2Test") {
    group = "verification"
    description = "Runs extended Zstandard interoperability tests and the pinned official golden corpus."
    dependsOn(prepareZstdTestCorpus)
    shouldRunAfter(tasks.test)
    inputs.dir(zstdTestDataDirectory)
    systemProperty("arkivo.zstd.testDataDirectory", zstdTestDataDirectory.get().asFile.absolutePath)
}

val klauspostManifestFile = rootProject.file("gradle/test-data/klauspost-compress.properties")
val klauspostManifest = Properties().apply {
    klauspostManifestFile.inputStream().use(::load)
}
val klauspostVersion = klauspostManifest.getProperty("version")
val klauspostRoot = klauspostManifest.getProperty("archiveRoot")
val klauspostSha256 = klauspostManifest.getProperty("archiveSha256")
val klauspostArchive = rootProject.layout.file(testDataCacheDirectory.map { directory ->
    directory.file("downloads/sha256/$klauspostSha256/${klauspostManifest.getProperty("archiveName")}").asFile
})
val klauspostTestDataDirectory = rootProject.layout.buildDirectory.dir("test-data/klauspost-compress/$klauspostVersion")

val downloadKlauspostTestSources = tasks.register<DownloadVerifiedFile>("downloadKlauspostTestSources") {
    group = "verification"
    description = "Downloads and verifies the pinned klauspost/compress source release."
    sourceUrl.set(klauspostManifest.getProperty("archiveUrl"))
    expectedSha256.set(klauspostSha256)
    expectedSize.set(klauspostManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(klauspostArchive)
}

val prepareKlauspostTestCorpus = tasks.register<Sync>("prepareKlauspostTestCorpus") {
    group = "verification"
    description = "Extracts klauspost Zstandard decoder and dictionary regression fixtures with their reference tests."
    dependsOn(downloadKlauspostTestSources)
    from(downloadKlauspostTestSources.flatMap { it.destination }.map { archive ->
        tarTree(resources.gzip(archive.asFile))
    }) {
        include("$klauspostRoot/LICENSE", "$klauspostRoot/zstd/*_test.go")
        include("$klauspostRoot/zstd/testdata/bad.zip", "$klauspostRoot/zstd/testdata/good.zip",
                "$klauspostRoot/zstd/testdata/decoder.zip", "$klauspostRoot/zstd/testdata/decode-regression.zip",
                "$klauspostRoot/zstd/testdata/dict-tests-small.zip", "$klauspostRoot/zstd/testdata/delta/**")
        eachFile {
            val segments = relativePath.segments
            require(segments.size > 1 && segments[0] == klauspostRoot) {
                "Unexpected klauspost source archive path: $relativePath"
            }
            relativePath = RelativePath(relativePath.isFile, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(klauspostManifestFile) { rename { "UPSTREAM.properties" } }
    into(klauspostTestDataDirectory)
}

tasks.named<Test>("tier2Test") {
    dependsOn(prepareKlauspostTestCorpus)
    inputs.dir(klauspostTestDataDirectory)
    systemProperty("arkivo.klauspost.testDataDirectory", klauspostTestDataDirectory.get().asFile.absolutePath)
}
