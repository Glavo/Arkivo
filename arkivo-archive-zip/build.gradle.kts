dependencies {
    api(project(":arkivo-archive"))
    implementation(project(":arkivo-codec"))
    compileOnly(project(":arkivo-codec-lzma"))

    testImplementation(project(":arkivo-codec-bzip2"))
    testImplementation(project(":arkivo-codec-deflate64"))
    testImplementation(project(":arkivo-codec-lzma"))
    testImplementation(project(":arkivo-codec-xz"))
    testImplementation(project(":arkivo-codec-zstd"))
    testImplementation("org.tukaani:xz:1.12")
    testImplementation("org.apache.commons:commons-compress:1.28.0")
}

val verifyOptionalCompressionCodecs by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Verifies ZIP loads and diagnoses operations without optional compression codecs."
    dependsOn(tasks.named("testClasses"))

    classpath = sourceSets.test.get().runtimeClasspath.filter { file ->
        val path = file.invariantSeparatorsPath
        sequenceOf("bzip2", "deflate64", "xz", "zstd").none { codecName ->
            path.contains("/arkivo-codec-$codecName/")
                    || file.name.startsWith("arkivo-codec-$codecName")
        } && !file.name.startsWith("zstd-jni")
    }
    mainClass.set("org.glavo.arkivo.archive.zip.internal.ZipOptionalCodecProbe")
}

tasks.named("check") {
    dependsOn(verifyOptionalCompressionCodecs)
}

val metadataScalabilityFixture =
    layout.buildDirectory.file("performance/zip-50000-entries.zip")

val prepareMetadataScalabilityFixture by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Creates the 50,000-entry ZIP fixture used by the low-heap index probe."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.glavo.arkivo.archive.zip.internal.ZipMetadataScalabilityProbe")
    args("create", metadataScalabilityFixture.get().asFile.absolutePath)
    outputs.file(metadataScalabilityFixture)
}

val lowHeapMetadataScalabilityProbe by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Indexes 50,000 ZIP entries with a 48 MiB maximum heap."
    dependsOn(prepareMetadataScalabilityFixture)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.glavo.arkivo.archive.zip.internal.ZipMetadataScalabilityProbe")
    args("verify", metadataScalabilityFixture.get().asFile.absolutePath)
    maxHeapSize = "48m"
    inputs.file(metadataScalabilityFixture)
}

tasks.named<Test>("test") {
    dependsOn(lowHeapMetadataScalabilityProbe)
}
