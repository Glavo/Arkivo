dependencies {
    api(project(":arkivo-codec"))
    implementation(project(":arkivo-base"))
    testImplementation("com.jcraft:jzlib:1.1.3")
    testImplementation("org.apache.commons:commons-compress:1.28.0")
}
