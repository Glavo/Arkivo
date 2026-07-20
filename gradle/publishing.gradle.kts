import java.lang.module.ModuleDescriptor
import java.util.jar.JarFile
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.plugins.signing.SigningExtension
import org.w3c.dom.Element

data class ArkivoPublicationMetadata(
    val displayName: String,
    val description: String
)

val publicationMetadata = mapOf(
    "arkivo-all" to ArkivoPublicationMetadata(
        "Arkivo",
        "Aggregates all Arkivo archive formats, checksum algorithms, and compression codecs."
    ),
    "arkivo-base" to ArkivoPublicationMetadata(
        "Arkivo Base",
        "Provides internal low-level primitives shared by Arkivo modules."
    ),
    "arkivo-checksum" to ArkivoPublicationMetadata(
        "Arkivo Checksum API",
        "Provides reusable checksum algorithms, incremental accumulators, and immutable checksum values."
    ),
    "arkivo-archive" to ArkivoPublicationMetadata(
        "Arkivo Archive API",
        "Provides archive format discovery, NIO file system contracts, streaming APIs, and storage abstractions."
    ),
    "arkivo-archive-codec" to ArkivoPublicationMetadata(
        "Arkivo Archive Codec Integration",
        "Integrates archive probing with installed Arkivo compression formats."
    ),
    "arkivo-archive-all" to ArkivoPublicationMetadata(
        "Arkivo Archive Formats",
        "Aggregates all Arkivo archive format implementations."
    ),
    "arkivo-archive-ar" to ArkivoPublicationMetadata(
        "Arkivo AR Archive",
        "Provides AR archive file system and streaming support."
    ),
    "arkivo-archive-cpio" to ArkivoPublicationMetadata(
        "Arkivo CPIO Archive",
        "Provides streaming support for portable ASCII and historical binary CPIO archives."
    ),
    "arkivo-archive-rar" to ArkivoPublicationMetadata(
        "Arkivo RAR Archive",
        "Provides read-only RAR archive file system and streaming support."
    ),
    "arkivo-archive-tar" to ArkivoPublicationMetadata(
        "Arkivo TAR Archive",
        "Provides TAR archive file system and streaming support with optional outer compression."
    ),
    "arkivo-archive-zip" to ArkivoPublicationMetadata(
        "Arkivo ZIP Archive",
        "Provides ZIP archive file system, streaming, mutation, encryption, and split-volume support."
    ),
    "arkivo-archive-7z" to ArkivoPublicationMetadata(
        "Arkivo 7z Archive",
        "Provides 7z archive file system, streaming, mutation, encryption, solid, and split-volume support."
    ),
    "arkivo-codec" to ArkivoPublicationMetadata(
        "Arkivo Codec API",
        "Provides buffer-first compression codec contracts, channel adapters, immutable configurations, discovery, and byte transforms."
    ),
    "arkivo-codec-all" to ArkivoPublicationMetadata(
        "Arkivo Compression Codecs",
        "Aggregates all Arkivo compression codec and byte-transform implementations."
    ),
    "arkivo-codec-bzip2" to ArkivoPublicationMetadata(
        "Arkivo BZip2 Codec",
        "Provides pure Java BZip2 buffer engines and channel adapters."
    ),
    "arkivo-codec-compress" to ArkivoPublicationMetadata(
        "Arkivo Unix Compress Codec",
        "Provides pure Java Unix compress and LZW buffer engines and channel adapters."
    ),
    "arkivo-codec-deflate" to ArkivoPublicationMetadata(
        "Arkivo Deflate Codecs",
        "Provides pure Java raw Deflate, Deflate64, gzip, and zlib buffer engines and channel adapters."
    ),
    "arkivo-codec-lzma" to ArkivoPublicationMetadata(
        "Arkivo LZMA Codec",
        "Provides pure Java raw LZMA, LZMA-alone, and LZMA2 buffer engines and channel adapters."
    ),
    "arkivo-codec-lzip" to ArkivoPublicationMetadata(
        "Arkivo Lzip Codec",
        "Provides pure Java lzip member and stream buffer engines and channel adapters."
    ),
    "arkivo-codec-lz4" to ArkivoPublicationMetadata(
        "Arkivo LZ4 Codec",
        "Provides pure Java standard LZ4 frame and raw-block buffer engines and channel adapters."
    ),
    "arkivo-codec-ppmd" to ArkivoPublicationMetadata(
        "Arkivo PPMd Codec",
        "Provides pure Java raw PPMd7 buffer engines and channel adapters."
    ),
    "arkivo-codec-xz" to ArkivoPublicationMetadata(
        "Arkivo XZ Codec",
        "Provides pure Java XZ buffer engines, channel adapters, and shared BCJ and Delta filters."
    ),

    "arkivo-codec-zstd" to ArkivoPublicationMetadata(
        "Arkivo Zstandard Codec",
        "Provides Zstandard buffer engines and channel adapters."
    )
)

