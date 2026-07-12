dependencies {
    api(project(":arkivo-base"))
    implementation("com.github.luben:zstd-jni:1.5.7-9")
    testImplementation("org.tukaani:xz:1.12")
    testImplementation("org.apache.commons:commons-compress:1.28.0")
}
