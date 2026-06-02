group = "org.glavo"
version = "1.0-SNAPSHOT"

allprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependencies {
        "compileOnly"("org.jetbrains:annotations:26.1.0")
        "testCompileOnly"("org.jetbrains:annotations:26.1.0")
        "testImplementation"(platform("org.junit:junit-bom:6.0.0"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release = 17
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
