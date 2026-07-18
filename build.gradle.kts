import java.nio.file.Files
import java.util.Properties
import org.glavo.arkivo.gradle.DownloadVerifiedFile
import org.gradle.api.file.RelativePath
import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.testing.base.TestingExtension

plugins {
    base
}

val testDataCacheDirectory = layout.dir(providers.provider {
    file(providers.gradleProperty("arkivo.testDataCacheDirectory").orNull ?: ".arkivo-cache/test-data")
})

group = "org.glavo"
version = providers.gradleProperty("releaseVersion").getOrElse("1.0-SNAPSHOT")

allprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java-library")

    dependencies {
        "compileOnly"("org.jetbrains:annotations:26.1.0")
        "testCompileOnly"("org.jetbrains:annotations:26.1.0")
        "testImplementation"(platform("org.junit:junit-bom:6.0.0"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 17
    }

    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
        (options as org.gradle.external.javadoc.StandardJavadocDocletOptions).apply {
            addBooleanOption("Xdoclint:all,-missing", true)
            addBooleanOption("Werror", true)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    extensions.configure<TestingExtension> {
        suites {
            register<JvmTestSuite>("tier2Test") {
                useJUnitJupiter()
                dependencies {
                    implementation(project())
                }
                targets.configureEach {
                    testTask.configure {
                        group = "verification"
                        description = "Runs the optional Tier 2 compatibility and corpus tests."
                        shouldRunAfter(tasks.named("test"))
                    }
                }
            }

            register<JvmTestSuite>("tier3Test") {
                useJUnitJupiter()
                dependencies {
                    implementation(project())
                }
                targets.configureEach {
                    testTask.configure {
                        group = "verification"
                        description = "Runs the optional Tier 3 scalability and resource tests."
                        shouldRunAfter(tasks.named("tier2Test"))
                    }
                }
            }
        }
    }

    listOf("tier2Test", "tier3Test").forEach { suiteName ->
        configurations.named("${suiteName}Implementation") {
            extendsFrom(configurations.named("testImplementation"))
        }
        configurations.named("${suiteName}CompileOnly") {
            extendsFrom(configurations.named("testCompileOnly"))
        }
        configurations.named("${suiteName}RuntimeOnly") {
            extendsFrom(configurations.named("testRuntimeOnly"))
        }
    }
}

apply(from = "gradle/publishing.gradle.kts")

tasks.register<Delete>("cleanTestDataCache") {
    group = "build"
    description = "Deletes the persistent project-local test data download cache."
    delete(testDataCacheDirectory)

    doFirst {
        val directory = testDataCacheDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        if (!Files.exists(directory)) {
            return@doFirst
        }
        if (Files.isSymbolicLink(directory)) {
            throw GradleException("Refusing to delete a symbolic-link test data cache directory: $directory")
        }
        val marker = directory.resolve(".arkivo-test-data-cache")
        val markerContent = "Arkivo test data cache v1\n"
        if (Files.isSymbolicLink(marker)
            || !Files.isRegularFile(marker)
            || Files.readString(marker) != markerContent
        ) {
            throw GradleException("Refusing to delete an unmarked test data cache directory: $directory")
        }
    }
}

tasks.register("testDataCacheReport") {
    group = "help"
    description = "Reports the persistent project-local test data download cache size."

    doLast {
        val directory = testDataCacheDirectory.get().asFile.toPath()
        if (!Files.exists(directory)) {
            logger.lifecycle("Test data cache is empty: {}", directory)
            return@doLast
        }

        var fileCount = 0L
        var totalSize = 0L
        Files.walk(directory).use { paths ->
            paths.filter(Files::isRegularFile)
                .filter { it.fileName.toString() != ".arkivo-test-data-cache" }
                .forEach { path ->
                    fileCount++
                    totalSize = Math.addExact(totalSize, Files.size(path))
            }
        }
        logger.lifecycle("Test data cache: {} files, {} bytes at {}", fileCount, totalSize, directory)
    }
}

val commonsCompressManifestFile = file("gradle/test-data/commons-compress.properties")
val commonsCompressManifest = Properties().apply {
    commonsCompressManifestFile.inputStream().use(::load)
}
val commonsCompressVersion = commonsCompressManifest.getProperty("version")
val commonsCompressArchiveRoot = commonsCompressManifest.getProperty("archiveRoot")
val commonsCompressArchiveName = commonsCompressManifest.getProperty("archiveName")
val commonsCompressArchiveSha256 = commonsCompressManifest.getProperty("archiveSha256")
val commonsCompressResourceFileCount =
    commonsCompressManifest.getProperty("resourceFileCount").toLong()
val commonsCompressResourceTotalSize =
    commonsCompressManifest.getProperty("resourceTotalSize").toLong()
val commonsCompressArchive = layout.file(testDataCacheDirectory.map { directory ->
    directory.file(
        "downloads/sha256/$commonsCompressArchiveSha256/$commonsCompressArchiveName"
    ).asFile
})
val commonsCompressTestDataDirectory = layout.buildDirectory.dir(
    "test-data/commons-compress/$commonsCompressVersion"
)

val downloadCommonsCompressTestSources = tasks.register<DownloadVerifiedFile>(
    "downloadCommonsCompressTestSources"
) {
    group = "verification"
    description = "Downloads and verifies the pinned Apache Commons Compress source release."
    sourceUrl.set(commonsCompressManifest.getProperty("archiveUrl"))
    expectedSha256.set(commonsCompressArchiveSha256)
    expectedSize.set(commonsCompressManifest.getProperty("archiveSize").toLong())
    offline.set(gradle.startParameter.isOffline)
    cacheRoot.set(testDataCacheDirectory)
    cacheMarker.set(testDataCacheDirectory.map { it.file(".arkivo-test-data-cache") })
    destination.set(commonsCompressArchive)
}

val prepareCommonsCompressTestCorpus = tasks.register<Sync>(
    "prepareCommonsCompressTestCorpus"
) {
    group = "verification"
    description = "Extracts the complete test resources from the pinned Commons Compress source release."
    dependsOn(downloadCommonsCompressTestSources)
    inputs.property("resourceFileCount", commonsCompressResourceFileCount)
    inputs.property("resourceTotalSize", commonsCompressResourceTotalSize)

    from(downloadCommonsCompressTestSources.flatMap { it.destination }.map { archive ->
        tarTree(resources.gzip(archive.asFile))
    }) {
        include(
            "$commonsCompressArchiveRoot/LICENSE.txt",
            "$commonsCompressArchiveRoot/NOTICE.txt",
            "$commonsCompressArchiveRoot/src/test/resources/**"
        )
        eachFile {
            val segments = relativePath.segments
            require(segments.size >= 2 && segments[0] == commonsCompressArchiveRoot) {
                "Unexpected Commons Compress source archive path: $relativePath"
            }
            relativePath = RelativePath(relativePath.isFile, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(commonsCompressManifestFile) {
        rename { "UPSTREAM.properties" }
    }
    into(commonsCompressTestDataDirectory)

    doLast {
        val resourceRoot = commonsCompressTestDataDirectory.get()
            .dir("src/test/resources")
            .asFile
            .toPath()
        var actualFileCount = 0L
        var actualTotalSize = 0L
        Files.walk(resourceRoot).use { paths ->
            paths.filter(Files::isRegularFile).forEach { path ->
                actualFileCount++
                actualTotalSize = Math.addExact(actualTotalSize, Files.size(path))
            }
        }
        check(actualFileCount == commonsCompressResourceFileCount) {
            "Commons Compress resource count is $actualFileCount instead of " +
                    commonsCompressResourceFileCount
        }
        check(actualTotalSize == commonsCompressResourceTotalSize) {
            "Commons Compress resource size is $actualTotalSize instead of " +
                    commonsCompressResourceTotalSize
        }
    }
}

listOf("tier2Test", "tier3Test").forEach { taskName ->
    project(":arkivo-all").tasks.named<Test>(taskName) {
        dependsOn(prepareCommonsCompressTestCorpus)
        inputs.dir(commonsCompressTestDataDirectory)
        systemProperty(
            "arkivo.commonsCompress.testDataDirectory",
            commonsCompressTestDataDirectory.get().asFile.absolutePath
        )
    }
}

val tier1Test = tasks.register("tier1Test") {
    group = "verification"
    description = "Runs the default Tier 1 test suites across all modules."
    dependsOn(subprojects.map { "${it.path}:test" })
}

val tier2Test = tasks.register("tier2Test") {
    group = "verification"
    description = "Runs only the optional Tier 2 test suites across all modules."
    dependsOn(subprojects.map { "${it.path}:tier2Test" })
}

val tier3Test = tasks.register("tier3Test") {
    group = "verification"
    description = "Runs only the optional Tier 3 test suites and probes across all modules."
    dependsOn(subprojects.map { "${it.path}:tier3Test" })
}

val checkTier2 = tasks.register("checkTier2") {
    group = "verification"
    description = "Runs all verification through Tier 2."
    dependsOn(tasks.named("check"), subprojects.map { "${it.path}:check" }, tier2Test)
}

tasks.register("checkTier3") {
    group = "verification"
    description = "Runs all verification through Tier 3."
    dependsOn(checkTier2, tier3Test)
}
