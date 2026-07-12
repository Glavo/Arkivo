import java.util.zip.ZipFile

dependencies {
    api(project(":arkivo-base"))
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
                "org/glavo/arkivo/sevenzip/internal/SevenZipArchiveWriter.class"
            ) != null) {
                "The 7z archive does not contain the Arkivo archive writer"
            }
            check(archive.getEntry(
                "org/glavo/arkivo/sevenzip/internal/SevenZipContentMethodsSupport.class"
            ) == null) {
                "The 7z archive contains the obsolete Commons Compress writer adapter"
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyPublishedSevenZipWriter)
}

val lowHeapUpdateProbe by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the 7z large-entry update probe with a heap smaller than the entry body."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.glavo.arkivo.sevenzip.SevenZipLowHeapUpdateProbe")
    maxHeapSize = "32m"
}

tasks.named<Test>("test") {
    dependsOn(lowHeapUpdateProbe)
}
