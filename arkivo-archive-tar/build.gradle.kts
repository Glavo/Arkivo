dependencies {
    api(project(":arkivo-archive"))
    api(project(":arkivo-codec"))
    testImplementation(project(":arkivo-codec-deflate"))
}

val lowHeapStorageProbe by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the TAR indexed-storage probe with a heap smaller than the entry body."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.glavo.arkivo.archive.tar.internal.TarLowHeapStorageProbe")
    maxHeapSize = "32m"
}

tasks.named<Test>("test") {
    dependsOn(lowHeapStorageProbe)
}
