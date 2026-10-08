import java.util.Properties
import org.glavo.arkivo.gradle.DownloadVerifiedFile

dependencies {
    api(project(":arkivo-archive"))
    implementation(project(":arkivo-archive-codec"))
    implementation(project(":arkivo-base"))
    implementation(project(":arkivo-codec"))
    implementation(project(":arkivo-codec-deflate"))
    compileOnly(project(":arkivo-codec-lzma"))
    runtimeOnly(project(":arkivo-codec-lzma"))
    testImplementation(project(":arkivo-codec-bzip2"))
    testImplementation(project(":arkivo-codec-lzma"))
    testImplementation(project(":arkivo-codec-xz"))
    testImplementation(project(":arkivo-codec-zstd"))
    testImplementation("org.tukaani:xz:1.12")
    testImplementation("org.apache.commons:commons-compress:1.28.0")
}

val openJdkZipManifestFile = rootProject.file("gradle/test-data/openjdk-zip.properties")
val openJdkZipManifest = Properties().apply {
    openJdkZipManifestFile.inputStream().use(::load)
}
val openJdkZipDirectory = rootProject.layout.buildDirectory.dir(
    "test-data/openjdk-zip/${openJdkZipManifest.getProperty("version")}")
val openJdkZipCache = rootProject.layout.dir(rootProject.providers.provider {
    rootProject.file(rootProject.providers.gradleProperty("arkivo.testDataCacheDirectory").orNull
        ?: ".arkivo-cache/test-data")
})
val openJdkZipDownloads = openJdkZipManifest.getProperty("files").split(',').map { name ->
    val hash = openJdkZipManifest.getProperty("$name.sha256")
    tasks.register<DownloadVerifiedFile>("downloadOpenJdkZip${name.substringBefore('.')}") {
        group = "verification"
        description = "Downloads and verifies OpenJDK's $name reference file."
        val sourcePath = if (name == "LICENSE") name else "test/jdk/java/util/zip/ZipFile/$name"
        sourceUrl.set("${openJdkZipManifest.getProperty("baseUrl")}/$sourcePath")
        expectedSha256.set(hash)
        expectedSize.set(openJdkZipManifest.getProperty("$name.size").toLong())
        offline.set(gradle.startParameter.isOffline)
        cacheRoot.set(openJdkZipCache)
        cacheMarker.set(openJdkZipCache.map { it.file(".arkivo-test-data-cache") })
        destination.set(openJdkZipCache.map { it.file("downloads/sha256/$hash/$name") })
    }
}
val prepareOpenJdkZipTestCorpus = tasks.register<Sync>("prepareOpenJdkZipTestCorpus") {
    group = "verification"
    description = "Collects pinned OpenJDK ZIP regression sources without compiling or executing them."
    openJdkZipDownloads.forEach { download -> from(download.flatMap { it.destination }) }
    from(openJdkZipManifestFile) { rename { "UPSTREAM.properties" } }
    into(openJdkZipDirectory)
}
tasks.named<Test>("tier2Test") {
    dependsOn(prepareOpenJdkZipTestCorpus)
    inputs.dir(openJdkZipDirectory)
    systemProperty("arkivo.openjdkZip.testDataDirectory", openJdkZipDirectory.get().asFile.absolutePath)
}

val verifyOptionalCompressionFormats by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Verifies ZIP loads and diagnoses operations without optional compression formats."
    dependsOn(tasks.named("testClasses"))

    classpath = sourceSets.test.get().runtimeClasspath.filter { file ->
        val path = file.invariantSeparatorsPath
        sequenceOf("bzip2", "lzma", "xz", "zstd").none { codecModuleName ->
            path.contains("/arkivo-codec-$codecModuleName/")
                    || file.name.startsWith("arkivo-codec-$codecModuleName")
        } && !file.name.startsWith("zstd-jni")
    }
    mainClass.set("org.glavo.arkivo.archive.zip.internal.ZipOptionalFormatProbe")
}

tasks.named("check") {
    dependsOn(verifyOptionalCompressionFormats)
}

val metadataScalabilityFixture =
    layout.buildDirectory.file("performance/zip-50000-entries.zip")

val prepareMetadataScalabilityFixture by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Creates the 50,000-entry ZIP fixture used by the low-heap index probe."
    dependsOn(tasks.named("tier3TestClasses"))
    classpath = sourceSets["tier3Test"].runtimeClasspath
    mainClass.set("org.glavo.arkivo.archive.zip.internal.ZipMetadataScalabilityProbe")
    args("create", metadataScalabilityFixture.get().asFile.absolutePath)
    outputs.file(metadataScalabilityFixture)
}

val lowHeapMetadataScalabilityProbe by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Indexes 50,000 ZIP entries with a 48 MiB maximum heap."
    dependsOn(prepareMetadataScalabilityFixture)
    classpath = sourceSets["tier3Test"].runtimeClasspath
    mainClass.set("org.glavo.arkivo.archive.zip.internal.ZipMetadataScalabilityProbe")
    args("verify", metadataScalabilityFixture.get().asFile.absolutePath)
    maxHeapSize = "48m"
    inputs.file(metadataScalabilityFixture)
}

val decodedChannelScalabilityFixture =
    layout.buildDirectory.file("performance/zip-80m-decoded-entry.zip")

val prepareDecodedChannelScalabilityFixture by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Creates the 80 MiB decoded ZIP entry used by the low-heap channel probe."
    dependsOn(tasks.named("tier3TestClasses"))
    classpath = sourceSets["tier3Test"].runtimeClasspath
    mainClass.set("org.glavo.arkivo.archive.zip.internal.ZipDecodedChannelScalabilityProbe")
    args("create", decodedChannelScalabilityFixture.get().asFile.absolutePath)
    outputs.file(decodedChannelScalabilityFixture)
}

val lowHeapDecodedChannelScalabilityProbe by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Opens and seeks through an 80 MiB decoded ZIP entry with a 32 MiB maximum heap."
    dependsOn(prepareDecodedChannelScalabilityFixture)
    classpath = sourceSets["tier3Test"].runtimeClasspath
    mainClass.set("org.glavo.arkivo.archive.zip.internal.ZipDecodedChannelScalabilityProbe")
    args("verify", decodedChannelScalabilityFixture.get().asFile.absolutePath)
    maxHeapSize = "32m"
    inputs.file(decodedChannelScalabilityFixture)
}

tasks.named<Test>("tier3Test") {
    dependsOn(lowHeapMetadataScalabilityProbe, lowHeapDecodedChannelScalabilityProbe)
}
