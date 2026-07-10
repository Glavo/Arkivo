dependencies {
    api(project(":arkivo-base"))
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.12")
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
