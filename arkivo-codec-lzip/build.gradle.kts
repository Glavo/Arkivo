/*
 * Copyright (c) 2026 Glavo
 * SPDX-License-Identifier: MPL-2.0
 */

import java.util.Properties

dependencies {
    api(project(":arkivo-codec"))
    implementation(project(":arkivo-base"))
    implementation(project(":arkivo-codec-lzma"))
    testImplementation("org.tukaani:xz:1.12")
}

val xzTestDataManifest = Properties().apply {
    rootProject.file("gradle/test-data/xz.properties").inputStream().use(::load)
}
val xzTestDataDirectory = rootProject.layout.buildDirectory.dir(
    "test-data/xz/${xzTestDataManifest.getProperty("version")}"
)

tasks.named<Test>("tier2Test") {
    group = "verification"
    description = "Runs lzip tests against the pinned official XZ Utils decoder corpus."
    dependsOn(":arkivo-codec-xz:prepareXZTestCorpus")
    shouldRunAfter(tasks.test)
    inputs.dir(xzTestDataDirectory)
    systemProperty("arkivo.lzip.testDataDirectory", xzTestDataDirectory.get().asFile.absolutePath)
}
