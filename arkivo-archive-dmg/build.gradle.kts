dependencies {
    api(project(":arkivo-archive"))
    implementation(project(":arkivo-base"))
    implementation(project(":arkivo-checksum"))
    implementation(project(":arkivo-codec"))
    implementation(project(":arkivo-codec-bzip2"))
    implementation(project(":arkivo-codec-deflate"))
    implementation(project(":arkivo-codec-xz"))
}

tasks.named<Test>("tier2Test") {
    val testDataDirectory = providers.gradleProperty("arkivo.libdmgHfsplus.testDataDirectory")
        .orElse(providers.systemProperty("arkivo.libdmgHfsplus.testDataDirectory"))
    testDataDirectory.orNull?.let { directory ->
        systemProperty("arkivo.libdmgHfsplus.testDataDirectory", directory)
    }
}
