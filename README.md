# Arkivo

Arkivo is a modular, pure Java archive and compression toolkit for Java 17 and later. It combines archive-backed NIO
file systems, forward-only archive readers and writers, caller-owned `ByteBuffer` codec engines, channel and stream
adapters, and `ServiceLoader`-based format discovery.

The project is currently developed as version `1.0-SNAPSHOT`. Published artifacts use the group `org.glavo`.

## Highlights

- Access archive entries with the standard `java.nio.file.Files` API.
- Detect installed archive and compression formats without coupling callers to implementations.
- Drive compression incrementally through `CompressionEncoder` and `CompressionDecoder`; implementations do not retain
  caller buffers after an operation returns.
- Use blocking `ReadableByteChannel`, `WritableByteChannel`, `InputStream`, and `OutputStream` adapters over the same
  codec engines.
- Bound decompression and archive metadata, entry, and aggregate sizes through explicit limits.
- Run without JNI or native libraries.

## Dependencies

Use the aggregate module when an application needs every archive format and compression codec:

```kotlin
dependencies {
    implementation("org.glavo:arkivo-all:VERSION")
}
```

For a smaller runtime, select an aggregate layer or individual implementation modules:

```kotlin
dependencies {
    implementation("org.glavo:arkivo-archive-zip:VERSION")
    implementation("org.glavo:arkivo-codec-zstd:VERSION")
}
```

The main module choices are:

| Artifact | Purpose |
| --- | --- |
| `arkivo-all` | Every archive format, codec, and compressed-stream integration |
| `arkivo-archive-all` | Every archive format implementation |
| `arkivo-codec-all` | Every compression codec and byte transform implementation |
| `arkivo-archive` | Archive contracts, discovery, storage, and NIO support |
| `arkivo-codec` | Codec contracts, compression format discovery, and adapters |
| `arkivo-archive-<format>` | One archive implementation, such as `zip`, `tar`, or `7z` |
| `arkivo-codec-<family>` | One codec family, such as `zstd`, `xz`, or `deflate` |

JPMS applications using the aggregate artifact can require the matching aggregate module:

```java
module example.application {
    requires org.glavo.arkivo.all;
}
```

## Compress a buffer

The allocating convenience methods are suitable when the complete result fits in one Java buffer. Decompression always
requires a caller-selected output limit.

```java
byte[] input = "Arkivo buffer example".getBytes(StandardCharsets.UTF_8);

CompressionCodec codec = Objects.requireNonNull(CompressionFormats.find("zstd")).defaultCodec();
ByteBuffer compressed = codec.compress(ByteBuffer.wrap(input));
ByteBuffer decoded = codec.decompress(compressed, input.length);

byte[] output = new byte[decoded.remaining()];
decoded.get(output);
```

For streaming or bounded-memory work, create a `CompressionEncoder` or `CompressionDecoder` and respond to the returned
`CodecOutcome`. Buffer positions are the authoritative consumed and produced byte counts. `NEEDS_INPUT` and
`NEEDS_OUTPUT` request another source or target range, while `finish()` completes an encoding without closing or
releasing its reusable state. Formats with meaningful non-terminal units may additionally expose their specialized
framing interfaces.

## Create and read a ZIP file system

Writable archive file systems use ordinary NIO operations. Closing the file system finishes and commits the archive.

```java
Path archive = Path.of("example.zip");
Map<String, ?> writable = Map.of(
        ArkivoFileSystem.OPEN_OPTIONS.key(),
        Set.of(
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        )
);

try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive, writable)) {
    Files.createDirectories(fileSystem.getPath("/docs"));
    Files.writeString(fileSystem.getPath("/docs/readme.txt"), "Hello from Arkivo");
}

try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(archive)) {
    String text = Files.readString(fileSystem.getPath("/docs/readme.txt"));
}
```

`ArkivoFormats` also accepts channels, repeatable channel sources, and logical multi-volume sources. Forward-only
workloads can use `openStreamingReader` and `openStreamingWriter` instead of building a random-access file system.

## Supported formats

Archive modules currently cover:

- AR file systems and streaming readers/writers.
- TAR file systems and streaming readers/writers, including detected or selected outer compression.
- ZIP file systems and streaming readers/writers, with mutation, encryption, and split-volume support.
- 7z file systems and streaming writers, with mutation, solid archives, encryption, and split-volume support.
- Read-only RAR4 and RAR5 file systems and streaming readers.

Compression support currently covers BZip2, raw Deflate, Deflate64, gzip, raw LZMA, LZMA-alone, LZMA2, PPMd7, XZ,
zlib, and Zstandard. The codec layer also provides Delta and BCJ executable transforms. All compression implementations
are pure Java.

Not every format has the same lifecycle or framing model. Codec objects are immutable configurations: generic optional
configuration is exposed through codec subinterfaces, while incremental flush and frame behavior is exposed through
encoder and decoder marker interfaces. Archive formats likewise advertise behavior through implemented format
interfaces instead of assuming universal streaming, framing, mutation, or multi-volume support.

## Build and verification

Run the normal compilation, unit, API compatibility, interoperability, scalability, and publication checks with:

```text
./gradlew check
```

The separately cached upstream corpus suite is opt-in:

```text
./gradlew realWorldTest
```

The build targets Java 17. The checked-in API baselines make unreviewed public or protected signature changes fail the
normal `check` lifecycle.

## License

Arkivo is available under the [Mozilla Public License 2.0](LICENSE).