check(publicationMetadata.keys == subprojects.map { it.name }.toSet()) {
    "Publication metadata must cover every Arkivo module"
}

val repositoryUrl = "https://github.com/Glavo/Arkivo"
val publicationVersion = project.version.toString()
val stagingDirectory = layout.buildDirectory.dir("maven-staging")
val centralPortalDirectory = layout.buildDirectory.dir("central-portal")
val signingKey = providers.gradleProperty("signingKey")
val signingPassword = providers.gradleProperty("signingPassword")
val signingKeyId = providers.gradleProperty("signingKeyId")

subprojects {
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
        withJavadocJar()
    }

    tasks.withType<Jar>().configureEach {
        from(rootProject.file("LICENSE")) {
            into("META-INF")
            rename { "LICENSE" }
        }
    }

    val metadata = publicationMetadata.getValue(name)
    extensions.configure<PublishingExtension> {
        val publication = publications.create<MavenPublication>("mavenJava") {
            from(components.getByName("java"))
            pom {
                name.set(metadata.displayName)
                description.set(metadata.description)
                url.set(repositoryUrl)
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("Mozilla Public License 2.0")
                        url.set("https://www.mozilla.org/MPL/2.0/")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("Glavo")
                        name.set("Glavo")
                        email.set("zjx001202@gmail.com")
                        url.set("https://github.com/Glavo")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/Glavo/Arkivo.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Glavo/Arkivo.git")
                    url.set(repositoryUrl)
                    tag.set("HEAD")
                }
                issueManagement {
                    system.set("GitHub")
                    url.set("$repositoryUrl/issues")
                }
            }
        }

        repositories {
            maven {
                name = "staging"
                url = stagingDirectory.get().asFile.toURI()
            }
            maven {
                name = "centralPortal"
                url = centralPortalDirectory.get().asFile.toURI()
            }
        }

        if (signingKey.isPresent) {
            extensions.configure<SigningExtension> {
                val keyId = signingKeyId.orNull
                if (keyId == null) {
                    useInMemoryPgpKeys(signingKey.get(), signingPassword.orNull)
                } else {
                    useInMemoryPgpKeys(keyId, signingKey.get(), signingPassword.orNull)
                }
                sign(publication)
            }
        }
    }

    tasks.withType<PublishToMavenRepository>().configureEach {
        if (name.endsWith("ToCentralPortalRepository")) {
            doFirst {
                check(!publicationVersion.endsWith("-SNAPSHOT")) {
                    "Central Portal bundles require a release version"
                }
                check(signingKey.isPresent) {
                    "Central Portal bundles require the signingKey Gradle property"
                }
            }
        }
    }
}

val cleanMavenStaging by tasks.registering(Delete::class) {
    group = "publishing"
    description = "Deletes the local Maven publication staging repository."
    delete(stagingDirectory)
}

