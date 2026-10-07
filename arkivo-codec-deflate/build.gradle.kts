import org.glavo.arkivo.gradle.DownloadVerifiedFile
import org.gradle.api.file.RelativePath
import java.util.Properties

dependencies {
    api(project(":arkivo-codec"))
    implementation(project(":arkivo-base"))
    testImplementation("com.jcraft:jzlib:1.1.3")
    testImplementation("org.apache.commons:commons-compress:1.28.0")
}

// Tier 2 reuses the fixed-schedule comparison helper from the ordinary test source set.
sourceSets.named("tier2Test") {
    compileClasspath += sourceSets.test.get().output
    runtimeClasspath += sourceSets.test.get().output
}

val zlibManifestFile = rootProject.file("gradle/test-data/zlib.properties")
val zlibManifest = Properties().apply {
    zlibManifestFile.inputStream().use(::load)
}
val zlibVersion = zlibManifest.getProperty("version")
val zlibRoot = zlibManifest.getProperty("archiveRoot")
val zlibSha256 = zlibManifest.getProperty("archiveSha256")
val testDataCacheDirectory = rootProject.layout.dir(rootProject.providers.provider {
    rootProject.file(
        rootProject.providers.gradleProperty("arkivo.testDataCacheDirectory").orNull
            ?: ".arkivo-cache/test-data"
    )
})
val zlibTestDataDirectory = rootProject.layout.buildDirectory.dir("test-data/zlib/$zlibVersion")

val downloadZlibTestSources = tasks.register<DownloadVerifiedFile>("downloadZlibTestSources") {
    group = "verification"
    description = "Downloads and verifies the pinned zlib source release."
    sourceUrl.set(zlibManifest.getProperty("archiveUrl"))
    expectedSha256.set(zlibSha256)
    expectedSize.set(zlibManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(testDataCacheDirectory.map {
        it.file("downloads/sha256/$zlibSha256/${zlibManifest.getProperty("archiveName")}")
    })
}

val prepareZlibTestCorpus = tasks.register<Sync>("prepareZlibTestCorpus") {
    group = "verification"
    description = "Extracts the pinned zlib inflate coverage vectors and their license."
    dependsOn(downloadZlibTestSources)
    from(downloadZlibTestSources.flatMap { it.destination }.map { archive ->
        tarTree(resources.gzip(archive.asFile))
    }) {
        include("$zlibRoot/LICENSE", "$zlibRoot/zlib.h", "$zlibRoot/test/infcover.c")
        eachFile {
            val segments = relativePath.segments
            require(segments.size >= 2 && segments[0] == zlibRoot) {
                "Unexpected zlib source archive path: $relativePath"
            }
            relativePath = RelativePath(relativePath.isFile, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(zlibManifestFile) {
        rename { "UPSTREAM.properties" }
    }
    into(zlibTestDataDirectory)
}

tasks.named<Test>("tier2Test") {
    dependsOn(prepareZlibTestCorpus)
    inputs.dir(zlibTestDataDirectory)
    systemProperty("arkivo.zlib.testDataDirectory", zlibTestDataDirectory.get().asFile.absolutePath)
}

val zlibNgManifestFile = rootProject.file("gradle/test-data/zlib-ng.properties")
val zlibNgManifest = Properties().apply {
    zlibNgManifestFile.inputStream().use(::load)
}
val zlibNgVersion = zlibNgManifest.getProperty("version")
val zlibNgRoot = zlibNgManifest.getProperty("archiveRoot")
val zlibNgSha256 = zlibNgManifest.getProperty("archiveSha256")
val zlibNgTestDataDirectory = rootProject.layout.buildDirectory.dir("test-data/zlib-ng/$zlibNgVersion")

val downloadZlibNgTestSources = tasks.register<DownloadVerifiedFile>("downloadZlibNgTestSources") {
    group = "verification"
    description = "Downloads and verifies the pinned zlib-ng source release."
    sourceUrl.set(zlibNgManifest.getProperty("archiveUrl"))
    expectedSha256.set(zlibNgSha256)
    expectedSize.set(zlibNgManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(testDataCacheDirectory.map {
        it.file("downloads/sha256/$zlibNgSha256/${zlibNgManifest.getProperty("archiveName")}")
    })
}

val prepareZlibNgTestCorpus = tasks.register<Sync>("prepareZlibNgTestCorpus") {
    group = "verification"
    description = "Extracts zlib-ng issue regressions, CVE samples, and compression test data."
    dependsOn(downloadZlibNgTestSources)
    from(downloadZlibNgTestSources.flatMap { it.destination }.map { archive ->
        tarTree(resources.gzip(archive.asFile))
    }) {
        include(
            "$zlibNgRoot/LICENSE.md", "$zlibNgRoot/test/CVE-*/**", "$zlibNgRoot/test/GH-*/**",
            "$zlibNgRoot/test/data/**", "$zlibNgRoot/test/cmake/**",
            "$zlibNgRoot/test/test_inflate_adler32.cc",
            "$zlibNgRoot/test/test_deflate_quick_bi_valid.cc",
            "$zlibNgRoot/test/test_deflate_quick_block_open.cc"
        )
        eachFile {
            val segments = relativePath.segments
            require(segments.size >= 2 && segments[0] == zlibNgRoot) {
                "Unexpected zlib-ng source archive path: $relativePath"
            }
            relativePath = RelativePath(relativePath.isFile, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(zlibNgManifestFile) {
        rename { "UPSTREAM.properties" }
    }
    into(zlibNgTestDataDirectory)
}

tasks.named<Test>("tier2Test") {
    dependsOn(prepareZlibNgTestCorpus)
    inputs.dir(zlibNgTestDataDirectory)
    systemProperty("arkivo.zlibNg.testDataDirectory", zlibNgTestDataDirectory.get().asFile.absolutePath)
}
