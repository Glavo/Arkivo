import java.lang.module.ModuleDescriptor
import java.lang.module.ModuleFinder
import java.util.Properties
import java.util.jar.JarFile
import org.glavo.arkivo.gradle.DownloadVerifiedFile
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.file.RelativePath

plugins {
    `java-test-fixtures`
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
    add("tier3TestImplementation", testFixtures(project()))
    add(benchmarkSourceSet.compileOnlyConfigurationName, "org.jetbrains:annotations:26.1.0")
    add(benchmarkSourceSet.implementationConfigurationName, "org.openjdk.jmh:jmh-core:1.37")
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
                    "org.glavo.arkivo.archive.cpio",
                    "org.glavo.arkivo.archive.dmg",
                    "org.glavo.arkivo.archive.rar",
                    "org.glavo.arkivo.archive.sevenzip",
                    "org.glavo.arkivo.archive.zip",
                    "org.glavo.arkivo.checksum.xxhash",
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