val cleanCentralPortalStaging by tasks.registering(Delete::class) {
    group = "publishing"
    description = "Deletes the Central Portal bundle staging repository."
    delete(centralPortalDirectory)
}

val stagingPublishTasks = subprojects.map { subproject ->
    subproject.tasks.named<PublishToMavenRepository>(
        "publishMavenJavaPublicationToStagingRepository"
    ) {
        dependsOn(cleanMavenStaging)
    }
}

val centralPortalPublishTasks = subprojects.map { subproject ->
    subproject.tasks.named<PublishToMavenRepository>(
        "publishMavenJavaPublicationToCentralPortalRepository"
    ) {
        dependsOn(cleanCentralPortalStaging)
    }
}

val stageMavenPublications by tasks.registering {
    group = "publishing"
    description = "Publishes every Arkivo module to a workspace-local Maven repository."
    dependsOn(stagingPublishTasks)
}

val verifyCentralPortalStaging by tasks.registering {
    group = "verification"
    description = "Verifies signatures and checksums in the Central Portal staging repository."
    dependsOn(centralPortalPublishTasks)
    inputs.dir(centralPortalDirectory)

    doLast {
        val version = publicationVersion
        publicationMetadata.keys.forEach { artifactId ->
            val artifactDirectory = centralPortalDirectory.get().asFile.resolve(
                "org/glavo/$artifactId/$version"
            )
            check(artifactDirectory.isDirectory) {
                "Missing Central Portal staging directory for $artifactId"
            }

            val baseName = "$artifactId-$version"
            val publicationFiles = listOf(
                artifactDirectory.resolve("$baseName.jar"),
                artifactDirectory.resolve("$baseName-sources.jar"),
                artifactDirectory.resolve("$baseName-javadoc.jar"),
                artifactDirectory.resolve("$baseName.pom"),
                artifactDirectory.resolve("$baseName.module")
            )
            publicationFiles.forEach { file ->
                check(file.isFile && file.length() > 0L) {
                    "Missing Central Portal artifact ${file.name}"
                }
                val signature = artifactDirectory.resolve("${file.name}.asc")
                check(signature.isFile && signature.length() > 0L) {
                    "Missing OpenPGP signature for ${file.name}"
                }
                listOf(file, signature).forEach { signedFile ->
                    listOf("md5", "sha1", "sha256", "sha512").forEach { algorithm ->
                        val checksum = artifactDirectory.resolve("${signedFile.name}.$algorithm")
                        check(checksum.isFile && checksum.length() > 0L) {
                            "Missing $algorithm checksum for ${signedFile.name}"
                        }
                    }
                }
            }
        }
    }
}

val centralPortalBundle by tasks.registering(Zip::class) {
    group = "publishing"
    description = "Creates a signed Maven repository bundle for Central Portal upload."
    dependsOn(verifyCentralPortalStaging)
    from(centralPortalDirectory) {
        exclude("**/maven-metadata.xml*")
    }
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("arkivo-$publicationVersion-central-portal.zip")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    doLast {
        ZipFile(archiveFile.get().asFile).use { bundle ->
            val entries = bundle.entries().asSequence().filter { !it.isDirectory }.toList()
            check(entries.isNotEmpty()) { "Central Portal bundle is empty" }
            check(entries.all { it.name.startsWith("org/glavo/") }) {
                "Central Portal bundle contains files outside the org.glavo namespace"
            }
            check(entries.none { "/maven-metadata.xml" in it.name }) {
                "Central Portal bundle contains repository-level Maven metadata"
            }
            check(entries.count { it.name.endsWith(".pom") } == publicationMetadata.size) {
                "Central Portal bundle does not contain one POM per Arkivo module"
            }
        }
    }
}

