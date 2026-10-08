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

val zstdReferenceDirectory = rootProject.layout.buildDirectory.dir("test-data/zstd-reference/$zstdTestDataVersion")
val prepareZstdReferenceSources = tasks.register<Sync>("prepareZstdReferenceSources") {
    group = "verification"
    description = "Extracts Zstandard's reference tests and sources for test-only native tools."
    val includedPaths = listOf("$zstdTestDataArchiveRoot/LICENSE", "$zstdTestDataArchiveRoot/COPYING",
        "$zstdTestDataArchiveRoot/lib/**", "$zstdTestDataArchiveRoot/programs/**",
        "$zstdTestDataArchiveRoot/tests/**", "$zstdTestDataArchiveRoot/contrib/seekable_format/**",
        "$zstdTestDataArchiveRoot/contrib/externalSequenceProducer/**")
    inputs.property("includedPaths", includedPaths)
    from(downloadZstdTestSources.flatMap { it.destination }.map { tarTree(resources.gzip(it.asFile)) }) {
        include(includedPaths)
        eachFile {
            val segments = relativePath.segments
            require(segments.size > 1 && segments[0] == zstdTestDataArchiveRoot) {
                "Unexpected Zstandard reference path: $relativePath"
            }
            relativePath = RelativePath(relativePath.isFile, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    from(zstdTestDataManifestFile) { rename { "UPSTREAM.properties" } }
    into(zstdReferenceDirectory)
}

val zstdReferenceCompiler = providers.environmentVariable("ARKIVO_ZSTD_C_COMPILER")
val decodeCorpusExecutable = layout.buildDirectory.file("reference-tools/decodecorpus" +
    if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
tasks.register<Exec>("buildZstdDecodeCorpus") {
    group = "verification"
    description = "Builds the verified decodecorpus tool with ARKIVO_ZSTD_C_COMPILER (GCC, Clang, or Zig)."
    dependsOn(prepareZstdReferenceSources)
    inputs.dir(zstdReferenceDirectory)
    inputs.property("compiler", zstdReferenceCompiler.orElse(""))
    outputs.file(decodeCorpusExecutable)
    doFirst {
        val compiler = zstdReferenceCompiler.orNull
            ?: throw GradleException("Set ARKIVO_ZSTD_C_COMPILER to a GCC, Clang, or Zig executable.")
        val root = zstdReferenceDirectory.get().asFile
        val executable = decodeCorpusExecutable.get().asFile
        executable.parentFile.mkdirs()
        val sources = root.resolve("lib").walkTopDown().filter {
            it.isFile && it.extension == "c" && it.parentFile.name in
                setOf("common", "compress", "decompress", "dictBuilder") && it.name != "zstd_compress.c"
        }.map { it.absolutePath }.sorted().toList()
        val command = mutableListOf(compiler)
        if (File(compiler).nameWithoutExtension.equals("zig", ignoreCase = true)) command.add("cc")
        command.addAll(listOf("-O2", "-DZSTD_DISABLE_ASM", "-DXXH_NAMESPACE=ZSTD_",
            "-I${root.resolve("lib")}", "-I${root.resolve("lib/common")}", "-I${root.resolve("programs")}"))
        command.addAll(sources)
        command.addAll(listOf(root.resolve("programs/util.c").absolutePath,
            root.resolve("programs/timefn.c").absolutePath, root.resolve("tests/decodecorpus.c").absolutePath,
            "-lm", "-o", executable.absolutePath))
        commandLine(command)
    }
}

val fseReferenceSource = layout.projectDirectory.file("src/tier2Test/c/zstd_fse_reference.c")
val fseReferenceExecutable = layout.buildDirectory.file("reference-tools/zstd-fse-reference" +
    if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
tasks.register<Exec>("buildZstdFseReference") {
    group = "verification"
    description = "Builds the test-only FSE reference with ARKIVO_ZSTD_C_COMPILER (GCC, Clang, or Zig)."
    dependsOn(prepareZstdReferenceSources)
    inputs.dir(zstdReferenceDirectory)
    inputs.file(fseReferenceSource)
    inputs.property("compiler", zstdReferenceCompiler.orElse(""))
    outputs.file(fseReferenceExecutable)
    doFirst {
        val compiler = zstdReferenceCompiler.orNull
            ?: throw GradleException("Set ARKIVO_ZSTD_C_COMPILER to a GCC, Clang, or Zig executable.")
        val root = zstdReferenceDirectory.get().asFile
        val executable = fseReferenceExecutable.get().asFile
        executable.parentFile.mkdirs()
        val command = mutableListOf(compiler)
        if (File(compiler).nameWithoutExtension.equals("zig", ignoreCase = true)) command.add("cc")
        command.addAll(listOf("-O2", "-DZSTD_DISABLE_ASM", "-DXXH_NAMESPACE=ZSTD_",
            "-I${root.resolve("lib/common")}"))
        command.addAll(listOf("lib/common/debug.c", "lib/common/entropy_common.c",
            "lib/common/error_private.c", "lib/common/fse_decompress.c", "lib/compress/fse_compress.c",
            "lib/compress/hist.c").map { root.resolve(it).absolutePath })
        command.addAll(listOf(fseReferenceSource.asFile.absolutePath, "-o", executable.absolutePath))
        commandLine(command)
    }
}

val huffmanReferenceSource = layout.projectDirectory.file("src/tier2Test/c/zstd_huffman_reference.c")
val huffmanReferenceExecutable = layout.buildDirectory.file("reference-tools/zstd-huffman-reference" +
    if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
tasks.register<Exec>("buildZstdHuffmanReference") {
    group = "verification"
    description = "Builds the test-only Huffman reference with ARKIVO_ZSTD_C_COMPILER (GCC, Clang, or Zig)."
    dependsOn(prepareZstdReferenceSources)
    inputs.dir(zstdReferenceDirectory)
    inputs.file(huffmanReferenceSource)
    inputs.property("compiler", zstdReferenceCompiler.orElse(""))
    outputs.file(huffmanReferenceExecutable)
    doFirst {
        val compiler = zstdReferenceCompiler.orNull
            ?: throw GradleException("Set ARKIVO_ZSTD_C_COMPILER to a GCC, Clang, or Zig executable.")
        val root = zstdReferenceDirectory.get().asFile
        val executable = huffmanReferenceExecutable.get().asFile
        executable.parentFile.mkdirs()
        val command = mutableListOf(compiler)
        if (File(compiler).nameWithoutExtension.equals("zig", ignoreCase = true)) command.add("cc")
        command.addAll(listOf("-O2", "-DZSTD_DISABLE_ASM", "-DXXH_NAMESPACE=ZSTD_",
            "-I${root.resolve("lib")}", "-I${root.resolve("lib/common")}", "-I${root.resolve("tests/fuzz")}"))
        command.addAll(listOf("lib/common/debug.c", "lib/common/entropy_common.c", "lib/common/error_private.c",
            "lib/common/fse_decompress.c", "lib/common/zstd_common.c", "lib/compress/fse_compress.c",
            "lib/compress/hist.c", "lib/compress/huf_compress.c", "lib/decompress/huf_decompress.c",
            "tests/fuzz/fuzz_helpers.c", "tests/fuzz/fuzz_data_producer.c").map { root.resolve(it).absolutePath })
        command.addAll(listOf(huffmanReferenceSource.asFile.absolutePath, "-o", executable.absolutePath))
        commandLine(command)
    }
}

val blockReferenceSource = layout.projectDirectory.file("src/tier2Test/c/zstd_block_reference.c")
val blockReferenceExecutable = layout.buildDirectory.file("reference-tools/zstd-block-reference" +
    if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
tasks.register<Exec>("buildZstdBlockReference") {
    group = "verification"
    description = "Builds the test-only block reference with ARKIVO_ZSTD_C_COMPILER (GCC, Clang, or Zig)."
    dependsOn(prepareZstdReferenceSources)
    inputs.dir(zstdReferenceDirectory)
    inputs.file(blockReferenceSource)
    inputs.property("compiler", zstdReferenceCompiler.orElse(""))
    outputs.file(blockReferenceExecutable)
    doFirst {
        val compiler = zstdReferenceCompiler.orNull
            ?: throw GradleException("Set ARKIVO_ZSTD_C_COMPILER to a GCC, Clang, or Zig executable.")
        val root = zstdReferenceDirectory.get().asFile
        val executable = blockReferenceExecutable.get().asFile
        executable.parentFile.mkdirs()
        val command = mutableListOf(compiler)
        if (File(compiler).nameWithoutExtension.equals("zig", ignoreCase = true)) command.add("cc")
        command.addAll(listOf("-O2", "-DZSTD_DISABLE_ASM", "-DXXH_NAMESPACE=ZSTD_",
            "-I${root.resolve("lib")}", "-I${root.resolve("lib/common")}", "-I${root.resolve("tests/fuzz")}"))
        command.addAll(root.resolve("lib").walkTopDown().filter {
            it.isFile && it.extension == "c" && it.parentFile.name in setOf("common", "compress", "decompress")
        }.map { it.absolutePath }.sorted().toList())
        command.addAll(listOf(root.resolve("tests/fuzz/fuzz_helpers.c").absolutePath,
            root.resolve("tests/fuzz/fuzz_data_producer.c").absolutePath,
            blockReferenceSource.asFile.absolutePath, "-o", executable.absolutePath))
        commandLine(command)
    }
}

val prefixReferenceSource = layout.projectDirectory.file("src/tier2Test/c/zstd_prefix_reference.c")
val prefixReferenceExecutable = layout.buildDirectory.file("reference-tools/zstd-prefix-reference" +
    if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
tasks.register<Exec>("buildZstdPrefixReference") {
    group = "verification"
    description = "Builds the test-only prefix reference with ARKIVO_ZSTD_C_COMPILER (GCC, Clang, or Zig)."
    dependsOn(prepareZstdReferenceSources)
    inputs.dir(zstdReferenceDirectory)
    inputs.file(prefixReferenceSource)
    inputs.property("compiler", zstdReferenceCompiler.orElse(""))
    outputs.file(prefixReferenceExecutable)
    doFirst {
        val compiler = zstdReferenceCompiler.orNull
            ?: throw GradleException("Set ARKIVO_ZSTD_C_COMPILER to a GCC, Clang, or Zig executable.")
        val root = zstdReferenceDirectory.get().asFile
        val executable = prefixReferenceExecutable.get().asFile
        executable.parentFile.mkdirs()
        val command = mutableListOf(compiler)
        if (File(compiler).nameWithoutExtension.equals("zig", ignoreCase = true)) command.add("cc")
        command.addAll(listOf("-O2", "-DZSTD_DISABLE_ASM", "-DXXH_NAMESPACE=ZSTD_",
            "-I${root.resolve("lib")}", "-I${root.resolve("lib/common")}", "-I${root.resolve("tests/fuzz")}"))
        command.addAll(root.resolve("lib").walkTopDown().filter {
            it.isFile && it.extension == "c" && it.parentFile.name in setOf("common", "compress", "decompress")
        }.map { it.absolutePath }.sorted().toList())
        command.addAll(listOf(root.resolve("tests/fuzz/fuzz_helpers.c").absolutePath,
            root.resolve("tests/fuzz/fuzz_data_producer.c").absolutePath,
            prefixReferenceSource.asFile.absolutePath, "-o", executable.absolutePath))
        commandLine(command)
    }
}

listOf("RoundTrip" to "round_trip", "StreamRoundTrip" to "stream_round_trip",
    "DictionaryLoader" to "dictionary_loader", "RawDictionary" to "raw_dictionary",
    "DictionaryRoundTrip" to "dictionary_round_trip",
    "DictionaryDecompress" to "dictionary_decompress").forEach { (taskSuffix, sourceSuffix) ->
    val referenceSource = layout.projectDirectory.file("src/tier2Test/c/zstd_${sourceSuffix}_reference.c")
    val referenceExecutable = layout.buildDirectory.file("reference-tools/zstd-${sourceSuffix.replace('_', '-')}-reference" +
        if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
    tasks.register<Exec>("buildZstd${taskSuffix}Reference") {
        group = "verification"
        description = "Builds the official $sourceSuffix target with ARKIVO_ZSTD_C_COMPILER (GCC, Clang, or Zig)."
        dependsOn(prepareZstdReferenceSources)
        inputs.dir(zstdReferenceDirectory)
        inputs.file(referenceSource)
        inputs.property("compiler", zstdReferenceCompiler.orElse(""))
        outputs.file(referenceExecutable)
        doFirst {
            val compiler = zstdReferenceCompiler.orNull
                ?: throw GradleException("Set ARKIVO_ZSTD_C_COMPILER to a GCC, Clang, or Zig executable.")
            val root = zstdReferenceDirectory.get().asFile
            val executable = referenceExecutable.get().asFile
            executable.parentFile.mkdirs()
            val command = mutableListOf(compiler)
            if (File(compiler).nameWithoutExtension.equals("zig", ignoreCase = true)) command.add("cc")
            command.addAll(listOf("-O2", "-DZSTD_DISABLE_ASM", "-DXXH_NAMESPACE=ZSTD_",
                "-DZSTD_MULTITHREAD", "-DFUZZING_ASSERT_VALID_SEQUENCE",
                "-I${root.resolve("lib")}", "-I${root.resolve("lib/common")}",
                "-I${root.resolve("lib/compress")}",
                "-I${root.resolve("tests/fuzz")}", "-I${root.resolve("contrib/externalSequenceProducer")}"))
            if (!System.getProperty("os.name").startsWith("Windows")) command.add("-pthread")
            command.addAll(root.resolve("lib").walkTopDown().filter {
                it.isFile && it.extension == "c" && it.parentFile.name in
                    setOf("common", "compress", "decompress", "dictBuilder")
            }.map { it.absolutePath }.sorted().toList())
            command.addAll(listOf("tests/fuzz/fuzz_helpers.c", "tests/fuzz/fuzz_data_producer.c",
                "tests/fuzz/zstd_helpers.c", "contrib/externalSequenceProducer/sequence_producer.c")
                .map { root.resolve(it).absolutePath })
            command.addAll(listOf(referenceSource.asFile.absolutePath, "-o", executable.absolutePath))
            commandLine(command)
        }
    }
}

tasks.named<Test>("tier2Test") {
    group = "verification"
    description = "Runs extended Zstandard interoperability tests and the pinned official golden corpus."
    dependsOn(prepareZstdTestCorpus, prepareZstdReferenceSources)
    // Native tools remain optional, but explicitly requested rebuilds must precede their consumers.
    mustRunAfter(tasks.matching {
        it.name == "buildZstdDecodeCorpus" ||
            it.name.startsWith("buildZstd") && it.name.endsWith("Reference")
    })
    shouldRunAfter(tasks.test)
    inputs.dir(zstdTestDataDirectory)
    systemProperty("arkivo.zstd.testDataDirectory", zstdTestDataDirectory.get().asFile.absolutePath)
    inputs.dir(zstdReferenceDirectory)
    inputs.property("decodeCorpusExecutable",
        providers.environmentVariable("ARKIVO_ZSTD_DECODECORPUS_EXECUTABLE").orElse(""))
    inputs.property("requireDecodeCorpus",
        providers.environmentVariable("ARKIVO_REQUIRE_ZSTD_DECODECORPUS").orElse("false"))
    inputs.property("fseReferenceExecutable",
        providers.environmentVariable("ARKIVO_ZSTD_FSE_EXECUTABLE").orElse(""))
    inputs.property("requireFseReference",
        providers.environmentVariable("ARKIVO_REQUIRE_ZSTD_FSE").orElse("false"))
    inputs.property("huffmanReferenceExecutable",
        providers.environmentVariable("ARKIVO_ZSTD_HUFFMAN_EXECUTABLE").orElse(""))
    inputs.property("requireHuffmanReference",
        providers.environmentVariable("ARKIVO_REQUIRE_ZSTD_HUFFMAN").orElse("false"))
    inputs.property("blockReferenceExecutable",
        providers.environmentVariable("ARKIVO_ZSTD_BLOCK_EXECUTABLE").orElse(""))
    inputs.property("requireBlockReference",
        providers.environmentVariable("ARKIVO_REQUIRE_ZSTD_BLOCK").orElse("false"))
    inputs.property("prefixReferenceExecutable",
        providers.environmentVariable("ARKIVO_ZSTD_PREFIX_EXECUTABLE").orElse(""))
    inputs.property("requirePrefixReference",
        providers.environmentVariable("ARKIVO_REQUIRE_ZSTD_PREFIX").orElse("false"))
    inputs.property("roundTripReferenceExecutable",
        providers.environmentVariable("ARKIVO_ZSTD_ROUND_TRIP_EXECUTABLE").orElse(""))
    inputs.property("requireRoundTripReference",
        providers.environmentVariable("ARKIVO_REQUIRE_ZSTD_ROUND_TRIP").orElse("false"))
    inputs.property("streamRoundTripReferenceExecutable",
        providers.environmentVariable("ARKIVO_ZSTD_STREAM_ROUND_TRIP_EXECUTABLE").orElse(""))
    inputs.property("requireStreamRoundTripReference",
        providers.environmentVariable("ARKIVO_REQUIRE_ZSTD_STREAM_ROUND_TRIP").orElse("false"))
    inputs.property("dictionaryLoaderReferenceExecutable",
        providers.environmentVariable("ARKIVO_ZSTD_DICTIONARY_LOADER_EXECUTABLE").orElse(""))
    inputs.property("requireDictionaryLoaderReference",
        providers.environmentVariable("ARKIVO_REQUIRE_ZSTD_DICTIONARY_LOADER").orElse("false"))
    inputs.property("rawDictionaryReferenceExecutable",
        providers.environmentVariable("ARKIVO_ZSTD_RAW_DICTIONARY_EXECUTABLE").orElse(""))
    inputs.property("requireRawDictionaryReference",
        providers.environmentVariable("ARKIVO_REQUIRE_ZSTD_RAW_DICTIONARY").orElse("false"))
    inputs.property("dictionaryRoundTripReferenceExecutable",
        providers.environmentVariable("ARKIVO_ZSTD_DICTIONARY_ROUND_TRIP_EXECUTABLE").orElse(""))
    inputs.property("requireDictionaryRoundTripReference",
        providers.environmentVariable("ARKIVO_REQUIRE_ZSTD_DICTIONARY_ROUND_TRIP").orElse("false"))
    inputs.property("dictionaryDecompressReferenceExecutable",
        providers.environmentVariable("ARKIVO_ZSTD_DICTIONARY_DECOMPRESS_EXECUTABLE").orElse(""))
    inputs.property("requireDictionaryDecompressReference",
        providers.environmentVariable("ARKIVO_REQUIRE_ZSTD_DICTIONARY_DECOMPRESS").orElse("false"))
    // Rebuilt native references must invalidate tests even when their configured paths are unchanged.
    // Missing optional tools remain the tests' responsibility, including required-reference CI failures.
    inputs.files(providers.provider {
        listOf("DECODECORPUS", "FSE", "HUFFMAN", "BLOCK", "PREFIX", "ROUND_TRIP", "STREAM_ROUND_TRIP",
            "DICTIONARY_LOADER", "RAW_DICTIONARY", "DICTIONARY_ROUND_TRIP", "DICTIONARY_DECOMPRESS").mapNotNull { key ->
            providers.environmentVariable("ARKIVO_ZSTD_${key}_EXECUTABLE").orNull
        }.filter { it.isNotBlank() }.map { file(it) }.filter { it.isFile }
    }).withPropertyName("nativeReferenceExecutables").withPathSensitivity(PathSensitivity.NONE)
    systemProperty("arkivo.zstd.referenceDirectory", zstdReferenceDirectory.get().asFile.absolutePath)
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
