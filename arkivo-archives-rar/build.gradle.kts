/*
 * Copyright (c) 2026 Glavo
 * SPDX-License-Identifier: MPL-2.0
 */

import java.util.zip.ZipFile

dependencies {
    api(project(":arkivo-base"))
}

val verifyPublishedRarModule by tasks.registering {
    group = "verification"
    description = "Verifies that the published RAR module contains only Arkivo decoder classes."
    dependsOn(tasks.jar)

    val archiveFile = tasks.jar.flatMap { it.archiveFile }
    inputs.file(archiveFile)

    doLast {
        ZipFile(archiveFile.get().asFile).use { archive ->
            check(archive.getEntry("module-info.class") != null) {
                "The RAR archive does not contain its JPMS descriptor"
            }
            check(archive.getEntry(
                "org/glavo/arkivo/rar/internal/Rar4Lz15Decoder.class"
            ) != null) {
                "The RAR archive does not contain the Arkivo RAR1.5 decoder"
            }
            check(archive.getEntry(
                "org/glavo/arkivo/rar/internal/Rar4Lz20Decoder.class"
            ) != null) {
                "The RAR archive does not contain the Arkivo RAR2 decoder"
            }
            check(archive.getEntry(
                "org/glavo/arkivo/rar/internal/Rar4Lz29Decoder.class"
            ) != null) {
                "The RAR archive does not contain the Arkivo RAR4 decoder"
            }
            check(archive.getEntry(
                "org/glavo/arkivo/rar/internal/Rar3Vm.class"
            ) != null) {
                "The RAR archive does not contain the Arkivo RAR3 virtual machine"
            }
            check(archive.getEntry(
                "org/glavo/arkivo/rar/internal/Rar3StandardFilters.class"
            ) != null) {
                "The RAR archive does not contain the Arkivo RAR3 standard filters"
            }
            check(archive.getEntry(
                "org/glavo/arkivo/rar/internal/Rar3PpmAllocator.class"
            ) != null && archive.getEntry(
                "org/glavo/arkivo/rar/internal/Rar3PpmRangeDecoder.class"
            ) != null && archive.getEntry(
                "org/glavo/arkivo/rar/internal/Rar3PpmModel.class"
            ) != null) {
                "The RAR archive does not contain the Arkivo RAR3 PPMd decoder"
            }
            check(archive.getEntry(
                "org/glavo/arkivo/rar/internal/Rar5LzDecoder.class"
            ) != null) {
                "The RAR archive does not contain the Arkivo RAR5 decoder"
            }
            check(archive.getEntry(
                "org/glavo/arkivo/rar/internal/Rar13Cipher.class"
            ) != null && archive.getEntry(
                "org/glavo/arkivo/rar/internal/Rar15Cipher.class"
            ) != null && archive.getEntry(
                "org/glavo/arkivo/rar/internal/Rar20Cipher.class"
            ) != null) {
                "The RAR archive does not contain the Arkivo legacy cipher implementations"
            }
            val forbiddenPrefixes = listOf("com/github/junrar/", "be/stef/", "org/slf4j/")
            check(archive.entries().asSequence().none { entry ->
                forbiddenPrefixes.any(entry.name::startsWith)
            }) {
                "The RAR archive contains a removed third-party decoder package"
            }
            check(archive.getEntry("META-INF/licenses/unrar-LICENSE.txt") == null) {
                "The RAR archive contains the obsolete UnRAR license"
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyPublishedRarModule)
}

val lowHeapStorageProbe by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the RAR stored-entry cache probe with a heap smaller than the entry body."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.glavo.arkivo.rar.RarLowHeapStorageProbe")
    maxHeapSize = "32m"
}

val baseJar = project(":arkivo-base").tasks.named<Jar>("jar")

tasks.named<Test>("test") {
    dependsOn(lowHeapStorageProbe, tasks.jar, baseJar)
    inputs.file(tasks.jar.flatMap { it.archiveFile })
    inputs.file(baseJar.flatMap { it.archiveFile })
    doFirst {
        systemProperty(
            "arkivo.rar.jar",
            tasks.jar.get().archiveFile.get().asFile.absolutePath
        )
        systemProperty(
            "arkivo.base.jar",
            baseJar.get().archiveFile.get().asFile.absolutePath
        )
    }
}
