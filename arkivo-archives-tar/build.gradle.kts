dependencies {
    api(project(":arkivo-base"))
}

val lowHeapStorageProbe by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the TAR indexed-storage probe with a heap smaller than the entry body."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.glavo.arkivo.tar.internal.TarLowHeapStorageProbe")
    maxHeapSize = "32m"
}

tasks.named<Test>("test") {
    dependsOn(lowHeapStorageProbe)
}
