// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

import java.util.Properties

dependencies {
    "tier2TestImplementation"(project(":arkivo-archive-zip"))
    "tier2TestImplementation"("org.apache.commons:commons-compress:1.28.0")
}

tasks.named<Test>("tier2Test") {
    dependsOn(":arkivo-all:prepareJSZipTestCorpus", ":arkivo-all:prepareZip4jTestCorpus")
    for (corpus in listOf("jszip", "zip4j")) {
        val manifestFile = rootProject.file("gradle/test-data/$corpus.properties")
        val manifest = Properties().apply { manifestFile.inputStream().use(::load) }
        val directory = rootProject.layout.buildDirectory.dir("test-data/$corpus/${manifest.getProperty("version")}")
        inputs.file(manifestFile)
        inputs.dir(directory)
        systemProperty("arkivo.$corpus.testDataDirectory", directory.get().asFile.absolutePath)
    }
}

tasks.withType<Jar>().configureEach {
    from("NOTICE") { into("META-INF") }
    from("UPSTREAM.properties") { into("META-INF/chardetng") }
    from(rootProject.file("LICENSES/Chardetng-MIT.txt")) { into("META-INF/LICENSES") }
}
