import org.glavo.arkivo.gradle.DownloadVerifiedFile
import org.gradle.api.file.RelativePath
import java.util.Properties

dependencies {
    api(project(":arkivo-codec"))
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
    description = "Runs BZip2 tests against the pinned official reference samples."
    dependsOn(prepareBZip2TestCorpus)
    shouldRunAfter(tasks.test)
    testClassesDirs = realWorldTestSourceSet.output.classesDirs
    classpath = realWorldTestSourceSet.runtimeClasspath
    systemProperty("arkivo.bzip2.testDataDirectory", bzip2TestDataDirectory.get().asFile.absolutePath)
}
