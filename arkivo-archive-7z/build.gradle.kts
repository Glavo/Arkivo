import java.util.zip.ZipFile

dependencies {
    api(project(":arkivo-archive"))
    implementation(project(":arkivo-codec"))
    implementation(project(":arkivo-codec-bcj"))
    implementation(project(":arkivo-codec-delta"))
    compileOnly(project(":arkivo-codec-lzma"))
    testImplementation(project(":arkivo-codec-bzip2"))
    testImplementation(project(":arkivo-codec-deflate"))
    testImplementation(project(":arkivo-codec-deflate64"))
    testImplementation(project(":arkivo-codec-lzma"))
    testImplementation(project(":arkivo-codec-zstd"))
    testImplementation("org.tukaani:xz:1.12")
    testImplementation("org.apache.commons:commons-compress:1.28.0")
}

val verifyPublishedSevenZipWriter by tasks.registering {
    group = "verification"
    description = "Verifies that the published 7z module contains the Arkivo archive writer."
    dependsOn(tasks.jar)

    val archiveFile = tasks.jar.flatMap { it.archiveFile }
    inputs.file(archiveFile)

    doLast {
        ZipFile(archiveFile.get().asFile).use { archive ->
            check(archive.getEntry(
                "org/glavo/arkivo/archive/sevenzip/internal/SevenZipArchiveWriter.class"
            ) != null) {
                "The 7z archive does not contain the Arkivo archive writer"
            }
            check(archive.getEntry(
                "org/glavo/arkivo/archive/sevenzip/internal/SevenZipContentMethodsSupport.class"
            ) == null) {
                "The 7z archive contains the obsolete Commons Compress writer adapter"
            }
        }
    }
}

val verifyOptionalCompressionCodecs by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Verifies 7z loads and diagnoses operations without optional compression codecs."
    dependsOn(tasks.named("testClasses"))

    classpath = sourceSets.test.get().runtimeClasspath.filter { file ->
        val path = file.invariantSeparatorsPath
        sequenceOf("bzip2", "deflate", "deflate64", "ppmd", "zstd").none { codecName ->
            path.contains("/arkivo-codec-$codecName/")
                    || file.name.startsWith("arkivo-codec-$codecName")
        }
    }
    mainClass.set("org.glavo.arkivo.archive.sevenzip.internal.SevenZipOptionalCodecProbe")
}

tasks.named("check") {
    dependsOn(verifyPublishedSevenZipWriter, verifyOptionalCompressionCodecs)
}

val lowHeapUpdateProbe by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the 7z large-entry update probe with a heap smaller than the entry body."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.glavo.arkivo.archive.sevenzip.SevenZipLowHeapUpdateProbe")
    maxHeapSize = "32m"
}

tasks.named<Test>("test") {
    dependsOn(lowHeapUpdateProbe)
}
