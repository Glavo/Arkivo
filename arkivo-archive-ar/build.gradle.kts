import java.util.Properties
import org.glavo.arkivo.gradle.DownloadVerifiedFile

dependencies {
    implementation(project(":arkivo-base"))
    api(project(":arkivo-archive"))
    implementation(project(":arkivo-archive-codec"))
}

val llvmArManifestFile = rootProject.file("gradle/test-data/llvm-ar.properties")
val llvmArManifest = Properties().apply { llvmArManifestFile.inputStream().use(::load) }
val llvmArDirectory = rootProject.layout.buildDirectory.dir(
    "test-data/llvm-ar/${llvmArManifest.getProperty("version")}")
val llvmArCache = rootProject.layout.dir(rootProject.providers.provider {
    rootProject.file(rootProject.providers.gradleProperty("arkivo.testDataCacheDirectory").orNull
        ?: ".arkivo-cache/test-data")
})
val llvmArDownloads = llvmArManifest.getProperty("files").split(',').map { name ->
    val hash = llvmArManifest.getProperty("$name.sha256")
    tasks.register<DownloadVerifiedFile>("downloadLlvmAr${name.replace('.', '-')}") {
        group = "verification"
        description = "Downloads and verifies LLVM's $name AR reference file."
        val sourcePath = when {
            name == "LICENSE.TXT" -> name
            name.endsWith(".test") -> "test/tools/llvm-ar/$name"
            else -> "test/tools/llvm-ar/Inputs/$name"
        }
        sourceUrl.set("${llvmArManifest.getProperty("baseUrl")}/$sourcePath")
        expectedSha256.set(hash)
        expectedSize.set(llvmArManifest.getProperty("$name.size").toLong())
        offline.set(gradle.startParameter.isOffline)
        cacheRoot.set(llvmArCache)
        cacheMarker.set(llvmArCache.map { it.file(".arkivo-test-data-cache") })
        destination.set(llvmArCache.map { it.file("downloads/sha256/$hash/$name") })
    }
}
val prepareLlvmArTestCorpus = tasks.register<Sync>("prepareLlvmArTestCorpus") {
    group = "verification"
    description = "Collects pinned LLVM AR fixtures, reference tests, and license."
    llvmArDownloads.forEach { download -> from(download.flatMap { it.destination }) }
    from(llvmArManifestFile) { rename { "UPSTREAM.properties" } }
    into(llvmArDirectory)
}
tasks.named<Test>("tier2Test") {
    dependsOn(prepareLlvmArTestCorpus)
    inputs.dir(llvmArDirectory)
    inputs.property("llvmArExecutable", providers.environmentVariable("ARKIVO_LLVM_AR_EXECUTABLE").orElse(""))
    inputs.property("requireLlvmAr", providers.environmentVariable("ARKIVO_REQUIRE_LLVM_AR").orElse("false"))
    systemProperty("arkivo.llvmAr.testDataDirectory", llvmArDirectory.get().asFile.absolutePath)
}

val lowHeapStorageProbe by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the AR indexed-storage probe with a heap smaller than the member body."
    dependsOn(tasks.named("tier3TestClasses"))
    classpath = sourceSets["tier3Test"].runtimeClasspath
    mainClass.set("org.glavo.arkivo.archive.ar.internal.ArLowHeapStorageProbe")
    maxHeapSize = "32m"
}

tasks.named<Test>("tier3Test") {
    dependsOn(lowHeapStorageProbe)
    failOnNoDiscoveredTests = false
}
