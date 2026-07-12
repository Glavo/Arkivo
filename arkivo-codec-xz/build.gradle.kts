dependencies {
    api(project(":arkivo-codec"))
    implementation(project(":arkivo-codec-bcj"))
    implementation(project(":arkivo-codec-delta"))
    implementation(project(":arkivo-codec-lzma"))
    testImplementation("org.tukaani:xz:1.12")
}
