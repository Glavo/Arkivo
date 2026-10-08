import java.util.Properties
import org.glavo.arkivo.gradle.DownloadVerifiedFile
import org.gradle.api.file.RelativePath

dependencies {
    api(project(":arkivo-codec"))
    testImplementation("org.apache.commons:commons-compress:1.28.0")
}

val ncompressManifestFile = rootProject.file("gradle/test-data/ncompress.properties")
val ncompressManifest = Properties().apply {
    ncompressManifestFile.inputStream().use(::load)
}
val ncompressVersion = ncompressManifest.getProperty("version")
val ncompressRoot = ncompressManifest.getProperty("archiveRoot")
val ncompressSha256 = ncompressManifest.getProperty("archiveSha256")
val testDataCacheDirectory = rootProject.layout.dir(rootProject.providers.provider {
    rootProject.file(
        rootProject.providers.gradleProperty("arkivo.testDataCacheDirectory").orNull
            ?: ".arkivo-cache/test-data"
    )
})
val ncompressArchive = rootProject.layout.file(testDataCacheDirectory.map { directory ->
    directory.file("downloads/sha256/$ncompressSha256/${ncompressManifest.getProperty("archiveName")}").asFile
})
val ncompressTestDataDirectory = rootProject.layout.buildDirectory.dir("test-data/ncompress/$ncompressVersion")

val downloadNcompressTestSources = tasks.register<DownloadVerifiedFile>("downloadNcompressTestSources") {
    group = "verification"
    description = "Downloads and verifies the pinned ncompress reference sources."
    sourceUrl.set(ncompressManifest.getProperty("archiveUrl"))
    expectedSha256.set(ncompressSha256)
    expectedSize.set(ncompressManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(ncompressArchive)
}

val prepareNcompressTestCorpus = tasks.register<Sync>("prepareNcompressTestCorpus") {
    group = "verification"
    description = "Extracts ncompress source input and its upstream regression and license files."
    dependsOn(downloadNcompressTestSources)
    from(downloadNcompressTestSources.flatMap { it.destination }.map { archive ->
        tarTree(resources.gzip(archive.asFile))
    }) {
        include("$ncompressRoot/compress.c", "$ncompressRoot/patchlevel.h", "$ncompressRoot/tests/runtests.sh",
                "$ncompressRoot/LICENSE.txt", "$ncompressRoot/UNLICENSE")
        eachFile {
            val segments = relativePath.segments
            require(segments.size > 1 && segments[0] == ncompressRoot) {
                "Unexpected ncompress source archive path: $relativePath"
            }
            relativePath = RelativePath(relativePath.isFile, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(ncompressManifestFile) { rename { "UPSTREAM.properties" } }
    into(ncompressTestDataDirectory)
}

tasks.named<Test>("tier2Test") {
    dependsOn(prepareNcompressTestCorpus)
    inputs.dir(ncompressTestDataDirectory)
    systemProperty("arkivo.ncompress.testDataDirectory", ncompressTestDataDirectory.get().asFile.absolutePath)
    inputs.property("ncompressExecutable", providers.environmentVariable("ARKIVO_NCOMPRESS_EXECUTABLE").orElse(""))
    inputs.property("requireNcompress", providers.environmentVariable("ARKIVO_REQUIRE_NCOMPRESS").orElse("false"))
}
