plugins {
    java
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.jetbrains:annotations:26.1.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}
