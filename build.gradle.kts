import java.nio.file.Files

plugins {
    base
}

val testDataCacheDirectory = layout.dir(providers.provider {
    file(providers.gradleProperty("arkivo.testDataCacheDirectory").orNull ?: ".arkivo-cache/test-data")
})

group = "org.glavo"
version = providers.gradleProperty("releaseVersion").getOrElse("1.0-SNAPSHOT")

allprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java-library")

    dependencies {
        "compileOnly"("org.jetbrains:annotations:26.1.0")
        "testCompileOnly"("org.jetbrains:annotations:26.1.0")
        "testImplementation"(platform("org.junit:junit-bom:6.0.0"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    tasks.withType<JavaCompile> {
        options.release = 17
    }

    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
        (options as org.gradle.external.javadoc.StandardJavadocDocletOptions).apply {
            addBooleanOption("Xdoclint:all,-missing", true)
            addBooleanOption("Werror", true)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

apply(from = "gradle/publishing.gradle.kts")

tasks.register<Delete>("cleanTestDataCache") {
    group = "build"
    description = "Deletes the persistent project-local test data download cache."
    delete(testDataCacheDirectory)

    doFirst {
        val directory = testDataCacheDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        if (!Files.exists(directory)) {
            return@doFirst
        }
        if (Files.isSymbolicLink(directory)) {
            throw GradleException("Refusing to delete a symbolic-link test data cache directory: $directory")
        }
        val marker = directory.resolve(".arkivo-test-data-cache")
        val markerContent = "Arkivo test data cache v1\n"
        if (!Files.isRegularFile(marker) || Files.readString(marker) != markerContent) {
            throw GradleException("Refusing to delete an unmarked test data cache directory: $directory")
        }
    }
}

tasks.register("testDataCacheReport") {
    group = "help"
    description = "Reports the persistent project-local test data download cache size."

    doLast {
        val directory = testDataCacheDirectory.get().asFile.toPath()
        if (!Files.exists(directory)) {
            logger.lifecycle("Test data cache is empty: {}", directory)
            return@doLast
        }

        var fileCount = 0L
        var totalSize = 0L
        Files.walk(directory).use { paths ->
            paths.filter(Files::isRegularFile)
                .filter { it.fileName.toString() != ".arkivo-test-data-cache" }
                .forEach { path ->
                    fileCount++
                    totalSize = Math.addExact(totalSize, Files.size(path))
            }
        }
        logger.lifecycle("Test data cache: {} files, {} bytes at {}", fileCount, totalSize, directory)
    }
}

tasks.register("realWorldTest") {
    group = "verification"
    description = "Runs opt-in real-world test corpus verification across supported modules."
    dependsOn(
        ":arkivo-codec-bzip2:realWorldTest",
        ":arkivo-codec-all:realWorldTest",
        ":arkivo-codec-xz:realWorldTest",
        ":arkivo-codec-zstd:realWorldTest"
    )
}
