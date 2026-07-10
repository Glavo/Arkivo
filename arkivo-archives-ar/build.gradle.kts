dependencies {
    api(project(":arkivo-base"))
}

val lowHeapStorageProbe by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the AR indexed-storage probe with a heap smaller than the member body."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.glavo.arkivo.ar.internal.ArLowHeapStorageProbe")
    maxHeapSize = "32m"
}

tasks.named<Test>("test") {
    dependsOn(lowHeapStorageProbe)
}
