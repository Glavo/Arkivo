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

val libdeflateManifestFile = rootProject.file("gradle/test-data/libdeflate.properties")
val libdeflateManifest = Properties().apply {
    libdeflateManifestFile.inputStream().use(::load)
}
val libdeflateVersion = libdeflateManifest.getProperty("version")
val libdeflateRoot = libdeflateManifest.getProperty("archiveRoot")
val libdeflateSha256 = libdeflateManifest.getProperty("archiveSha256")
val libdeflateTestDataDirectory = rootProject.layout.buildDirectory.dir("test-data/libdeflate/$libdeflateVersion")

val downloadLibdeflateTestSources = tasks.register<DownloadVerifiedFile>("downloadLibdeflateTestSources") {
    group = "verification"
    description = "Downloads and verifies the pinned libdeflate regression sources."
    sourceUrl.set(libdeflateManifest.getProperty("archiveUrl"))
    expectedSha256.set(libdeflateSha256)
    expectedSize.set(libdeflateManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(testDataCacheDirectory.map {
        it.file("downloads/sha256/$libdeflateSha256/${libdeflateManifest.getProperty("archiveName")}")
    })
}

val prepareLibdeflateTestCorpus = tasks.register<Sync>("prepareLibdeflateTestCorpus") {
    group = "verification"
    description = "Extracts libdeflate's regression generators, embedded reproducer, and license."
    dependsOn(downloadLibdeflateTestSources)
    from(downloadLibdeflateTestSources.flatMap { it.destination }.map { archive ->
        tarTree(resources.gzip(archive.asFile))
    }) {
        include("$libdeflateRoot/COPYING", "$libdeflateRoot/programs/test_*.c")
        eachFile {
            val segments = relativePath.segments
            require(segments.size >= 2 && segments[0] == libdeflateRoot) {
                "Unexpected libdeflate source archive path: $relativePath"
            }
            relativePath = RelativePath(relativePath.isFile, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(libdeflateManifestFile) { rename { "UPSTREAM.properties" } }
    into(libdeflateTestDataDirectory)
}

tasks.named<Test>("tier2Test") {
    dependsOn(prepareLibdeflateTestCorpus)
    inputs.dir(libdeflateTestDataDirectory)
    systemProperty("arkivo.libdeflate.testDataDirectory", libdeflateTestDataDirectory.get().asFile.absolutePath)
}

val minizManifestFile = rootProject.file("gradle/test-data/miniz.properties")
val minizManifest = Properties().apply { minizManifestFile.inputStream().use(::load) }
val minizVersion = minizManifest.getProperty("version")
val minizRoot = minizManifest.getProperty("archiveRoot")
val minizSha256 = minizManifest.getProperty("archiveSha256")
val minizTestDataDirectory = rootProject.layout.buildDirectory.dir("test-data/miniz/$minizVersion")

val downloadMinizTestSources = tasks.register<DownloadVerifiedFile>("downloadMinizTestSources") {
    group = "verification"
    description = "Downloads and verifies miniz's pinned streaming regression sources."
    sourceUrl.set(minizManifest.getProperty("archiveUrl"))
    expectedSha256.set(minizSha256)
    expectedSize.set(minizManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(testDataCacheDirectory.map {
        it.file("downloads/sha256/$minizSha256/${minizManifest.getProperty("archiveName")}")
    })
}

val prepareMinizTestCorpus = tasks.register<Sync>("prepareMinizTestCorpus") {
    group = "verification"
    description = "Extracts miniz's small-buffer, flush, and large-buffer scenarios with their notices."
    dependsOn(downloadMinizTestSources)
    val corpusFiles = listOf("LICENSE", "tests/ossfuzz.sh", "tests/small_fuzzer.c",
        "tests/large_fuzzer.c", "tests/flush_fuzzer.c", "tests/compress_fuzzer.c")
    inputs.property("corpusFiles", corpusFiles)
    from(downloadMinizTestSources.flatMap { it.destination }.map { tarTree(resources.gzip(it.asFile)) }) {
        include(*corpusFiles.map { "$minizRoot/$it" }.toTypedArray())
        eachFile {
            val segments = relativePath.segments
            require(segments.size > 1 && segments[0] == minizRoot) { "Unexpected miniz archive path: $relativePath" }
            relativePath = RelativePath(relativePath.isFile, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(minizManifestFile) { rename { "UPSTREAM.properties" } }
    into(minizTestDataDirectory)
}

tasks.named<Test>("tier2Test") {
    dependsOn(prepareMinizTestCorpus)
    inputs.dir(minizTestDataDirectory)
    systemProperty("arkivo.miniz.testDataDirectory", minizTestDataDirectory.get().asFile.absolutePath)
}

val fflateManifestFile = rootProject.file("gradle/test-data/fflate.properties")
val fflateManifest = Properties().apply { fflateManifestFile.inputStream().use(::load) }
val fflateVersion = fflateManifest.getProperty("version")
val fflateRoot = fflateManifest.getProperty("archiveRoot")
val fflateSha256 = fflateManifest.getProperty("archiveSha256")
val fflateTestDataDirectory = rootProject.layout.buildDirectory.dir("test-data/fflate/$fflateVersion")

val downloadFflateReference = tasks.register<DownloadVerifiedFile>("downloadFflateReference") {
    group = "verification"
    description = "Downloads and verifies the pinned fflate JavaScript reference implementation."
    sourceUrl.set(fflateManifest.getProperty("archiveUrl"))
    expectedSha256.set(fflateSha256)
    expectedSize.set(fflateManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(testDataCacheDirectory.map {
        it.file("downloads/sha256/$fflateSha256/${fflateManifest.getProperty("archiveName")}")
    })
}

val prepareFflateReference = tasks.register<Sync>("prepareFflateReference") {
    group = "verification"
    description = "Extracts fflate's pure JavaScript CommonJS module, metadata, and license."
    dependsOn(downloadFflateReference)
    val referenceFiles = listOf("LICENSE", "package.json", "lib/browser.cjs", "lib/browser.d.cts")
    inputs.property("referenceFiles", referenceFiles)
    from(downloadFflateReference.flatMap { it.destination }.map { tarTree(resources.gzip(it.asFile)) }) {
        include(*referenceFiles.map { "$fflateRoot/$it" }.toTypedArray())
        eachFile {
            val segments = relativePath.segments
            require(segments.size > 1 && segments[0] == fflateRoot) { "Unexpected fflate archive path: $relativePath" }
            relativePath = RelativePath(relativePath.isFile, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(fflateManifestFile) { rename { "UPSTREAM.properties" } }
    into(fflateTestDataDirectory)
}

tasks.named<Test>("tier2Test") {
    dependsOn(prepareFflateReference)
    inputs.dir(fflateTestDataDirectory)
    inputs.property("nodeExecutable", providers.environmentVariable("ARKIVO_NODE_EXECUTABLE").orElse(""))
    inputs.property("requireNode", providers.environmentVariable("ARKIVO_REQUIRE_NODE").orElse("false"))
    systemProperty("arkivo.fflate.testDataDirectory", fflateTestDataDirectory.get().asFile.absolutePath)
}

val zopfliManifestFile = rootProject.file("gradle/test-data/zopfli.properties")
val zopfliManifest = Properties().apply { zopfliManifestFile.inputStream().use(::load) }
val zopfliRoot = zopfliManifest.getProperty("archiveRoot")
val zopfliSha256 = zopfliManifest.getProperty("archiveSha256")
val zopfliTestDataDirectory = rootProject.layout.buildDirectory.dir("test-data/zopfli/${zopfliManifest.getProperty("version")}")

val downloadZopfliReference = tasks.register<DownloadVerifiedFile>("downloadZopfliReference") {
    group = "verification"
    description = "Downloads and verifies the pinned Zopfli reference sources."
    sourceUrl.set(zopfliManifest.getProperty("archiveUrl"))
    expectedSha256.set(zopfliSha256)
    expectedSize.set(zopfliManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(testDataCacheDirectory.map {
        it.file("downloads/sha256/$zopfliSha256/${zopfliManifest.getProperty("archiveName")}")
    })
}

val prepareZopfliReference = tasks.register<Sync>("prepareZopfliReference") {
    group = "verification"
    description = "Extracts Zopfli's reference implementation, test inputs, and license."
    dependsOn(downloadZopfliReference)
    from(downloadZopfliReference.flatMap { it.destination }.map { tarTree(resources.gzip(it.asFile)) }) {
        include("$zopfliRoot/COPYING", "$zopfliRoot/src/zopfli/*.c", "$zopfliRoot/src/zopfli/*.h",
            "$zopfliRoot/go/zopfli/zopfli_test.go")
        eachFile {
            val segments = relativePath.segments
            require(segments.size > 1 && segments[0] == zopfliRoot) { "Unexpected Zopfli archive path: $relativePath" }
            relativePath = RelativePath(relativePath.isFile, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(zopfliManifestFile) { rename { "UPSTREAM.properties" } }
    into(zopfliTestDataDirectory)
}

tasks.named<Test>("tier2Test") {
    dependsOn(prepareZopfliReference)
    inputs.dir(zopfliTestDataDirectory)
    inputs.property("zopfliExecutable", providers.environmentVariable("ARKIVO_ZOPFLI_EXECUTABLE").orElse(""))
    inputs.property("requireZopfli", providers.environmentVariable("ARKIVO_REQUIRE_ZOPFLI").orElse("false"))
    systemProperty("arkivo.zopfli.testDataDirectory", zopfliTestDataDirectory.get().asFile.absolutePath)
}

val htslibManifestFile = rootProject.file("gradle/test-data/htslib.properties")
val htslibManifest = Properties().apply { htslibManifestFile.inputStream().use(::load) }
val htslibDirectory = rootProject.layout.buildDirectory.dir("test-data/htslib/${htslibManifest.getProperty("version")}")
val htslibDownloads = htslibManifest.getProperty("files").split(',').map { name ->
    val hash = htslibManifest.getProperty("$name.sha256")
    tasks.register<DownloadVerifiedFile>("downloadHtslib${name.replace('.', '-')}") {
        group = "verification"
        description = "Downloads and verifies HTSlib's $name reference file."
        val sourcePath = when {
            name == "LICENSE" -> name
            name.endsWith(".bam") -> "test/bgzf_boundaries/$name"
            else -> "test/$name"
        }
        sourceUrl.set("${htslibManifest.getProperty("baseUrl")}/$sourcePath")
        expectedSha256.set(hash)
        expectedSize.set(htslibManifest.getProperty("$name.size").toLong())
        offline.set(gradle.startParameter.isOffline)
        cacheRoot.set(testDataCacheDirectory)
        cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
        destination.set(testDataCacheDirectory.map { it.file("downloads/sha256/$hash/$name") })
    }
}
val prepareHtslibTestCorpus = tasks.register<Sync>("prepareHtslibTestCorpus") {
    group = "verification"
    description = "Collects HTSlib's BGZF boundary fixtures, index, and source notices."
    htslibDownloads.forEach { download -> from(download.flatMap { it.destination }) }
    from(htslibManifestFile) { rename { "UPSTREAM.properties" } }
    into(htslibDirectory)
}
tasks.named<Test>("tier2Test") {
    dependsOn(prepareHtslibTestCorpus)
    inputs.dir(htslibDirectory)
    systemProperty("arkivo.htslib.testDataDirectory", htslibDirectory.get().asFile.absolutePath)
}
