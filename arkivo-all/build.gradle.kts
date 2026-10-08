import java.lang.module.ModuleDescriptor
import java.lang.module.ModuleFinder
import java.util.Properties
import java.util.jar.JarFile
import org.glavo.arkivo.gradle.DownloadVerifiedFile
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.file.RelativePath

plugins {
    `java-test-fixtures`
}

// Test fixtures are shared by local suites and are not part of the published library.
(components["java"] as AdhocComponentWithVariants).apply {
    withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
    withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
}

val benchmarkSourceSet = sourceSets.create("benchmark") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}

val fuzzTestSourceSet = sourceSets.create("fuzzTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}

configurations[benchmarkSourceSet.implementationConfigurationName].extendsFrom(
    configurations.api.get(),
    configurations.implementation.get()
)
configurations[benchmarkSourceSet.runtimeOnlyConfigurationName].extendsFrom(
    configurations.runtimeOnly.get()
)
configurations[fuzzTestSourceSet.implementationConfigurationName].extendsFrom(
    configurations.api.get(),
    configurations.implementation.get()
)
configurations[fuzzTestSourceSet.runtimeOnlyConfigurationName].extendsFrom(
    configurations.runtimeOnly.get()
)

dependencies {
    api(project(":arkivo-archive"))
    api(project(":arkivo-archive-all"))
    api(project(":arkivo-checksum"))
    api(project(":arkivo-checksum-xxhash"))
    api(project(":arkivo-codec-all"))
    implementation(project(":arkivo-archive-codec"))
    testFixturesCompileOnly("org.jetbrains:annotations:26.1.0")
    testImplementation("org.tukaani:xz:1.12")
    testImplementation("org.apache.commons:commons-compress:1.28.0")
    add("tier2TestImplementation", project(":arkivo-base"))
    add("tier2TestImplementation", "com.github.luben:zstd-jni:1.5.7-9")
    add("tier2TestImplementation", "net.lingala.zip4j:zip4j:2.11.5")
    add("tier3TestImplementation", testFixtures(project()))
    add(benchmarkSourceSet.compileOnlyConfigurationName, "org.jetbrains:annotations:26.1.0")
    add(benchmarkSourceSet.implementationConfigurationName, "org.openjdk.jmh:jmh-core:1.37")
    add(benchmarkSourceSet.implementationConfigurationName, "org.apache.commons:commons-compress:1.28.0")
    add(benchmarkSourceSet.implementationConfigurationName, "org.tukaani:xz:1.12")
    add(
        benchmarkSourceSet.annotationProcessorConfigurationName,
        "org.openjdk.jmh:jmh-generator-annprocess:1.37"
    )
    add(fuzzTestSourceSet.compileOnlyConfigurationName, "org.jetbrains:annotations:26.1.0")
    add(fuzzTestSourceSet.implementationConfigurationName, platform("org.junit:junit-bom:6.0.0"))
    add(fuzzTestSourceSet.implementationConfigurationName, "org.junit.jupiter:junit-jupiter")
    add(fuzzTestSourceSet.implementationConfigurationName, "com.code-intelligence:jazzer-junit:0.30.0")
    add(fuzzTestSourceSet.runtimeOnlyConfigurationName, "org.junit.platform:junit-platform-launcher")
}

val libarchiveManifestFile = rootProject.file("gradle/test-data/libarchive.properties")
val libarchiveManifest = Properties().apply {
    libarchiveManifestFile.inputStream().use(::load)
}
val libarchiveVersion = libarchiveManifest.getProperty("version")
val libarchiveRoot = libarchiveManifest.getProperty("archiveRoot")
val libarchiveArchiveName = libarchiveManifest.getProperty("archiveName")
val libarchiveArchiveSha256 = libarchiveManifest.getProperty("archiveSha256")
val libarchiveArchiveSize = libarchiveManifest.getProperty("archiveSize").toLong()
val testDataCacheDirectory = rootProject.layout.dir(rootProject.providers.provider {
    rootProject.file(
        rootProject.providers.gradleProperty("arkivo.testDataCacheDirectory").orNull
            ?: ".arkivo-cache/test-data"
    )
})
val libarchiveArchive = rootProject.layout.file(testDataCacheDirectory.map { directory ->
    directory.file("downloads/sha256/$libarchiveArchiveSha256/$libarchiveArchiveName").asFile
})
val libarchiveTestDataDirectory = rootProject.layout.buildDirectory.dir(
    "test-data/libarchive/$libarchiveVersion"
)

val downloadLibarchiveTestSources = tasks.register<DownloadVerifiedFile>("downloadLibarchiveTestSources") {
    group = "verification"
    description = "Downloads and verifies the pinned official libarchive source release."
    sourceUrl.set(libarchiveManifest.getProperty("archiveUrl"))
    expectedSha256.set(libarchiveArchiveSha256)
    expectedSize.set(libarchiveArchiveSize)
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(libarchiveArchive)
}

val libarchiveFixturePattern = "$libarchiveRoot/libarchive/test/*.uu"

val prepareLibarchiveTestCorpus = tasks.register<Sync>("prepareLibarchiveTestCorpus") {
    group = "verification"
    description = "Extracts the complete uuencoded fixture corpus from the pinned libarchive source release."
    dependsOn(downloadLibarchiveTestSources)
    inputs.property("fixturePattern", libarchiveFixturePattern)

    from(downloadLibarchiveTestSources.flatMap { it.destination }.map { archive ->
        tarTree(resources.gzip(archive.asFile))
    }) {
        include("$libarchiveRoot/COPYING")
        include(libarchiveFixturePattern)
        eachFile {
            val segments = relativePath.segments
            require(segments.isNotEmpty() && segments[0] == libarchiveRoot) {
                "Unexpected libarchive source archive path: $relativePath"
            }
            relativePath = if (segments.size == 2 && segments[1] == "COPYING") {
                RelativePath(true, "COPYING")
            } else {
                require(segments.size == 4
                        && segments[1] == "libarchive"
                        && segments[2] == "test") {
                    "Unexpected libarchive fixture path: $relativePath"
                }
                RelativePath(true, "fixtures", segments[3])
            }
        }
        includeEmptyDirs = false
    }
    from(libarchiveManifestFile) {
        rename { "UPSTREAM.properties" }
    }
    into(libarchiveTestDataDirectory)
}

tasks.named<Test>("tier2Test") {
    group = "verification"
    description = "Runs archive readers against the pinned official libarchive corpus."
    dependsOn(prepareLibarchiveTestCorpus)
    shouldRunAfter(tasks.test)
    inputs.dir(libarchiveTestDataDirectory)
    systemProperty(
        "arkivo.libarchive.testDataDirectory",
        libarchiveTestDataDirectory.get().asFile.absolutePath
    )
}

val sharpCompressManifestFile = rootProject.file("gradle/test-data/sharpcompress.properties")
val sharpCompressManifest = Properties().apply {
    sharpCompressManifestFile.inputStream().use(::load)
}
val sharpCompressVersion = sharpCompressManifest.getProperty("version")
val sharpCompressRoot = sharpCompressManifest.getProperty("archiveRoot")
val sharpCompressSha256 = sharpCompressManifest.getProperty("archiveSha256")
val sharpCompressArchive = rootProject.layout.file(testDataCacheDirectory.map { directory ->
    directory.file("downloads/sha256/$sharpCompressSha256/${sharpCompressManifest.getProperty("archiveName")}").asFile
})
val sharpCompressTestDataDirectory = rootProject.layout.buildDirectory.dir("test-data/sharpcompress/$sharpCompressVersion")

val downloadSharpCompressTestSources = tasks.register<DownloadVerifiedFile>("downloadSharpCompressTestSources") {
    group = "verification"
    description = "Downloads and verifies the pinned SharpCompress source and test archives."
    sourceUrl.set(sharpCompressManifest.getProperty("archiveUrl"))
    expectedSha256.set(sharpCompressSha256)
    expectedSize.set(sharpCompressManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(sharpCompressArchive)
}

val prepareSharpCompressTestCorpus = tasks.register<Sync>("prepareSharpCompressTestCorpus") {
    group = "verification"
    description = "Extracts SharpCompress archives, original files, and reference test sources."
    dependsOn(downloadSharpCompressTestSources)
    from(downloadSharpCompressTestSources.flatMap { it.destination }.map { archive ->
        tarTree(resources.gzip(archive.asFile))
    }) {
        include("$sharpCompressRoot/LICENSE*")
        include("$sharpCompressRoot/tests/TestArchives/**")
        include("$sharpCompressRoot/tests/SharpCompress.Test/**/*.cs")
        eachFile {
            val segments = relativePath.segments
            require(segments.size > 1 && segments[0] == sharpCompressRoot) {
                "Unexpected SharpCompress source archive path: $relativePath"
            }
            relativePath = RelativePath(true, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(sharpCompressManifestFile) { rename { "UPSTREAM.properties" } }
    into(sharpCompressTestDataDirectory)
}

tasks.named<Test>("tier2Test") {
    dependsOn(prepareSharpCompressTestCorpus)
    inputs.dir(sharpCompressTestDataDirectory)
    systemProperty("arkivo.sharpcompress.testDataDirectory", sharpCompressTestDataDirectory.get().asFile.absolutePath)
}

val py7zrManifestFile = rootProject.file("gradle/test-data/py7zr.properties")
val py7zrManifest = Properties().apply {
    py7zrManifestFile.inputStream().use(::load)
}
val py7zrVersion = py7zrManifest.getProperty("version")
val py7zrRoot = py7zrManifest.getProperty("archiveRoot")
val py7zrSha256 = py7zrManifest.getProperty("archiveSha256")
val py7zrArchive = rootProject.layout.file(testDataCacheDirectory.map { directory ->
    directory.file("downloads/sha256/$py7zrSha256/${py7zrManifest.getProperty("archiveName")}").asFile
})
val py7zrTestDataDirectory = rootProject.layout.buildDirectory.dir("test-data/py7zr/$py7zrVersion")

val downloadPy7zrTestSources = tasks.register<DownloadVerifiedFile>("downloadPy7zrTestSources") {
    group = "verification"
    description = "Downloads and verifies the pinned py7zr source and archive fixtures."
    sourceUrl.set(py7zrManifest.getProperty("archiveUrl"))
    expectedSha256.set(py7zrSha256)
    expectedSize.set(py7zrManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(py7zrArchive)
}

val preparePy7zrTestCorpus = tasks.register<Sync>("preparePy7zrTestCorpus") {
    group = "verification"
    description = "Extracts py7zr fixtures, reference tests, and their upstream license."
    dependsOn(downloadPy7zrTestSources)
    from(downloadPy7zrTestSources.flatMap { it.destination }.map { archive ->
        tarTree(resources.gzip(archive.asFile))
    }) {
        include("$py7zrRoot/LICENSE", "$py7zrRoot/tests/**")
        eachFile {
            val segments = relativePath.segments
            require(segments.size > 1 && segments[0] == py7zrRoot) {
                "Unexpected py7zr source archive path: $relativePath"
            }
            relativePath = RelativePath(true, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(py7zrManifestFile) { rename { "UPSTREAM.properties" } }
    into(py7zrTestDataDirectory)
}

tasks.named<Test>("tier2Test") {
    dependsOn(preparePy7zrTestCorpus)
    inputs.dir(py7zrTestDataDirectory)
    systemProperty("arkivo.py7zr.testDataDirectory", py7zrTestDataDirectory.get().asFile.absolutePath)
}

val libzipManifestFile = rootProject.file("gradle/test-data/libzip.properties")
val libzipManifest = Properties().apply {
    libzipManifestFile.inputStream().use(::load)
}
val libzipVersion = libzipManifest.getProperty("version")
val libzipRoot = libzipManifest.getProperty("archiveRoot")
val libzipSha256 = libzipManifest.getProperty("archiveSha256")
val libzipArchive = rootProject.layout.file(testDataCacheDirectory.map { directory ->
    directory.file("downloads/sha256/$libzipSha256/${libzipManifest.getProperty("archiveName")}").asFile
})
val libzipTestDataDirectory = rootProject.layout.buildDirectory.dir("test-data/libzip/$libzipVersion")

val downloadLibzipTestSources = tasks.register<DownloadVerifiedFile>("downloadLibzipTestSources") {
    group = "verification"
    description = "Downloads and verifies the pinned libzip source and regression data."
    sourceUrl.set(libzipManifest.getProperty("archiveUrl"))
    expectedSha256.set(libzipSha256)
    expectedSize.set(libzipManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(libzipArchive)
}

val prepareLibzipTestCorpus = tasks.register<Sync>("prepareLibzipTestCorpus") {
    group = "verification"
    description = "Extracts libzip regression inputs, expected results, and licensing information."
    dependsOn(downloadLibzipTestSources)
    from(downloadLibzipTestSources.flatMap { it.destination }.map { archive ->
        tarTree(resources.gzip(archive.asFile))
    }) {
        include("$libzipRoot/LICENSE")
        include("$libzipRoot/regress/**")
        eachFile {
            val segments = relativePath.segments
            require(segments.size > 1 && segments[0] == libzipRoot) {
                "Unexpected libzip source archive path: $relativePath"
            }
            relativePath = RelativePath(true, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(libzipManifestFile) { rename { "UPSTREAM.properties" } }
    into(libzipTestDataDirectory)
}

tasks.named<Test>("tier2Test") {
    dependsOn(prepareLibzipTestCorpus)
    inputs.dir(libzipTestDataDirectory)
    systemProperty("arkivo.libzip.testDataDirectory", libzipTestDataDirectory.get().asFile.absolutePath)
}

val rarfileManifestFile = rootProject.file("gradle/test-data/rarfile.properties")
val rarfileManifest = Properties().apply {
    rarfileManifestFile.inputStream().use(::load)
}
val rarfileVersion = rarfileManifest.getProperty("version")
val rarfileRoot = rarfileManifest.getProperty("archiveRoot")
val rarfileSha256 = rarfileManifest.getProperty("archiveSha256")
val rarfileArchive = rootProject.layout.file(testDataCacheDirectory.map { directory ->
    directory.file("downloads/sha256/$rarfileSha256/${rarfileManifest.getProperty("archiveName")}").asFile
})
val rarfileTestDataDirectory = rootProject.layout.buildDirectory.dir("test-data/rarfile/$rarfileVersion")

val downloadRarfileTestSources = tasks.register<DownloadVerifiedFile>("downloadRarfileTestSources") {
    group = "verification"
    description = "Downloads and verifies the pinned rarfile source and regression data."
    sourceUrl.set(rarfileManifest.getProperty("archiveUrl"))
    expectedSha256.set(rarfileSha256)
    expectedSize.set(rarfileManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(rarfileArchive)
}

val prepareRarfileTestCorpus = tasks.register<Sync>("prepareRarfileTestCorpus") {
    group = "verification"
    description = "Extracts rarfile archives, expected metadata, and reference tests."
    dependsOn(downloadRarfileTestSources)
    from(downloadRarfileTestSources.flatMap { it.destination }.map { archive ->
        tarTree(resources.gzip(archive.asFile))
    }) {
        include("$rarfileRoot/LICENSE")
        include("$rarfileRoot/test/**")
        eachFile {
            val segments = relativePath.segments
            require(segments.size > 1 && segments[0] == rarfileRoot) {
                "Unexpected rarfile source archive path: $relativePath"
            }
            relativePath = RelativePath(true, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(rarfileManifestFile) { rename { "UPSTREAM.properties" } }
    into(rarfileTestDataDirectory)
}

tasks.named<Test>("tier2Test") {
    dependsOn(prepareRarfileTestCorpus)
    inputs.dir(rarfileTestDataDirectory)
    systemProperty("arkivo.rarfile.testDataDirectory", rarfileTestDataDirectory.get().asFile.absolutePath)
}

val goManifestFile = rootProject.file("gradle/test-data/go.properties")
val goManifest = Properties().apply {
    goManifestFile.inputStream().use(::load)
}
val goVersion = goManifest.getProperty("version")
val goRoot = goManifest.getProperty("archiveRoot")
val goSha256 = goManifest.getProperty("archiveSha256")
val goArchive = rootProject.layout.file(testDataCacheDirectory.map { directory ->
    directory.file("downloads/sha256/$goSha256/${goManifest.getProperty("archiveName")}").asFile
})
val goTestDataDirectory = rootProject.layout.buildDirectory.dir("test-data/go/$goVersion")

val downloadGoTestSources = tasks.register<DownloadVerifiedFile>("downloadGoTestSources") {
    group = "verification"
    description = "Downloads and verifies the pinned Go source release."
    sourceUrl.set(goManifest.getProperty("archiveUrl"))
    expectedSha256.set(goSha256)
    expectedSize.set(goManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(goArchive)
}

val prepareGoTestCorpus = tasks.register<Sync>("prepareGoTestCorpus") {
    group = "verification"
    description = "Extracts Go archive and compression fixtures with their reference tests and license."
    dependsOn(downloadGoTestSources)
    from(downloadGoTestSources.flatMap { it.destination }.map { archive ->
        tarTree(resources.gzip(archive.asFile))
    }) {
        include("$goRoot/LICENSE", "$goRoot/PATENTS")
        include("$goRoot/src/archive/**/testdata/**", "$goRoot/src/archive/**/*_test.go")
        include("$goRoot/src/compress/**/testdata/**", "$goRoot/src/compress/**/*_test.go")
        eachFile {
            val segments = relativePath.segments
            require(segments.size > 1 && segments[0] == goRoot) {
                "Unexpected Go source archive path: $relativePath"
            }
            relativePath = RelativePath(true, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(goManifestFile) { rename { "UPSTREAM.properties" } }
    into(goTestDataDirectory)
}

tasks.named<Test>("tier2Test") {
    dependsOn(prepareGoTestCorpus)
    inputs.dir(goTestDataDirectory)
    systemProperty("arkivo.go.testDataDirectory", goTestDataDirectory.get().asFile.absolutePath)
}

val cpythonManifestFile = rootProject.file("gradle/test-data/cpython.properties")
val cpythonManifest = Properties().apply {
    cpythonManifestFile.inputStream().use(::load)
}
val cpythonVersion = cpythonManifest.getProperty("version")
val cpythonRoot = cpythonManifest.getProperty("archiveRoot")
val cpythonSha256 = cpythonManifest.getProperty("archiveSha256")
val cpythonArchive = rootProject.layout.file(testDataCacheDirectory.map { directory ->
    directory.file("downloads/sha256/$cpythonSha256/${cpythonManifest.getProperty("archiveName")}").asFile
})
val cpythonTestDataDirectory = rootProject.layout.buildDirectory.dir("test-data/cpython/$cpythonVersion")

val downloadCPythonTestSources = tasks.register<DownloadVerifiedFile>("downloadCPythonTestSources") {
    group = "verification"
    description = "Downloads and verifies the pinned CPython source release."
    sourceUrl.set(cpythonManifest.getProperty("archiveUrl"))
    expectedSha256.set(cpythonSha256)
    expectedSize.set(cpythonManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(cpythonArchive)
}

val prepareCPythonTestCorpus = tasks.register<Sync>("prepareCPythonTestCorpus") {
    group = "verification"
    description = "Extracts CPython TAR fixtures with their reference tests and license."
    dependsOn(downloadCPythonTestSources)
    from(downloadCPythonTestSources.flatMap { it.destination }.map { archive ->
        tarTree(resources.gzip(archive.asFile))
    }) {
        include("$cpythonRoot/LICENSE")
        include("$cpythonRoot/Lib/test/archivetestdata/**", "$cpythonRoot/Lib/test/test_tarfile.py")
        eachFile {
            val segments = relativePath.segments
            require(segments.size > 1 && segments[0] == cpythonRoot) {
                "Unexpected CPython source archive path: $relativePath"
            }
            relativePath = RelativePath(true, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(cpythonManifestFile) { rename { "UPSTREAM.properties" } }
    into(cpythonTestDataDirectory)
}

tasks.named<Test>("tier2Test") {
    dependsOn(prepareCPythonTestCorpus)
    inputs.dir(cpythonTestDataDirectory)
    systemProperty("arkivo.cpython.testDataDirectory", cpythonTestDataDirectory.get().asFile.absolutePath)
}

val zip4jManifestFile = rootProject.file("gradle/test-data/zip4j.properties")
val zip4jManifest = Properties().apply {
    zip4jManifestFile.inputStream().use(::load)
}
val zip4jVersion = zip4jManifest.getProperty("version")
val zip4jRoot = zip4jManifest.getProperty("archiveRoot")
val zip4jSha256 = zip4jManifest.getProperty("archiveSha256")
val zip4jArchive = rootProject.layout.file(testDataCacheDirectory.map { directory ->
    directory.file("downloads/sha256/$zip4jSha256/${zip4jManifest.getProperty("archiveName")}").asFile
})
val zip4jTestDataDirectory = rootProject.layout.buildDirectory.dir("test-data/zip4j/$zip4jVersion")

val downloadZip4jTestSources = tasks.register<DownloadVerifiedFile>("downloadZip4jTestSources") {
    group = "verification"
    description = "Downloads and verifies the pinned Zip4j source release."
    sourceUrl.set(zip4jManifest.getProperty("archiveUrl"))
    expectedSha256.set(zip4jSha256)
    expectedSize.set(zip4jManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(zip4jArchive)
}

val prepareZip4jTestCorpus = tasks.register<Sync>("prepareZip4jTestCorpus") {
    group = "verification"
    description = "Extracts Zip4j regression archives, original contents, reference tests, and license."
    dependsOn(downloadZip4jTestSources)
    from(downloadZip4jTestSources.flatMap { it.destination }.map { archive ->
        tarTree(resources.gzip(archive.asFile))
    }) {
        include("$zip4jRoot/LICENSE", "$zip4jRoot/src/test/**")
        eachFile {
            val segments = relativePath.segments
            require(segments.size > 1 && segments[0] == zip4jRoot) {
                "Unexpected Zip4j source archive path: $relativePath"
            }
            relativePath = RelativePath(true, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(zip4jManifestFile) { rename { "UPSTREAM.properties" } }
    into(zip4jTestDataDirectory)
}

tasks.named<Test>("tier2Test") {
    dependsOn(prepareZip4jTestCorpus)
    inputs.dir(zip4jTestDataDirectory)
    systemProperty("arkivo.zip4j.testDataDirectory", zip4jTestDataDirectory.get().asFile.absolutePath)
}

val minizipManifestFile = rootProject.file("gradle/test-data/minizip-ng.properties")
val minizipManifest = Properties().apply {
    minizipManifestFile.inputStream().use(::load)
}
val minizipVersion = minizipManifest.getProperty("version")
val minizipRoot = minizipManifest.getProperty("archiveRoot")
val minizipSha256 = minizipManifest.getProperty("archiveSha256")
val minizipArchive = rootProject.layout.file(testDataCacheDirectory.map { directory ->
    directory.file("downloads/sha256/$minizipSha256/${minizipManifest.getProperty("archiveName")}").asFile
})
val minizipTestDataDirectory = rootProject.layout.buildDirectory.dir("test-data/minizip-ng/$minizipVersion")

val downloadMinizipTestSources = tasks.register<DownloadVerifiedFile>("downloadMinizipTestSources") {
    group = "verification"
    description = "Downloads and verifies the pinned minizip-ng source release."
    sourceUrl.set(minizipManifest.getProperty("archiveUrl"))
    expectedSha256.set(minizipSha256)
    expectedSize.set(minizipManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(minizipArchive)
}

val prepareMinizipTestCorpus = tasks.register<Sync>("prepareMinizipTestCorpus") {
    group = "verification"
    description = "Extracts minizip-ng ZIP seeds, reference tests, and license."
    dependsOn(downloadMinizipTestSources)
    from(downloadMinizipTestSources.flatMap { it.destination }.map { archive ->
        tarTree(resources.gzip(archive.asFile))
    }) {
        include("$minizipRoot/LICENSE", "$minizipRoot/test/**")
        eachFile {
            val segments = relativePath.segments
            require(segments.size > 1 && segments[0] == minizipRoot) {
                "Unexpected minizip-ng source archive path: $relativePath"
            }
            relativePath = RelativePath(true, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(minizipManifestFile) { rename { "UPSTREAM.properties" } }
    into(minizipTestDataDirectory)
}

tasks.named<Test>("tier2Test") {
    dependsOn(prepareMinizipTestCorpus)
    inputs.dir(minizipTestDataDirectory)
    systemProperty("arkivo.minizip.testDataDirectory", minizipTestDataDirectory.get().asFile.absolutePath)
}

val sharpZipLibManifestFile = rootProject.file("gradle/test-data/sharpziplib.properties")
val sharpZipLibManifest = Properties().apply {
    sharpZipLibManifestFile.inputStream().use(::load)
}
val sharpZipLibVersion = sharpZipLibManifest.getProperty("version")
val sharpZipLibRoot = sharpZipLibManifest.getProperty("archiveRoot")
val sharpZipLibSha256 = sharpZipLibManifest.getProperty("archiveSha256")
val sharpZipLibArchive = rootProject.layout.file(testDataCacheDirectory.map { directory ->
    directory.file("downloads/sha256/$sharpZipLibSha256/${sharpZipLibManifest.getProperty("archiveName")}").asFile
})
val sharpZipLibTestDataDirectory = rootProject.layout.buildDirectory.dir("test-data/sharpziplib/$sharpZipLibVersion")

val downloadSharpZipLibTestSources = tasks.register<DownloadVerifiedFile>("downloadSharpZipLibTestSources") {
    group = "verification"
    description = "Downloads and verifies the pinned SharpZipLib source release."
    sourceUrl.set(sharpZipLibManifest.getProperty("archiveUrl"))
    expectedSha256.set(sharpZipLibSha256)
    expectedSize.set(sharpZipLibManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(sharpZipLibArchive)
}

val prepareSharpZipLibTestCorpus = tasks.register<Sync>("prepareSharpZipLibTestCorpus") {
    group = "verification"
    description = "Extracts SharpZipLib reference tests with embedded regression archives and their license."
    dependsOn(downloadSharpZipLibTestSources)
    from(downloadSharpZipLibTestSources.flatMap { it.destination }.map { archive ->
        tarTree(resources.gzip(archive.asFile))
    }) {
        include("$sharpZipLibRoot/LICENSE.txt", "$sharpZipLibRoot/test/**")
        eachFile {
            val segments = relativePath.segments
            require(segments.size > 1 && segments[0] == sharpZipLibRoot) {
                "Unexpected SharpZipLib source archive path: $relativePath"
            }
            relativePath = RelativePath(true, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(sharpZipLibManifestFile) { rename { "UPSTREAM.properties" } }
    into(sharpZipLibTestDataDirectory)
}

tasks.named<Test>("tier2Test") {
    dependsOn(prepareSharpZipLibTestCorpus)
    inputs.dir(sharpZipLibTestDataDirectory)
    systemProperty("arkivo.sharpziplib.testDataDirectory", sharpZipLibTestDataDirectory.get().asFile.absolutePath)
}

val jsZipManifestFile = rootProject.file("gradle/test-data/jszip.properties")
val jsZipManifest = Properties().apply {
    jsZipManifestFile.inputStream().use(::load)
}
val jsZipVersion = jsZipManifest.getProperty("version")
val jsZipRoot = jsZipManifest.getProperty("archiveRoot")
val jsZipSha256 = jsZipManifest.getProperty("archiveSha256")
val jsZipArchive = rootProject.layout.file(testDataCacheDirectory.map { directory ->
    directory.file("downloads/sha256/$jsZipSha256/${jsZipManifest.getProperty("archiveName")}").asFile
})
val jsZipTestDataDirectory = rootProject.layout.buildDirectory.dir("test-data/jszip/$jsZipVersion")

val downloadJSZipTestSources = tasks.register<DownloadVerifiedFile>("downloadJSZipTestSources") {
    group = "verification"
    description = "Downloads and verifies the pinned JSZip source release."
    sourceUrl.set(jsZipManifest.getProperty("archiveUrl"))
    expectedSha256.set(jsZipSha256)
    expectedSize.set(jsZipManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(jsZipArchive)
}

val prepareJSZipTestCorpus = tasks.register<Sync>("prepareJSZipTestCorpus") {
    group = "verification"
    description = "Extracts the JSZip interoperability corpus, reference assertions, and its license."
    dependsOn(downloadJSZipTestSources)
    val corpusPatterns = listOf("LICENSE.markdown", "test/asserts/**", "test/ref/**")
    inputs.property("corpusPatterns", corpusPatterns)
    from(downloadJSZipTestSources.flatMap { it.destination }.map { archive ->
        tarTree(resources.gzip(archive.asFile))
    }) {
        include(*corpusPatterns.map { "$jsZipRoot/$it" }.toTypedArray())
        eachFile {
            val segments = relativePath.segments
            require(segments.size > 1 && segments[0] == jsZipRoot) {
                "Unexpected JSZip source archive path: $relativePath"
            }
            relativePath = RelativePath(true, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(jsZipManifestFile) { rename { "UPSTREAM.properties" } }
    into(jsZipTestDataDirectory)
}

tasks.named<Test>("tier2Test") {
    dependsOn(prepareJSZipTestCorpus)
    inputs.dir(jsZipTestDataDirectory)
    systemProperty("arkivo.jszip.testDataDirectory", jsZipTestDataDirectory.get().asFile.absolutePath)
}

val zipJsManifestFile = rootProject.file("gradle/test-data/zipjs.properties")
val zipJsManifest = Properties().apply {
    zipJsManifestFile.inputStream().use(::load)
}
val zipJsVersion = zipJsManifest.getProperty("version")
val zipJsRoot = zipJsManifest.getProperty("archiveRoot")
val zipJsSha256 = zipJsManifest.getProperty("archiveSha256")
val zipJsArchive = rootProject.layout.file(testDataCacheDirectory.map { directory ->
    directory.file("downloads/sha256/$zipJsSha256/${zipJsManifest.getProperty("archiveName")}").asFile
})
val zipJsTestDataDirectory = rootProject.layout.buildDirectory.dir("test-data/zipjs/$zipJsVersion")

val downloadZipJsTestSources = tasks.register<DownloadVerifiedFile>("downloadZipJsTestSources") {
    group = "verification"
    description = "Downloads and verifies the pinned zip.js source release."
    sourceUrl.set(zipJsManifest.getProperty("archiveUrl"))
    expectedSha256.set(zipJsSha256)
    expectedSize.set(zipJsManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(zipJsArchive)
}

val prepareZipJsTestCorpus = tasks.register<Sync>("prepareZipJsTestCorpus") {
    group = "verification"
    description = "Extracts selected zip.js regression archives, reference tests, and their license."
    dependsOn(downloadZipJsTestSources)
    val corpusPatterns = listOf("LICENSE", "tests/all/**") +
        zipJsManifest.getProperty("fixtures").split(',').map { "tests/data/$it" }
    inputs.property("corpusPatterns", corpusPatterns)
    from(downloadZipJsTestSources.flatMap { it.destination }.map { archive ->
        tarTree(resources.gzip(archive.asFile))
    }) {
        include(*corpusPatterns.map { "$zipJsRoot/$it" }.toTypedArray())
        eachFile {
            val segments = relativePath.segments
            require(segments.size > 1 && segments[0] == zipJsRoot) {
                "Unexpected zip.js source archive path: $relativePath"
            }
            relativePath = RelativePath(true, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(zipJsManifestFile) { rename { "UPSTREAM.properties" } }
    into(zipJsTestDataDirectory)
}

tasks.named<Test>("tier2Test") {
    dependsOn(prepareZipJsTestCorpus)
    inputs.dir(zipJsTestDataDirectory)
    systemProperty("arkivo.zipjs.testDataDirectory", zipJsTestDataDirectory.get().asFile.absolutePath)
}

val zipRsManifestFile = rootProject.file("gradle/test-data/ziprs.properties")
val zipRsManifest = Properties().apply {
    zipRsManifestFile.inputStream().use(::load)
}
val zipRsVersion = zipRsManifest.getProperty("version")
val zipRsRoot = zipRsManifest.getProperty("archiveRoot")
val zipRsSha256 = zipRsManifest.getProperty("archiveSha256")
val zipRsArchive = rootProject.layout.file(testDataCacheDirectory.map { directory ->
    directory.file("downloads/sha256/$zipRsSha256/${zipRsManifest.getProperty("archiveName")}").asFile
})
val zipRsTestDataDirectory = rootProject.layout.buildDirectory.dir("test-data/ziprs/$zipRsVersion")

val downloadZipRsTestSources = tasks.register<DownloadVerifiedFile>("downloadZipRsTestSources") {
    group = "verification"
    description = "Downloads and verifies the pinned zip-rs source release."
    sourceUrl.set(zipRsManifest.getProperty("archiveUrl"))
    expectedSha256.set(zipRsSha256)
    expectedSize.set(zipRsManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(zipRsArchive)
}

val prepareZipRsTestCorpus = tasks.register<Sync>("prepareZipRsTestCorpus") {
    group = "verification"
    description = "Extracts zip-rs ZIP regressions, finite fuzz seeds, reference assertions, and licenses."
    dependsOn(downloadZipRsTestSources)
    val corpusPatterns = listOf("LICENSE", "tests/*.rs", "src/read*.rs", "src/read/*.rs",
        "src/extra_fields/extended_timestamp.rs", "tests/data/*.zip", "tests/data/LICENSE.deflate64.zip.txt",
        "tests/data/folder/**", "tests/data/legacy/*.zip", "fuzz/read/in/*")
    val excludedPatterns = listOf("tests/data/lin-ub_iwd-v11.zip", "tests/data/pandoc_soft_links.zip")
    inputs.property("corpusPatterns", corpusPatterns)
    inputs.property("excludedPatterns", excludedPatterns)
    from(downloadZipRsTestSources.flatMap { it.destination }.map { archive ->
        tarTree(resources.gzip(archive.asFile))
    }) {
        include(*corpusPatterns.map { "$zipRsRoot/$it" }.toTypedArray())
        exclude(*excludedPatterns.map { "$zipRsRoot/$it" }.toTypedArray())
        eachFile {
            val segments = relativePath.segments
            require(segments.size > 1 && segments[0] == zipRsRoot) {
                "Unexpected zip-rs source archive path: $relativePath"
            }
            relativePath = RelativePath(true, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(zipRsManifestFile) { rename { "UPSTREAM.properties" } }
    into(zipRsTestDataDirectory)
}

tasks.named<Test>("tier2Test") {
    dependsOn(prepareZipRsTestCorpus)
    inputs.dir(zipRsTestDataDirectory)
    systemProperty("arkivo.ziprs.testDataDirectory", zipRsTestDataDirectory.get().asFile.absolutePath)
}

val gnuGzipManifestFile = rootProject.file("gradle/test-data/gnu-gzip.properties")
val gnuGzipManifest = Properties().apply { gnuGzipManifestFile.inputStream().use(::load) }
val gnuGzipVersion = gnuGzipManifest.getProperty("version")
val gnuGzipRoot = gnuGzipManifest.getProperty("archiveRoot")
val gnuGzipSha256 = gnuGzipManifest.getProperty("archiveSha256")
val gnuGzipTestDataDirectory = rootProject.layout.buildDirectory.dir("test-data/gnu-gzip/$gnuGzipVersion")

val downloadGnuGzipTestSources = tasks.register<DownloadVerifiedFile>("downloadGnuGzipTestSources") {
    group = "verification"
    description = "Downloads and verifies the pinned GNU gzip regression resources."
    sourceUrl.set(gnuGzipManifest.getProperty("archiveUrl"))
    expectedSha256.set(gnuGzipSha256)
    expectedSize.set(gnuGzipManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(testDataCacheDirectory.map {
        it.file("downloads/sha256/$gnuGzipSha256/${gnuGzipManifest.getProperty("archiveName")}")
    })
}

val prepareGnuGzipTestCorpus = tasks.register<Sync>("prepareGnuGzipTestCorpus") {
    group = "verification"
    description = "Extracts GNU gzip reference bytes, crash reproducers, and their original license."
    dependsOn(downloadGnuGzipTestSources)
    val corpusFiles = listOf("COPYING", "tests/reference", "tests/helin-segv", "tests/hufts",
        "tests/hufts-segv.gz", "tests/unpack-invalid", "tests/memcpy-abuse", "tests/trailing-nul")
    inputs.property("corpusFiles", corpusFiles)
    from(downloadGnuGzipTestSources.flatMap { it.destination }.map { tarTree(resources.gzip(it.asFile)) }) {
        include(*corpusFiles.map { "$gnuGzipRoot/$it" }.toTypedArray())
        eachFile {
            val segments = relativePath.segments
            require(segments.size > 1 && segments[0] == gnuGzipRoot) { "Unexpected GNU gzip archive path: $relativePath" }
            relativePath = RelativePath(relativePath.isFile, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(gnuGzipManifestFile) { rename { "UPSTREAM.properties" } }
    into(gnuGzipTestDataDirectory)
}

tasks.named<Test>("tier2Test") {
    dependsOn(prepareGnuGzipTestCorpus)
    inputs.dir(gnuGzipTestDataDirectory)
    systemProperty("arkivo.gnu-gzip.testDataDirectory", gnuGzipTestDataDirectory.get().asFile.absolutePath)
}

val dotNetAssetsManifestFile = rootProject.file("gradle/test-data/dotnet-assets.properties")
val dotNetAssetsManifest = Properties().apply {
    dotNetAssetsManifestFile.inputStream().use(::load)
}
val dotNetAssetsVersion = dotNetAssetsManifest.getProperty("version")
val dotNetAssetsRoot = dotNetAssetsManifest.getProperty("archiveRoot")
val dotNetAssetsSha256 = dotNetAssetsManifest.getProperty("archiveSha256")
val dotNetAssetsArchive = rootProject.layout.file(testDataCacheDirectory.map { directory ->
    directory.file("downloads/sha256/$dotNetAssetsSha256/${dotNetAssetsManifest.getProperty("archiveName")}").asFile
})
val dotNetAssetsTestDataDirectory = rootProject.layout.buildDirectory.dir("test-data/dotnet-assets/$dotNetAssetsVersion")

val downloadDotNetAssetsTestSources = tasks.register<DownloadVerifiedFile>("downloadDotNetAssetsTestSources") {
    group = "verification"
    description = "Downloads and verifies the pinned dotnet-assets source archive."
    sourceUrl.set(dotNetAssetsManifest.getProperty("archiveUrl"))
    expectedSha256.set(dotNetAssetsSha256)
    expectedSize.set(dotNetAssetsManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(dotNetAssetsArchive)
}

val prepareDotNetAssetsTestCorpus = tasks.register<Sync>("prepareDotNetAssetsTestCorpus") {
    group = "verification"
    description = "Extracts selected dotnet-assets compression test resources and notices."
    dependsOn(downloadDotNetAssetsTestSources)
    val corpusPatterns = listOf("LICENSE.TXT", "THIRD-PARTY-NOTICES.TXT",
        "src/System.IO.Compression.TestData/ZipTestData/**") +
        listOf("UncompressedTestFiles", "DeflateTestData", "GZipTestData", "ZLibTestData", "ZstandardTestData")
            .map { "src/System.IO.Compression.TestData/$it/TestDocument.*" }
    inputs.property("corpusPatterns", corpusPatterns)
    from(downloadDotNetAssetsTestSources.flatMap { it.destination }.map { archive ->
        tarTree(resources.gzip(archive.asFile))
    }) {
        include(*corpusPatterns.map { "$dotNetAssetsRoot/$it" }.toTypedArray())
        eachFile {
            val segments = relativePath.segments
            require(segments.size > 1 && segments[0] == dotNetAssetsRoot) {
                "Unexpected dotnet-assets source archive path: $relativePath"
            }
            relativePath = RelativePath(true, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(dotNetAssetsManifestFile) { rename { "UPSTREAM.properties" } }
    into(dotNetAssetsTestDataDirectory)
}

tasks.named<Test>("tier2Test") {
    dependsOn(prepareDotNetAssetsTestCorpus)
    inputs.dir(dotNetAssetsTestDataDirectory)
    systemProperty("arkivo.dotnet-assets.testDataDirectory", dotNetAssetsTestDataDirectory.get().asFile.absolutePath)
}

val dotNetRuntimeManifestFile = rootProject.file("gradle/test-data/dotnet-runtime.properties")
val dotNetRuntimeManifest = Properties().apply {
    dotNetRuntimeManifestFile.inputStream().use(::load)
}
val dotNetRuntimeVersion = dotNetRuntimeManifest.getProperty("version")
val dotNetRuntimeRoot = dotNetRuntimeManifest.getProperty("archiveRoot")
val dotNetRuntimeSha256 = dotNetRuntimeManifest.getProperty("archiveSha256")
val dotNetRuntimeArchive = rootProject.layout.file(testDataCacheDirectory.map { directory ->
    directory.file("downloads/sha256/$dotNetRuntimeSha256/${dotNetRuntimeManifest.getProperty("archiveName")}").asFile
})
val dotNetRuntimeTestDataDirectory = rootProject.layout.buildDirectory.dir("test-data/dotnet-runtime/$dotNetRuntimeVersion")

val downloadDotNetRuntimeTestSources = tasks.register<DownloadVerifiedFile>("downloadDotNetRuntimeTestSources") {
    group = "verification"
    description = "Downloads and verifies the pinned dotnet-runtime source archive."
    sourceUrl.set(dotNetRuntimeManifest.getProperty("archiveUrl"))
    expectedSha256.set(dotNetRuntimeSha256)
    expectedSize.set(dotNetRuntimeManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(dotNetRuntimeArchive)
}

val prepareDotNetRuntimeTestCorpus = tasks.register<Sync>("prepareDotNetRuntimeTestCorpus") {
    group = "verification"
    description = "Extracts selected dotnet-runtime compression test resources and notices."
    dependsOn(downloadDotNetRuntimeTestSources)
    val corpusPatterns = listOf("LICENSE.TXT",
        "src/libraries/System.IO.Compression/tests/**",
        "src/libraries/System.IO.Compression.ZipFile/tests/**")
    inputs.property("corpusPatterns", corpusPatterns)
    from(downloadDotNetRuntimeTestSources.flatMap { it.destination }.map { archive ->
        tarTree(resources.gzip(archive.asFile))
    }) {
        include(*corpusPatterns.map { "$dotNetRuntimeRoot/$it" }.toTypedArray())
        eachFile {
            val segments = relativePath.segments
            require(segments.size > 1 && segments[0] == dotNetRuntimeRoot) {
                "Unexpected dotnet-runtime source archive path: $relativePath"
            }
            relativePath = RelativePath(true, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(dotNetRuntimeManifestFile) { rename { "UPSTREAM.properties" } }
    into(dotNetRuntimeTestDataDirectory)
}

tasks.named<Test>("tier2Test") {
    dependsOn(prepareDotNetRuntimeTestCorpus)
    inputs.dir(dotNetRuntimeTestDataDirectory)
    systemProperty("arkivo.dotnet-runtime.testDataDirectory", dotNetRuntimeTestDataDirectory.get().asFile.absolutePath)
}

val benchmarkArguments = providers.gradleProperty("benchmarkArgs")

val benchmark by tasks.registering(JavaExec::class) {
    group = "benchmark"
    description = "Runs the Arkivo JMH codec and archive benchmarks."
    dependsOn(tasks.named(benchmarkSourceSet.classesTaskName))
    classpath = benchmarkSourceSet.runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    doFirst {
        if (benchmarkArguments.isPresent) {
            setArgs(
                benchmarkArguments.get()
                    .trim()
                    .split(Regex("\\s+"))
                    .filter(String::isNotEmpty)
            )
        }
    }
}

val jazzerMaxDuration = providers.gradleProperty("jazzerMaxDuration").getOrElse("1m")
val jazzerMaxHeapSize = providers.gradleProperty("jazzerMaxHeapSize").getOrElse("1g")
val jazzerInstrumentation = providers.gradleProperty("jazzerInstrumentation")
    .getOrElse("org.glavo.arkivo.**")
val fuzzTargets = linkedMapOf(
    "fuzzCompressionDecoder" to
            "org.glavo.arkivo.fuzz.CompressionFuzzTest.fuzzCompressionDecoder",
    "fuzzCompressionRoundTrip" to
            "org.glavo.arkivo.fuzz.CompressionFuzzTest.fuzzCompressionRoundTrip",
    "fuzzCompressionEncoderState" to
            "org.glavo.arkivo.fuzz.CompressionFuzzTest.fuzzCompressionEncoderState",
    "fuzzCompressionConfigurations" to
            "org.glavo.arkivo.fuzz.CompressionConfigurationFuzzTest.fuzzCompressionConfigurations",
    "fuzzZstdSeekableRoundTrip" to
            "org.glavo.arkivo.fuzz.ZstdSeekableFuzzTest.fuzzZstdSeekableRoundTrip",
    "fuzzZstdSeekableIndex" to
            "org.glavo.arkivo.fuzz.ZstdSeekableFuzzTest.fuzzZstdSeekableIndex",
    "fuzzTarOuterCompression" to
            "org.glavo.arkivo.fuzz.TarOuterCompressionFuzzTest.fuzzTarOuterCompression",
    "fuzzTarOuterCompressionUpdate" to
            "org.glavo.arkivo.fuzz.TarOuterCompressionFuzzTest.fuzzTarOuterCompressionUpdate",
    "fuzzArchiveStreaming" to
            "org.glavo.arkivo.fuzz.ArchiveFuzzTest.fuzzArchiveStreaming",
    "fuzzArchiveFileSystem" to
            "org.glavo.arkivo.fuzz.ArchiveFuzzTest.fuzzArchiveFileSystem",
    "fuzzArchiveFileSystemMutations" to
            "org.glavo.arkivo.fuzz.ArchiveFileSystemMutationFuzzTest.fuzzArchiveFileSystemMutations",
    "fuzzArchiveWriterState" to
            "org.glavo.arkivo.fuzz.ArchiveWriterFuzzTest.fuzzArchiveWriterState",
    "fuzzArchiveVolumes" to
            "org.glavo.arkivo.fuzz.ArchiveVolumeFuzzTest.fuzzArchiveVolumes",
    "fuzzDMGImage" to
            "org.glavo.arkivo.fuzz.ArchiveFuzzTest.fuzzDMGImage",
    "fuzzFormatDetection" to
            "org.glavo.arkivo.fuzz.FormatDetectionFuzzTest.fuzzFormatDetection"
)

val fuzzTargetTasks = fuzzTargets.map { (taskName, targetMethod) ->
    tasks.register<Test>(taskName) {
        group = "fuzzing"
        description = "Runs the $targetMethod Jazzer target locally."
        dependsOn(tasks.named(fuzzTestSourceSet.classesTaskName))
        testClassesDirs = fuzzTestSourceSet.output.classesDirs
        classpath = fuzzTestSourceSet.runtimeClasspath
        filter {
            includeTestsMatching(targetMethod)
        }
        environment("JAZZER_FUZZ", "1")
        systemProperty("jazzer.instrument", jazzerInstrumentation)
        systemProperty("jazzer.max_duration", jazzerMaxDuration)
        if (taskName in setOf(
                    "fuzzArchiveFileSystem",
                    "fuzzArchiveFileSystemMutations",
                    "fuzzArchiveWriterState",
                    "fuzzTarOuterCompression",
                    "fuzzTarOuterCompressionUpdate",
                    "fuzzArchiveVolumes"
                )) {
            // Arkivo paths belong to an in-memory provider and can never reach the host file system.
            systemProperty(
                "jazzer.disabled_hooks",
                "com.code_intelligence.jazzer.sanitizers.FilePathTraversal"
            )
        }
        maxHeapSize = jazzerMaxHeapSize
        val fuzzWorkingDirectory = rootProject.file(".arkivo-cache/fuzz/$taskName")
        workingDir(fuzzWorkingDirectory)
        doFirst {
            fuzzWorkingDirectory.mkdirs()
        }
        outputs.upToDateWhen { false }
    }
}

val fuzzRegressionTest by tasks.registering(Test::class) {
    group = "fuzzing"
    description = "Runs the deterministic Jazzer seed corpus without coverage-guided mutation."
    dependsOn(tasks.named(fuzzTestSourceSet.classesTaskName))
    testClassesDirs = fuzzTestSourceSet.output.classesDirs
    classpath = fuzzTestSourceSet.runtimeClasspath
    systemProperty("jazzer.instrument", jazzerInstrumentation)
    maxHeapSize = jazzerMaxHeapSize
    val fuzzWorkingDirectory = rootProject.file(".arkivo-cache/fuzz/regression")
    workingDir(fuzzWorkingDirectory)
    doFirst {
        fuzzWorkingDirectory.mkdirs()
    }
    outputs.upToDateWhen { false }
}

tasks.register("fuzzAll") {
    group = "fuzzing"
    description = "Runs every optional local Jazzer target with an independent fuzzing process."
    dependsOn(fuzzTargetTasks)
}

val moduleProjectPaths = listOf(
    ":arkivo-all",
    ":arkivo-base",
    ":arkivo-checksum",
    ":arkivo-checksum-xxhash",
    ":arkivo-archive",
    ":arkivo-archive-codec",
    ":arkivo-archive-7z",
    ":arkivo-archive-all",
    ":arkivo-archive-ar",
    ":arkivo-archive-cpio",
    ":arkivo-archive-dmg",
    ":arkivo-archive-rar",
    ":arkivo-archive-tar",
    ":arkivo-archive-zip",
    ":arkivo-codec",
    ":arkivo-codec-all",
    ":arkivo-codec-bzip2",
    ":arkivo-codec-compress",
    ":arkivo-codec-deflate",
    ":arkivo-codec-lz4",
    ":arkivo-codec-lzip",
    ":arkivo-codec-lzma",
    ":arkivo-codec-ppmd",
    ":arkivo-codec-xz",
    ":arkivo-codec-zstd"
)
val moduleJarTasks = moduleProjectPaths.map { projectPath ->
    project(projectPath).tasks.named<Jar>("jar")
}
val moduleJarFiles = moduleJarTasks.map { jarTask ->
    jarTask.flatMap { it.archiveFile }
}

val builtinCatalogProbe = "org.glavo.arkivo.all.BuiltinCatalogProbe"

val verifyBuiltinCatalogOnClasspath by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Verifies Arkivo's built-in catalogs from the published JARs on the classpath."
    dependsOn(tasks.named("testClasses"), moduleJarTasks)
    classpath = files(sourceSets.test.get().output, moduleJarFiles)
    mainClass.set(builtinCatalogProbe)
}

val verifyBuiltinCatalogOnModulePath by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Verifies Arkivo's built-in catalogs from the published modules."
    dependsOn(tasks.named("testClasses"), moduleJarTasks)
    classpath = sourceSets.test.get().output
    mainClass.set(builtinCatalogProbe)
    doFirst {
        jvmArgs(
            "--module-path",
            moduleJarFiles.joinToString(File.pathSeparator) { it.get().asFile.absolutePath },
            "--add-modules",
            "org.glavo.arkivo.all"
        )
    }
}

val verifyModuleDescriptors by tasks.registering {
    group = "verification"
    description = "Verifies packaged JPMS descriptors and public module boundaries."
    dependsOn(moduleJarTasks)
    inputs.files(moduleJarFiles)

    doLast {
        val expectedModules = setOf(
            "org.glavo.arkivo.all",
            "org.glavo.arkivo.base",
            "org.glavo.arkivo.checksum",
            "org.glavo.arkivo.checksum.xxhash",
            "org.glavo.arkivo.archive",
            "org.glavo.arkivo.archive.codec",
            "org.glavo.arkivo.archive.all",
            "org.glavo.arkivo.archive.ar",
            "org.glavo.arkivo.archive.cpio",
            "org.glavo.arkivo.archive.dmg",
            "org.glavo.arkivo.archive.rar",
            "org.glavo.arkivo.archive.sevenzip",
            "org.glavo.arkivo.archive.tar",
            "org.glavo.arkivo.archive.zip",
            "org.glavo.arkivo.codec",
            "org.glavo.arkivo.codec.all",
            "org.glavo.arkivo.codec.bzip2",
            "org.glavo.arkivo.codec.compress",
            "org.glavo.arkivo.codec.deflate",
            "org.glavo.arkivo.codec.lz4",
            "org.glavo.arkivo.codec.lzip",
            "org.glavo.arkivo.codec.lzma",
            "org.glavo.arkivo.codec.ppmd",
            "org.glavo.arkivo.codec.xz",
            "org.glavo.arkivo.codec.zstd"
        )
        val descriptors = ModuleFinder.of(
            *moduleJarFiles.map { it.get().asFile.toPath() }.toTypedArray()
        ).findAll().associate { reference ->
            reference.descriptor().name() to reference.descriptor()
        }
        check(descriptors.keys == expectedModules) {
            "Packaged Arkivo modules differ from the expected set: " + descriptors.keys
        }
        val forbiddenServiceEntries = setOf(
            "META-INF/services/org.glavo.arkivo.archive.ArkivoFormat",
            "META-INF/services/org.glavo.arkivo.archive.spi.ArkivoStreamingSourceProvider",
            "META-INF/services/org.glavo.arkivo.codec.CompressionFormat"
        )
        moduleJarFiles.forEach { jarFile ->
            val file = jarFile.get().asFile
            JarFile(file).use { jar ->
                val presentEntries = forbiddenServiceEntries.filter { jar.getJarEntry(it) != null }
                check(presentEntries.isEmpty()) {
                    "${file.name} contains removed Arkivo service descriptors: $presentEntries"
                }
            }
        }
        descriptors.values.forEach { descriptor ->
            check(!descriptor.isAutomatic) {
                "Arkivo module must have an explicit descriptor: " + descriptor.name()
            }
            check(!descriptor.isOpen) {
                "Arkivo module must not be open: " + descriptor.name()
            }
        }

        val archiveModule = "org.glavo.arkivo.archive"
        val codecModule = "org.glavo.arkivo.codec"
        val expectedTransitiveRequirements = mapOf(
            "org.glavo.arkivo.all" to setOf(
                archiveModule,
                "org.glavo.arkivo.archive.all",
                "org.glavo.arkivo.checksum",
                "org.glavo.arkivo.checksum.xxhash",
                "org.glavo.arkivo.codec.all"
            ),
            "org.glavo.arkivo.checksum.xxhash" to setOf(
                "org.glavo.arkivo.checksum"
            ),
            "org.glavo.arkivo.archive.all" to setOf(
                archiveModule,
                "org.glavo.arkivo.archive.ar",
                "org.glavo.arkivo.archive.cpio",
                "org.glavo.arkivo.archive.dmg",
                "org.glavo.arkivo.archive.rar",
                "org.glavo.arkivo.archive.sevenzip",
                "org.glavo.arkivo.archive.tar",
                "org.glavo.arkivo.archive.zip"
            ),
            "org.glavo.arkivo.archive.ar" to setOf(archiveModule),
            "org.glavo.arkivo.archive.cpio" to setOf(archiveModule),
            "org.glavo.arkivo.archive.dmg" to setOf(archiveModule),
            "org.glavo.arkivo.archive.rar" to setOf(archiveModule),
            "org.glavo.arkivo.archive.sevenzip" to setOf(archiveModule),
            "org.glavo.arkivo.archive.tar" to setOf(archiveModule, codecModule),
            "org.glavo.arkivo.archive.zip" to setOf(archiveModule),
            "org.glavo.arkivo.codec.all" to setOf(
                codecModule,
                "org.glavo.arkivo.codec.bzip2",
                "org.glavo.arkivo.codec.compress",
                "org.glavo.arkivo.codec.deflate",
                "org.glavo.arkivo.codec.lz4",
                "org.glavo.arkivo.codec.lzip",
                "org.glavo.arkivo.codec.lzma",
                "org.glavo.arkivo.codec.ppmd",
                "org.glavo.arkivo.codec.xz",
                "org.glavo.arkivo.codec.zstd"
            ),
            "org.glavo.arkivo.codec.bzip2" to setOf(codecModule),
            "org.glavo.arkivo.codec.compress" to setOf(codecModule),
            "org.glavo.arkivo.codec.deflate" to setOf(codecModule),
            "org.glavo.arkivo.codec.lz4" to setOf(codecModule),
            "org.glavo.arkivo.codec.lzip" to setOf(codecModule),
            "org.glavo.arkivo.codec.lzma" to setOf(codecModule),
            "org.glavo.arkivo.codec.ppmd" to setOf(codecModule),
            "org.glavo.arkivo.codec.xz" to setOf(
                codecModule,
                "org.glavo.arkivo.codec.lzma"
            ),
            "org.glavo.arkivo.codec.zstd" to setOf(codecModule)
        )
        descriptors.forEach { (moduleName, descriptor) ->
            val actual = descriptor.requires()
                .filter { ModuleDescriptor.Requires.Modifier.TRANSITIVE in it.modifiers() }
                .map { it.name() }
                .toSet()
            val expected = expectedTransitiveRequirements[moduleName].orEmpty()
            check(actual == expected) {
                "$moduleName has transitive requirements $actual instead of $expected"
            }
        }

        val expectedPublicExports = mapOf(
            "org.glavo.arkivo.checksum" to setOf("org.glavo.arkivo.checksum"),
            "org.glavo.arkivo.checksum.xxhash" to setOf("org.glavo.arkivo.checksum.xxhash"),
            archiveModule to setOf("org.glavo.arkivo.archive"),
            "org.glavo.arkivo.archive.ar" to setOf("org.glavo.arkivo.archive.ar"),
            "org.glavo.arkivo.archive.cpio" to setOf("org.glavo.arkivo.archive.cpio"),
            "org.glavo.arkivo.archive.dmg" to setOf("org.glavo.arkivo.archive.dmg"),
            "org.glavo.arkivo.archive.rar" to setOf("org.glavo.arkivo.archive.rar"),
            "org.glavo.arkivo.archive.sevenzip" to setOf("org.glavo.arkivo.archive.sevenzip"),
            "org.glavo.arkivo.archive.tar" to setOf("org.glavo.arkivo.archive.tar"),
            "org.glavo.arkivo.archive.zip" to setOf("org.glavo.arkivo.archive.zip"),
            codecModule to setOf(
                "org.glavo.arkivo.codec",
                "org.glavo.arkivo.codec.transform"
            ),
            "org.glavo.arkivo.codec.bzip2" to setOf("org.glavo.arkivo.codec.bzip2"),
            "org.glavo.arkivo.codec.compress" to setOf("org.glavo.arkivo.codec.compress"),
            "org.glavo.arkivo.codec.deflate" to setOf("org.glavo.arkivo.codec.deflate"),
            "org.glavo.arkivo.codec.lz4" to setOf("org.glavo.arkivo.codec.lz4"),
            "org.glavo.arkivo.codec.lzip" to setOf("org.glavo.arkivo.codec.lzip"),
            "org.glavo.arkivo.codec.lzma" to setOf("org.glavo.arkivo.codec.lzma"),
            "org.glavo.arkivo.codec.ppmd" to setOf("org.glavo.arkivo.codec.ppmd"),
            "org.glavo.arkivo.codec.xz" to setOf("org.glavo.arkivo.codec.xz"),
            "org.glavo.arkivo.codec.zstd" to setOf("org.glavo.arkivo.codec.zstd")
        )
        descriptors.forEach { (moduleName, descriptor) ->
            val actual = descriptor.exports()
                .filter { !it.isQualified }
                .map { it.source() }
                .toSet()
            val expected = expectedPublicExports[moduleName].orEmpty()
            check(actual == expected) {
                "$moduleName publicly exports $actual instead of $expected"
            }
        }

        val expectedQualifiedExports = mapOf(
            codecModule to mapOf(
                "org.glavo.arkivo.codec.internal" to setOf(
                    "org.glavo.arkivo.archive.zip",
                    "org.glavo.arkivo.codec.bzip2",
                    "org.glavo.arkivo.codec.compress",
                    "org.glavo.arkivo.codec.deflate",
                    "org.glavo.arkivo.codec.lz4",
                    "org.glavo.arkivo.codec.lzip",
                    "org.glavo.arkivo.codec.lzma",
                    "org.glavo.arkivo.codec.ppmd",
                    "org.glavo.arkivo.codec.xz",
                    "org.glavo.arkivo.codec.zstd"
                )
            ),
            "org.glavo.arkivo.base" to mapOf(
                "org.glavo.arkivo.internal" to setOf(
                    archiveModule,
                    "org.glavo.arkivo.archive.ar",
                    "org.glavo.arkivo.archive.cpio",
                    "org.glavo.arkivo.archive.dmg",
                    "org.glavo.arkivo.archive.rar",
                    "org.glavo.arkivo.archive.sevenzip",
                    "org.glavo.arkivo.archive.tar",
                    "org.glavo.arkivo.archive.zip",
                    "org.glavo.arkivo.checksum.xxhash",
                    codecModule,
                    "org.glavo.arkivo.codec.deflate",
                    "org.glavo.arkivo.codec.lz4",
                    "org.glavo.arkivo.codec.lzip",
                    "org.glavo.arkivo.codec.xz",
                    "org.glavo.arkivo.codec.zstd"
                )
            ),
            archiveModule to mapOf(
                "org.glavo.arkivo.archive.internal" to setOf(
                    "org.glavo.arkivo.all",
                    "org.glavo.arkivo.archive.codec",
                    "org.glavo.arkivo.archive.ar",
                    "org.glavo.arkivo.archive.cpio",
                    "org.glavo.arkivo.archive.dmg",
                    "org.glavo.arkivo.archive.rar",
                    "org.glavo.arkivo.archive.sevenzip",
                    "org.glavo.arkivo.archive.tar",
                    "org.glavo.arkivo.archive.zip"
                )
            ),

            "org.glavo.arkivo.codec.lzma" to mapOf(
                "org.glavo.arkivo.codec.lzma.internal" to setOf(
                    "org.glavo.arkivo.codec.xz"
                )
            ),
            "org.glavo.arkivo.codec.xz" to mapOf(
                "org.glavo.arkivo.codec.xz.internal.filter" to setOf(
                    "org.glavo.arkivo.archive.sevenzip"
                )
            ),
            "org.glavo.arkivo.codec.ppmd" to mapOf(
                "org.glavo.arkivo.codec.ppmd.internal" to setOf(
                    "org.glavo.arkivo.archive.rar"
                )
            )
        )
        descriptors.forEach { (moduleName, descriptor) ->
            val actual = descriptor.exports()
                .filter { it.isQualified }
                .associate { it.source() to it.targets() }
            val expected = expectedQualifiedExports[moduleName].orEmpty()
            check(actual == expected) {
                "$moduleName has qualified exports $actual instead of $expected"
            }
        }

        val expectedQualifiedOpens = mapOf(
            "org.glavo.arkivo.archive.codec" to mapOf(
                "org.glavo.arkivo.archive.codec.internal" to setOf(archiveModule)
            )
        )
        descriptors.forEach { (moduleName, descriptor) ->
            val actual = descriptor.opens()
                .filter { it.isQualified }
                .associate { it.source() to it.targets() }
            val expected = expectedQualifiedOpens[moduleName].orEmpty()
            check(actual == expected) {
                "$moduleName has qualified opens $actual instead of $expected"
            }
        }

        val fileSystemProviderService = "java.nio.file.spi.FileSystemProvider"
        descriptors.forEach { (moduleName, descriptor) ->
            check(descriptor.uses().isEmpty()) {
                "$moduleName unexpectedly uses services " + descriptor.uses()
            }
        }

        val expectedProviders = mapOf(
            "org.glavo.arkivo.archive.ar" to mapOf(
                fileSystemProviderService to setOf(
                    "org.glavo.arkivo.archive.ar.internal.ArArkivoFileSystemProvider"
                )
            ),
            "org.glavo.arkivo.archive.rar" to mapOf(
                fileSystemProviderService to setOf(
                    "org.glavo.arkivo.archive.rar.internal.RarArkivoFileSystemProvider"
                )
            ),
            "org.glavo.arkivo.archive.dmg" to mapOf(
                fileSystemProviderService to setOf(
                    "org.glavo.arkivo.archive.dmg.internal.DMGArkivoFileSystemProvider"
                )
            ),
            "org.glavo.arkivo.archive.sevenzip" to mapOf(
                fileSystemProviderService to setOf(
                    "org.glavo.arkivo.archive.sevenzip.internal.SevenZipArkivoFileSystemProvider"
                )
            ),
            "org.glavo.arkivo.archive.tar" to mapOf(
                fileSystemProviderService to setOf(
                    "org.glavo.arkivo.archive.tar.internal.TarArkivoFileSystemProvider"
                )
            ),
            "org.glavo.arkivo.archive.zip" to mapOf(
                fileSystemProviderService to setOf(
                    "org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemProvider"
                )
            )
        )
        descriptors.forEach { (moduleName, descriptor) ->
            val actual = descriptor.provides().associate {
                it.service() to it.providers().toSet()
            }
            val expected = expectedProviders[moduleName].orEmpty()
            check(actual == expected) {
                "$moduleName provides $actual instead of $expected"
            }
        }
    }
}

tasks.named("check") {
    dependsOn(
        verifyModuleDescriptors,
        verifyBuiltinCatalogOnClasspath,
        verifyBuiltinCatalogOnModulePath,
        benchmarkSourceSet.classesTaskName
    )
}
