dependencies {
    api(project(":arkivo-base"))
    implementation(project(":arkivo-codecs-filters"))
    implementation(project(":arkivo-codecs-lzma"))
    testImplementation("org.tukaani:xz:1.12")
}
