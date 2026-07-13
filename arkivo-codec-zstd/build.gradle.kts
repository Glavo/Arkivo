import org.glavo.arkivo.gradle.DownloadVerifiedFile
import org.gradle.api.file.RelativePath
import java.util.Properties

dependencies {
    api(project(":arkivo-codec"))
    implementation("com.github.luben:zstd-jni:1.5.7-9")
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

val realWorldTestSourceSet = sourceSets.create("realWorldTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}

configurations.named(realWorldTestSourceSet.implementationConfigurationName) {
    extendsFrom(configurations.testImplementation.get())
}
configurations.named(realWorldTestSourceSet.compileOnlyConfigurationName) {
    extendsFrom(configurations.testCompileOnly.get())
}
configurations.named(realWorldTestSourceSet.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.testRuntimeOnly.get())
}

tasks.register<Test>("realWorldTest") {
    group = "verification"
    description = "Runs Zstandard tests against the pinned official golden corpus."
    dependsOn(prepareZstdTestCorpus)
    shouldRunAfter(tasks.test)
    testClassesDirs = realWorldTestSourceSet.output.classesDirs
    classpath = realWorldTestSourceSet.runtimeClasspath
    systemProperty("arkivo.zstd.testDataDirectory", zstdTestDataDirectory.get().asFile.absolutePath)
}
