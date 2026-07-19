dependencies {
    api(project(":arkivo-archive"))
    implementation(project(":arkivo-archive-codec"))
    api(project(":arkivo-codec"))
    testImplementation(project(":arkivo-codec-deflate"))
    testImplementation(project(":arkivo-codec-zstd"))
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
