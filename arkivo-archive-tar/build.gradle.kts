dependencies {
    implementation(project(":arkivo-base"))
    api(project(":arkivo-archive"))
    implementation(project(":arkivo-archive-codec"))
    api(project(":arkivo-codec"))
    testImplementation(project(":arkivo-codec-deflate"))
    testImplementation(project(":arkivo-codec-zstd"))
    add("tier2TestImplementation", "org.apache.commons:commons-compress:1.28.0")
}

tasks.named<Test>("tier2Test") {
    inputs.property("gnuTarExecutable", providers.environmentVariable("ARKIVO_GNU_TAR_EXECUTABLE").orElse(""))
    inputs.property("requireGnuTar", providers.environmentVariable("ARKIVO_REQUIRE_GNU_TAR").orElse("false"))
}

val lowHeapStorageProbe by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the TAR indexed-storage probe with a heap smaller than the entry body."
    dependsOn(tasks.named("tier3TestClasses"))
    classpath = sourceSets["tier3Test"].runtimeClasspath
    mainClass.set("org.glavo.arkivo.archive.tar.internal.TarLowHeapStorageProbe")
    maxHeapSize = "32m"
}

tasks.named<Test>("tier3Test") {
    dependsOn(lowHeapStorageProbe)
    failOnNoDiscoveredTests = false
}
