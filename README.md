# Arkivo

Arkivo is a modular Java library for archive file systems, streaming archive access, and channel-first compression codecs. It targets Java 17 and exposes explicit JPMS modules.

The archive layer supports AR, RAR, TAR, ZIP, and 7z. RAR support is read-only. ZIP and 7z include complete-rewrite mutation, encryption, and split-volume support. 7z output includes solid folders, linear BCJ/Delta chains, and four-stream BCJ2 graphs. The codec layer can be used independently of archive support.

## Dependencies

Use the aggregate artifact when every format and codec is acceptable:

~~~kotlin
dependencies {
    implementation("org.glavo:arkivo-all:<version>")
}
~~~

Use only the modules required by an application when dependency size or optional codec behavior matters:

~~~kotlin
dependencies {
    implementation("org.glavo:arkivo-archive")
    implementation("org.glavo:arkivo-archive-zip")
    implementation("org.glavo:arkivo-codec")
    implementation("org.glavo:arkivo-codec-gzip")
}
~~~

The main module groups are:

| Artifact | Purpose |
| --- | --- |
| arkivo-all | All archive formats and codecs |
| arkivo-archive | Archive discovery, NIO file system, streaming, password, and volume APIs |
| arkivo-archive-all | All archive format providers |
| arkivo-archive-ar | AR read, write, and streaming support |
| arkivo-archive-rar | RAR read-only file system and streaming support |
| arkivo-archive-tar | TAR read, write, streaming, and optional outer compression |
| arkivo-archive-zip | ZIP read, write, update, encryption, and split-volume support |
| arkivo-archive-7z | 7z read, write, update, encryption, solid, and split-volume support |
| arkivo-codec | Independent channel-first codec API |
| arkivo-codec-all | All codec and byte-transform providers |
| arkivo-codec-* | Individual gzip, zlib, Deflate, Deflate64, BZip2, LZMA, XZ, Zstandard, BCJ, or Delta providers |

Providers are discovered through ServiceLoader. Applications using JPMS should require the API modules they compile against and include the selected provider modules at runtime.

## Archive File Systems

ArkivoFormats detects installed archive providers and opens an ArkivoFileSystem from a path or a seekable channel:

~~~java
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormats;

import java.nio.file.Files;
import java.nio.file.Path;

Path archive = Path.of("example.zip");
try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(archive)) {
    try (var entries = Files.walk(fileSystem.getPath("/"))) {
        entries.forEach(System.out::println);
    }
}
~~~

Forward-only data can be processed without first copying it to a Path:

~~~java
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoStreamingReader;

import java.nio.channels.ReadableByteChannel;
import java.nio.file.attribute.BasicFileAttributes;

try (ReadableByteChannel source = openSource();
     ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(source)) {
    while (reader.next()) {
        BasicFileAttributes attributes = reader.readAttributes(BasicFileAttributes.class);
        if (attributes.isRegularFile()) {
            try (ReadableByteChannel content = reader.openChannel()) {
                process(content);
            }
        }
    }
}
~~~

## Compression Codecs

The codec API treats channels as the primary transport. Stream adapters remain available for interoperability:

~~~java
import org.glavo.arkivo.codec.CompressionCodecs;

import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

Path sourcePath = Path.of("input.txt");
Path targetPath = Path.of("input.txt.gz");
try (ReadableByteChannel source = Files.newByteChannel(sourcePath, StandardOpenOption.READ);
     WritableByteChannel target = Files.newByteChannel(
             targetPath,
             StandardOpenOption.CREATE,
             StandardOpenOption.TRUNCATE_EXISTING,
             StandardOpenOption.WRITE
     )) {
    CompressionCodecs.compress("gzip", source, target);
}
~~~

CodecOptions controls compression level, strategy, dictionaries, pledged source size, checksums, workers, maximum decoded output, window size, and codec-specific options.

## Building

Use the workspace-local Gradle user home:

~~~shell
./gradlew -g .gradle-user-home check javadoc
~~~

The `check` lifecycle verifies the reviewed exported type set, exact public and protected JVM signatures, generic
declarations, inheritance, sealed hierarchies, and inlinable constant values. After reviewing an intentional public API
change, regenerate the text signature baseline with:

~~~shell
./gradlew -g .gradle-user-home :arkivo-all:updatePublicApiSignatures
~~~

JMH benchmarks cover channel-first gzip and Zstandard throughput, ZIP and 7z indexing, and concurrent random entry
reads. The normal `check` lifecycle compiles the benchmark harness but does not run full measurements:

~~~shell
./gradlew -g .gradle-user-home :arkivo-all:benchmark
~~~

Use the `benchmarkArgs` Gradle property to forward a whitespace-separated JMH argument list and select a subset, for
example `-PbenchmarkArgs=CodecThroughputBenchmark`.

Set `ARKIVO_7Z_EXECUTABLE` to an official 7-Zip CLI path to enable the optional generated-BCJ2 interoperability test.
No external executable or binary archive fixture is stored in the repository.

Real-world corpus tests download pinned upstream source releases into the project-local
`.arkivo-cache/test-data/downloads/sha256` content-addressed cache, verify declared sizes and SHA-256 values, and extract
only reviewed test directories into `build/test-data`. The normal `test`, `check`, and `clean` lifecycles neither
download nor delete the persistent cache. Run the opt-in suite and inspect or explicitly remove its cache with:

~~~shell
./gradlew -g .gradle-user-home realWorldTest
./gradlew -g .gradle-user-home testDataCacheReport
./gradlew -g .gradle-user-home cleanTestDataCache
~~~

`-Parkivo.testDataCacheDirectory=<path>` overrides the project-local cache directory. `--offline` reuses an existing
verified download and fails before extraction when the required object is absent or invalid. The Zstandard suite pins
the official 1.5.7 release and extracts its upstream license with the golden compression, decompression, malformed
frame, and dictionary vectors; no downloaded test data is committed.

The publication verifier builds every binary, sources, and Javadoc JAR, publishes all modules to build/maven-staging, and validates their POM metadata and dependency scopes:

~~~shell
./gradlew -g .gradle-user-home verifyMavenPublications
~~~

centralPortalBundle creates a signed Maven repository bundle under build/distributions. It requires a non-SNAPSHOT releaseVersion and the Gradle properties signingKey and signingPassword; signingKeyId is optional. These properties can be supplied with the corresponding ORG_GRADLE_PROJECT_* environment variables.

## License

Arkivo is licensed under the [Mozilla Public License 2.0](LICENSE).