val expectedDependencies = mapOf(
    "arkivo-all" to setOf(
        "org.glavo:arkivo-archive:compile:$publicationVersion",
        "org.glavo:arkivo-archive-all:compile:$publicationVersion",
        "org.glavo:arkivo-archive-codec:runtime:$publicationVersion",
        "org.glavo:arkivo-checksum:compile:$publicationVersion",
        "org.glavo:arkivo-codec-all:compile:$publicationVersion"
    ),
    "arkivo-base" to emptySet(),
    "arkivo-checksum" to setOf(
        "org.glavo:arkivo-base:runtime:$publicationVersion"
    ),
    "arkivo-archive" to emptySet(),
    "arkivo-archive-codec" to setOf(
        "org.glavo:arkivo-archive:runtime:$publicationVersion",
        "org.glavo:arkivo-codec:runtime:$publicationVersion"
    ),
    "arkivo-archive-all" to setOf(
        "org.glavo:arkivo-archive:compile:$publicationVersion",
        "org.glavo:arkivo-archive-codec:runtime:$publicationVersion",
        "org.glavo:arkivo-archive-7z:compile:$publicationVersion",
        "org.glavo:arkivo-archive-ar:compile:$publicationVersion",
        "org.glavo:arkivo-archive-cpio:compile:$publicationVersion",
        "org.glavo:arkivo-archive-rar:compile:$publicationVersion",
        "org.glavo:arkivo-archive-tar:compile:$publicationVersion",
        "org.glavo:arkivo-archive-zip:compile:$publicationVersion"
    ),
    "arkivo-archive-ar" to setOf(
        "org.glavo:arkivo-archive:compile:$publicationVersion",
        "org.glavo:arkivo-archive-codec:runtime:$publicationVersion"
    ),
    "arkivo-archive-cpio" to setOf(
        "org.glavo:arkivo-archive:compile:$publicationVersion",
        "org.glavo:arkivo-archive-codec:runtime:$publicationVersion",
        "org.glavo:arkivo-base:runtime:$publicationVersion"
    ),
    "arkivo-archive-rar" to setOf(
        "org.glavo:arkivo-archive:compile:$publicationVersion",
        "org.glavo:arkivo-archive-codec:runtime:$publicationVersion",
        "org.glavo:arkivo-base:runtime:$publicationVersion",
        "org.glavo:arkivo-codec-ppmd:runtime:$publicationVersion"
    ),
    "arkivo-archive-tar" to setOf(
        "org.glavo:arkivo-archive:compile:$publicationVersion",
        "org.glavo:arkivo-archive-codec:runtime:$publicationVersion",
        "org.glavo:arkivo-codec:compile:$publicationVersion"
    ),
    "arkivo-archive-zip" to setOf(
        "org.glavo:arkivo-archive:compile:$publicationVersion",
        "org.glavo:arkivo-archive-codec:runtime:$publicationVersion",
        "org.glavo:arkivo-base:runtime:$publicationVersion",
        "org.glavo:arkivo-codec:runtime:$publicationVersion",
        "org.glavo:arkivo-codec-deflate:runtime:$publicationVersion",
        "org.glavo:arkivo-codec-lzma:runtime:$publicationVersion"
    ),
    "arkivo-archive-7z" to setOf(
        "org.glavo:arkivo-archive:compile:$publicationVersion",
        "org.glavo:arkivo-archive-codec:runtime:$publicationVersion",
        "org.glavo:arkivo-base:runtime:$publicationVersion",
        "org.glavo:arkivo-codec:runtime:$publicationVersion",
        "org.glavo:arkivo-codec-lzma:runtime:$publicationVersion",
        "org.glavo:arkivo-codec-ppmd:runtime:$publicationVersion",
        "org.glavo:arkivo-codec-xz:runtime:$publicationVersion"
    ),
    "arkivo-codec" to emptySet(),
    "arkivo-codec-all" to setOf(
        "org.glavo:arkivo-codec:compile:$publicationVersion",
        "org.glavo:arkivo-codec-bzip2:compile:$publicationVersion",
        "org.glavo:arkivo-codec-compress:compile:$publicationVersion",
        "org.glavo:arkivo-codec-deflate:compile:$publicationVersion",
        "org.glavo:arkivo-codec-lz4:compile:$publicationVersion",
        "org.glavo:arkivo-codec-lzip:compile:$publicationVersion",
        "org.glavo:arkivo-codec-lzma:compile:$publicationVersion",
        "org.glavo:arkivo-codec-ppmd:compile:$publicationVersion",
        "org.glavo:arkivo-codec-xz:compile:$publicationVersion",
        "org.glavo:arkivo-codec-zstd:compile:$publicationVersion"
    ),
    "arkivo-codec-bzip2" to setOf(
        "org.glavo:arkivo-checksum:runtime:$publicationVersion",
        "org.glavo:arkivo-codec:compile:$publicationVersion"
    ),
    "arkivo-codec-compress" to setOf("org.glavo:arkivo-codec:compile:$publicationVersion"),
    "arkivo-codec-deflate" to setOf("org.glavo:arkivo-codec:compile:$publicationVersion"),
    "arkivo-codec-lzma" to setOf("org.glavo:arkivo-codec:compile:$publicationVersion"),
    "arkivo-codec-lzip" to setOf(
        "org.glavo:arkivo-base:runtime:$publicationVersion",
        "org.glavo:arkivo-codec:compile:$publicationVersion",
        "org.glavo:arkivo-codec-lzma:runtime:$publicationVersion"
    ),
    "arkivo-codec-lz4" to setOf(
        "org.glavo:arkivo-base:runtime:$publicationVersion",
        "org.glavo:arkivo-checksum:runtime:$publicationVersion",
        "org.glavo:arkivo-codec:compile:$publicationVersion"
    ),
    "arkivo-codec-ppmd" to setOf("org.glavo:arkivo-codec:compile:$publicationVersion"),
    "arkivo-codec-xz" to setOf(
        "org.glavo:arkivo-base:runtime:$publicationVersion",
        "org.glavo:arkivo-checksum:runtime:$publicationVersion",
        "org.glavo:arkivo-codec:compile:$publicationVersion",
        "org.glavo:arkivo-codec-lzma:compile:$publicationVersion"
    ),
    "arkivo-codec-zstd" to setOf(
        "org.glavo:arkivo-base:runtime:$publicationVersion",
        "org.glavo:arkivo-checksum:runtime:$publicationVersion",
        "org.glavo:arkivo-codec:compile:$publicationVersion"
    )
)

