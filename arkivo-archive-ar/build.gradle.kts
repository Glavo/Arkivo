dependencies {
    api(project(":arkivo-archive"))
}

val lowHeapStorageProbe by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the AR indexed-storage probe with a heap smaller than the member body."
    dependsOn(tasks.named("tier3TestClasses"))
    classpath = sourceSets["tier3Test"].runtimeClasspath
    mainClass.set("org.glavo.arkivo.archive.ar.internal.ArLowHeapStorageProbe")
    maxHeapSize = "32m"
}

tasks.named<Test>("tier3Test") {
    dependsOn(lowHeapStorageProbe)
    failOnNoDiscoveredTests = false
}
