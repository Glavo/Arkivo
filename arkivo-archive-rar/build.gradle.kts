/*
 * Copyright (c) 2026 Glavo
 * SPDX-License-Identifier: MPL-2.0
 */

import java.util.zip.ZipFile

dependencies {
    api(project(":arkivo-archive"))
    implementation(project(":arkivo-archive-codec"))
    implementation(project(":arkivo-base"))
    implementation(project(":arkivo-codec-ppmd"))
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
                "org/glavo/arkivo/archive/rar/internal/Rar4Lz15Decoder.class"
            ) != null) {
                "The RAR archive does not contain the Arkivo RAR1.5 decoder"
            }
            check(archive.getEntry(
                "org/glavo/arkivo/archive/rar/internal/Rar4Lz20Decoder.class"
            ) != null) {
                "The RAR archive does not contain the Arkivo RAR2 decoder"
            }
            check(archive.getEntry(
                "org/glavo/arkivo/archive/rar/internal/Rar4Lz29Decoder.class"
            ) != null) {
                "The RAR archive does not contain the Arkivo RAR4 decoder"
            }
            check(archive.getEntry(
                "org/glavo/arkivo/archive/rar/internal/Rar3Vm.class"
            ) != null) {
                "The RAR archive does not contain the Arkivo RAR3 virtual machine"
            }
            check(archive.getEntry(
                "org/glavo/arkivo/archive/rar/internal/Rar3StandardFilters.class"
            ) != null) {
                "The RAR archive does not contain the Arkivo RAR3 standard filters"
            }
            check(archive.getEntry(
                "org/glavo/arkivo/archive/rar/internal/Rar3PPMdRangeDecoder.class"
            ) != null) {
                "The RAR archive does not contain the Arkivo RAR3 PPMd range decoder"
            }
            check(archive.getEntry(
                "org/glavo/arkivo/archive/rar/internal/Rar5LzDecoder.class"
            ) != null) {
                "The RAR archive does not contain the Arkivo RAR5 decoder"
            }
            check(archive.getEntry(
                "org/glavo/arkivo/archive/rar/internal/Rar13Cipher.class"
            ) != null && archive.getEntry(
                "org/glavo/arkivo/archive/rar/internal/Rar15Cipher.class"
            ) != null && archive.getEntry(
                "org/glavo/arkivo/archive/rar/internal/Rar20Cipher.class"
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
    dependsOn(tasks.named("tier3TestClasses"))
    classpath = sourceSets["tier3Test"].runtimeClasspath
    mainClass.set("org.glavo.arkivo.archive.rar.RarLowHeapStorageProbe")
    maxHeapSize = "32m"
}

val archiveJar = project(":arkivo-archive").tasks.named<Jar>("jar")
val archiveCodecJar = project(":arkivo-archive-codec").tasks.named<Jar>("jar")
val internalBaseJar = project(":arkivo-base").tasks.named<Jar>("jar")
val codecJar = project(":arkivo-codec").tasks.named<Jar>("jar")
val ppmdJar = project(":arkivo-codec-ppmd").tasks.named<Jar>("jar")

tasks.named<Test>("test") {
    dependsOn(tasks.jar, archiveJar, archiveCodecJar, internalBaseJar, codecJar, ppmdJar)
    inputs.file(tasks.jar.flatMap { it.archiveFile })
    inputs.file(archiveJar.flatMap { it.archiveFile })
    inputs.file(archiveCodecJar.flatMap { it.archiveFile })
    inputs.file(internalBaseJar.flatMap { it.archiveFile })
    inputs.file(codecJar.flatMap { it.archiveFile })
    inputs.file(ppmdJar.flatMap { it.archiveFile })
    doFirst {
        systemProperty(
            "arkivo.rar.jar",
            tasks.jar.get().archiveFile.get().asFile.absolutePath
        )
        systemProperty(
            "arkivo.archive.jar",
            archiveJar.get().archiveFile.get().asFile.absolutePath
        )
        systemProperty(
            "arkivo.archive-codec.jar",
            archiveCodecJar.get().archiveFile.get().asFile.absolutePath
        )
        systemProperty(
            "arkivo.internal-base.jar",
            internalBaseJar.get().archiveFile.get().asFile.absolutePath
        )
        systemProperty(
            "arkivo.codec.jar",
            codecJar.get().archiveFile.get().asFile.absolutePath
        )
        systemProperty(
            "arkivo.ppmd.jar",
            ppmdJar.get().archiveFile.get().asFile.absolutePath
        )
    }
}

tasks.named<Test>("tier3Test") {
    dependsOn(lowHeapStorageProbe)
    failOnNoDiscoveredTests = false
}