check(expectedDependencies.keys == publicationMetadata.keys) {
    "Expected POM dependencies must cover every Arkivo module"
}

fun Element.childText(localName: String): String {
    val nodes = getElementsByTagNameNS("*", localName)
    check(nodes.length > 0) { "Missing POM element $localName" }
    return nodes.item(0).textContent.trim()
}

val verifyMavenPublications by tasks.registering {
    group = "verification"
    description = "Verifies every staged Maven publication and its dependency metadata."
    dependsOn(stageMavenPublications)

    inputs.dir(stagingDirectory)
    doLast {
        val documentFactory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val version = publicationVersion
        fun readModuleDescriptor(file: File): ModuleDescriptor =
            JarFile(file).use { archive ->
                val entry = checkNotNull(archive.getJarEntry("module-info.class")) {
                    "${file.name} does not contain module-info.class"
                }
                archive.getInputStream(entry).use { input -> ModuleDescriptor.read(input) }
            }

        val moduleNamesByArtifact = publicationMetadata.keys.associateWith { artifactId ->
            val artifactDirectory = stagingDirectory.get().asFile.resolve(
                "org/glavo/$artifactId/$version"
            )
            val mainJars = artifactDirectory.listFiles()?.filter { file ->
                file.isFile &&
                        file.name.endsWith(".jar") &&
                        !file.name.endsWith("-sources.jar") &&
                        !file.name.endsWith("-javadoc.jar")
            }.orEmpty()
            check(mainJars.size == 1) {
                "$artifactId has ${mainJars.size} main JAR artifacts: ${mainJars.map { it.name }}"
            }
            readModuleDescriptor(mainJars.single()).name()
        }
        val artifactsByModuleName = moduleNamesByArtifact.entries.associate { (artifactId, moduleName) ->
            moduleName to artifactId
        }
        check(artifactsByModuleName.size == moduleNamesByArtifact.size) {
            "Published Arkivo modules must have unique JPMS names"
        }

        publicationMetadata.forEach { (artifactId, metadata) ->
            val artifactDirectory = stagingDirectory.get().asFile.resolve(
                "org/glavo/$artifactId/$version"
            )
            check(artifactDirectory.isDirectory) {
                "Missing staged publication directory for $artifactId"
            }

            val publishedFiles = artifactDirectory.listFiles()?.filter { it.isFile }.orEmpty()
            fun requireArtifact(description: String, predicate: (String) -> Boolean): File {
                val matches = publishedFiles.filter { predicate(it.name) }
                check(matches.size == 1) {
                    "$artifactId has ${matches.size} $description artifacts: " +
                            matches.map { it.name }
                }
                return matches.single()
            }

            val sourcesJar = requireArtifact("sources JAR") { it.endsWith("-sources.jar") }
            val javadocJar = requireArtifact("Javadoc JAR") { it.endsWith("-javadoc.jar") }
            val mainJar = requireArtifact("main JAR") {
                it.endsWith(".jar") &&
                        !it.endsWith("-sources.jar") &&
                        !it.endsWith("-javadoc.jar")
            }
            val pomFile = requireArtifact("POM") { it.endsWith(".pom") }
            val moduleFile = requireArtifact("Gradle module metadata") { it.endsWith(".module") }
            listOf(mainJar, sourcesJar, javadocJar, pomFile, moduleFile).forEach { file ->
                check(file.length() > 0L) {
                    "Empty staged artifact ${file.name}"
                }
                listOf("md5", "sha1", "sha256", "sha512").forEach { algorithm ->
                    val checksum = artifactDirectory.resolve("${file.name}.$algorithm")
                    check(checksum.isFile && checksum.length() > 0L) {
                        "Missing $algorithm checksum for ${file.name}"
                    }
                }
            }

            listOf(mainJar, sourcesJar, javadocJar).forEach { file ->
                JarFile(file).use { archive ->
                    check(archive.getEntry("META-INF/LICENSE") != null) {
                        "${file.name} does not contain META-INF/LICENSE"
                    }
                    check(archive.entries().asSequence().any {
                        !it.isDirectory && it.name != "META-INF/LICENSE"
                    }) {
                        "${file.name} contains no publication content"
                    }
                }
            }
            val moduleDescriptor = readModuleDescriptor(mainJar)
            JarFile(sourcesJar).use { archive ->
                check(archive.getEntry("module-info.java") != null) {
                    "${sourcesJar.name} does not contain module-info.java"
                }
            }
            JarFile(javadocJar).use { archive ->
                check(archive.getEntry("index.html") != null) {
                    "${javadocJar.name} does not contain index.html"
                }
            }

            val document = documentFactory.newDocumentBuilder().parse(pomFile)
            val root = document.documentElement
            check(root.childText("groupId") == "org.glavo") { "Incorrect groupId for $artifactId" }
            check(root.childText("artifactId") == artifactId) { "Incorrect artifactId for $artifactId" }
            check(root.childText("version") == version) { "Incorrect version for $artifactId" }
            check(root.childText("name") == metadata.displayName) { "Incorrect name for $artifactId" }
            check(root.childText("description") == metadata.description) {
                "Incorrect description for $artifactId"
            }
            check(root.childText("url") == repositoryUrl) { "Incorrect project URL for $artifactId" }
            check(root.childText("inceptionYear") == "2026") {
                "Incorrect inception year for $artifactId"
            }

            val licenses = root.getElementsByTagNameNS("*", "license")
            check(licenses.length == 1) { "Expected one license for $artifactId" }
            val license = licenses.item(0) as Element
            check(license.childText("name") == "Mozilla Public License 2.0") {
                "Incorrect license name for $artifactId"
            }
            check(license.childText("url") == "https://www.mozilla.org/MPL/2.0/") {
                "Incorrect license URL for $artifactId"
            }
            check(license.childText("distribution") == "repo") {
                "Incorrect license distribution for $artifactId"
            }

            val developers = root.getElementsByTagNameNS("*", "developer")
            check(developers.length == 1) { "Expected one developer for $artifactId" }
            val developer = developers.item(0) as Element
            check(developer.childText("id") == "Glavo") { "Incorrect developer ID for $artifactId" }
            check(developer.childText("name") == "Glavo") { "Incorrect developer name for $artifactId" }
            check(developer.childText("email") == "zjx001202@gmail.com") {
                "Incorrect developer email for $artifactId"
            }
            check(developer.childText("url") == "https://github.com/Glavo") {
                "Incorrect developer URL for $artifactId"
            }

            val scm = root.getElementsByTagNameNS("*", "scm").item(0) as Element
            check(scm.childText("connection") == "scm:git:https://github.com/Glavo/Arkivo.git") {
                "Incorrect SCM connection for $artifactId"
            }
            check(scm.childText("developerConnection") ==
                    "scm:git:ssh://git@github.com/Glavo/Arkivo.git") {
                "Incorrect SCM developer connection for $artifactId"
            }
            check(scm.childText("url") == repositoryUrl) { "Incorrect SCM URL for $artifactId" }

            val issueManagement =
                    root.getElementsByTagNameNS("*", "issueManagement").item(0) as Element
            check(issueManagement.childText("system") == "GitHub") {
                "Incorrect issue system for $artifactId"
            }
            check(issueManagement.childText("url") == "$repositoryUrl/issues") {
                "Incorrect issue URL for $artifactId"
            }

            val dependencyNodes = root.getElementsByTagNameNS("*", "dependency")
            val actualDependencies = buildSet {
                for (index in 0 until dependencyNodes.length) {
                    val dependency = dependencyNodes.item(index) as Element
                    add(
                        listOf(
                            dependency.childText("groupId"),
                            dependency.childText("artifactId"),
                            dependency.childText("scope"),
                            dependency.childText("version")
                        ).joinToString(":")
                    )
                }
            }
            check(actualDependencies == expectedDependencies.getValue(artifactId)) {
                "$artifactId publishes dependencies $actualDependencies instead of " +
                        expectedDependencies.getValue(artifactId)
            }
            check(actualDependencies.none { "annotations" in it || "junit" in it }) {
                "$artifactId leaks build-only dependencies into its POM"
            }

            val actualArkivoDependencies = actualDependencies.mapNotNullTo(mutableSetOf()) { coordinate ->
                val parts = coordinate.split(':')
                if (parts[0] == "org.glavo" && parts[1] in moduleNamesByArtifact) {
                    "${parts[1]}:${parts[2]}"
                } else {
                    null
                }
            }
            val expectedArkivoDependencies = moduleDescriptor.requires().mapNotNullTo(mutableSetOf()) { requirement ->
                val dependencyArtifact = artifactsByModuleName[requirement.name()]
                    ?: return@mapNotNullTo null
                val scope = if (ModuleDescriptor.Requires.Modifier.TRANSITIVE in requirement.modifiers()) {
                    "compile"
                } else {
                    "runtime"
                }
                "$dependencyArtifact:$scope"
            }
            check(actualArkivoDependencies == expectedArkivoDependencies) {
                "$artifactId publishes Arkivo dependency scopes $actualArkivoDependencies, but " +
                        "module ${moduleDescriptor.name()} requires $expectedArkivoDependencies"
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyMavenPublications)
}