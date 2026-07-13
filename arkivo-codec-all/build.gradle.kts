import org.glavo.arkivo.gradle.DownloadVerifiedFile
import org.gradle.api.file.RelativePath
import java.util.Properties

dependencies {
    api(project(":arkivo-codec"))
    api(project(":arkivo-codec-bzip2"))
    api(project(":arkivo-codec-deflate"))
    api(project(":arkivo-codec-deflate64"))
    api(project(":arkivo-codec-bcj"))
    api(project(":arkivo-codec-delta"))
    api(project(":arkivo-codec-gzip"))
    api(project(":arkivo-codec-lzma"))
    api(project(":arkivo-codec-xz"))
    api(project(":arkivo-codec-zlib"))
    api(project(":arkivo-codec-zstd"))
    testImplementation("org.apache.commons:commons-compress:1.28.0")
}

val commonsCompressManifestFile = rootProject.file("gradle/test-data/commons-compress.properties")
val commonsCompressManifest = Properties().apply {
    commonsCompressManifestFile.inputStream().use(::load)
}
val commonsCompressVersion = commonsCompressManifest.getProperty("version")
val commonsCompressBaseUrl = commonsCompressManifest.getProperty("baseUrl")
val commonsCompressFileIds = commonsCompressManifest.getProperty("files")
    .split(',')
    .map(String::trim)
val testDataCacheDirectory = rootProject.layout.dir(rootProject.providers.provider {
    rootProject.file(
        rootProject.providers.gradleProperty("arkivo.testDataCacheDirectory").orNull
            ?: ".arkivo-cache/test-data"
    )
})
val commonsCompressTestDataDirectory = rootProject.layout.buildDirectory.dir(
    "test-data/commons-compress/$commonsCompressVersion"
)

val commonsCompressDownloads = commonsCompressFileIds.associateWith { fileId ->
    val propertyPrefix = "file.$fileId."
    val sourcePath = commonsCompressManifest.getProperty(propertyPrefix + "path")
    val sha256 = commonsCompressManifest.getProperty(propertyPrefix + "sha256")
    val fileName = sourcePath.substringAfterLast('/')
    val taskSegment = fileId.replaceFirstChar { character -> character.uppercase() }
    val destination = rootProject.layout.file(testDataCacheDirectory.map { directory ->
        directory.file("downloads/sha256/$sha256/$fileName").asFile
    })

    tasks.register<DownloadVerifiedFile>("downloadCommonsCompress$taskSegment") {
        group = "verification"
        description = "Downloads and verifies Apache Commons Compress test data file $sourcePath."
        sourceUrl.set(commonsCompressBaseUrl + sourcePath)
        expectedSha256.set(sha256)
        expectedSize.set(commonsCompressManifest.getProperty(propertyPrefix + "size").toLong())
        offline.set(gradle.startParameter.isOffline)
        cacheRoot.set(testDataCacheDirectory)
        cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
        this.destination.set(destination)
    }
}

val prepareCommonsCompressTestCorpus = tasks.register<Sync>("prepareCommonsCompressTestCorpus") {
    group = "verification"
    description = "Prepares the pinned Apache Commons Compress codec test corpus."
    dependsOn(commonsCompressDownloads.values)

    commonsCompressDownloads.forEach { (fileId, download) ->
        val sourcePath = commonsCompressManifest.getProperty("file.$fileId.path")
        from(download.flatMap { it.destination }) {
            eachFile {
                relativePath = RelativePath(true, *sourcePath.split('/').toTypedArray())
            }
        }
    }
    from(commonsCompressManifestFile) {
        rename { "UPSTREAM.properties" }
    }
    includeEmptyDirs = false
    into(commonsCompressTestDataDirectory)
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
    description = "Runs Deflate-family tests against the pinned Apache Commons Compress corpus."
    dependsOn(prepareCommonsCompressTestCorpus)
    shouldRunAfter(tasks.test)
    testClassesDirs = realWorldTestSourceSet.output.classesDirs
    classpath = realWorldTestSourceSet.runtimeClasspath
    systemProperty(
        "arkivo.commonsCompress.testDataDirectory",
        commonsCompressTestDataDirectory.get().asFile.absolutePath
    )
}
