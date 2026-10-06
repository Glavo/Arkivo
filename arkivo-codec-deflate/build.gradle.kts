dependencies {
    api(project(":arkivo-codec"))
    implementation(project(":arkivo-base"))
    testImplementation("com.jcraft:jzlib:1.1.3")
    testImplementation("org.apache.commons:commons-compress:1.28.0")
}

// Tier 2 reuses the fixed-schedule comparison helper from the ordinary test source set.
sourceSets.named("tier2Test") {
    compileClasspath += sourceSets.test.get().output
    runtimeClasspath += sourceSets.test.get().output
}
