# Arkivo

Arkivo is a modular, pure Java archive and compression toolkit for Java 17 and later. It combines archive-backed NIO
file systems, forward-only archive readers and writers, caller-owned `ByteBuffer` codec engines, channel and stream
adapters, and `ServiceLoader`-based format discovery. The [architecture guide](ARCHITECTURE.md) describes the common
programming model, lifecycle rules, and module boundaries.

The current build version is `1.0-SNAPSHOT`. Published artifacts use the group `org.glavo`.

## Highlights

- Access archive entries with the standard `java.nio.file.Files` API.
- Detect installed archive and compression formats without coupling callers to implementations.
- Drive compression incrementally through `CompressionEncoder` and `CompressionDecoder`; implementations do not retain
  caller buffers after an operation returns.
- Use blocking `ReadableByteChannel`, `WritableByteChannel`, `InputStream`, and `OutputStream` adapters over the same
  codec engines.
- Bound decompression and archive metadata, entry, and aggregate sizes through explicit limits.
- Run without JNI or native libraries.

## API conventions

Arkivo follows the conventions of Java NIO:

- Buffer positions record consumed and produced bytes; codec engines do not retain caller buffers after an operation.
- Channels and streams are stateful resources and must be closed. Immutable formats, codecs, options, limits, and
  attribute snapshots can be shared.
- Codec adapters borrow a caller-supplied endpoint unless an overload accepts `ResourceOwnership.OWNED`.
- Archive factory ownership is documented per overload because the owned resource may be a channel, repeatable source,
  volume source, target transaction, reader, writer, or file system.
- Archive entries use `Path`, `Files`, and NIO attribute views where a format supports a file-system model.

Capability subinterfaces describe operations that are not universal. A format is not assumed to support random access,
streaming writes, flushing, independent frames, dictionaries, encryption, or multiple volumes unless its type exposes
that contract.

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

CompressionFormat format = CompressionFormats.require("zstd");
CompressionCodec<?> codec = format.defaultCodec().withMaximumOutputSize(input.length);
ByteBuffer compressed = codec.compress(ByteBuffer.wrap(input));
ByteBuffer decoded = codec.decompress(compressed);

byte[] output = new byte[decoded.remaining()];
decoded.get(output);
```

For streaming or bounded-memory work, create a `CompressionEncoder` or `CompressionDecoder` and respond to the returned
`CodecOutcome`. Buffer positions are the authoritative consumed and produced byte counts. `NEEDS_INPUT` and
`NEEDS_OUTPUT` request another source or target range. After all input has been supplied, repeat `finish()` until it
returns `FINISHED`; closing an unfinished engine releases resources without completing the stream. Formats with
meaningful non-terminal units additionally expose their specialized framing interfaces.

## Create and read a ZIP file system

Writable archive file systems use ordinary NIO operations. Closing the file system finishes and commits the archive.

```java
Path archive = Path.of("example.zip");
try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.create(archive)) {
    Files.createDirectories(fileSystem.getPath("/docs"));
    Files.writeString(fileSystem.getPath("/docs/readme.txt"), "Hello from Arkivo");
}

try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(archive)) {
    String text = Files.readString(fileSystem.getPath("/docs/readme.txt"));
}
```

`ArkivoFormats` also accepts channels, repeatable channel sources, and logical multi-volume sources. Forward-only
workloads can use `openStreamingReader` and `openStreamingWriter` instead of building a random-access file system.

```java
try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(archive)) {
    while (reader.next()) {
        ArchiveEntryAttributes attributes = reader.readAttributes();
        if (attributes.isRegularFile()) {
            try (InputStream input = reader.openInputStream()) {
                // Consume the current entry before advancing the cursor.
            }
        }
    }
}
```

Metadata is read from the current cursor position and may be parsed lazily. Attribute snapshots remain valid after the
reader advances. An entry body can be opened once; `next()` closes any body that the caller left open.

## Supported formats

Official archive modules provide these access models:

| Format | File system | Streaming read | Streaming write | Notable support |
| --- | --- | --- | --- | --- |
| AR | Read/write | Yes | Yes | GNU and BSD metadata variants |
| CPIO | None | Yes | Yes | `newc`, CRC, portable ASCII, and old binary dialects |
| TAR | Read/write | Yes | Yes | Detected or selected outer compression |
| ZIP | Read/write | Yes | Yes | Encryption, mutation, and split volumes |
| 7z | Read/write | No | Yes | Solid archives, encryption, mutation, and split volumes |
| RAR4/RAR5 | Read-only | Yes | No | Encryption and multi-volume reading |

Compression modules provide BZip2, Unix compress (`.Z`), raw Deflate, Deflate64, gzip, LZ4 frame and raw block,
lzip, raw LZMA, LZMA-alone, LZMA2, PPMd7, XZ, zlib, and Zstandard. The codec layer also provides Delta and BCJ
executable transforms.
All compression implementations are pure Java.

Not every format has the same lifecycle or framing model. Codec objects are immutable configurations: generic optional
configuration is exposed through codec subinterfaces, while incremental flush and frame behavior is exposed through
encoder and decoder marker interfaces. Archive formats likewise advertise behavior through implemented format
interfaces instead of assuming universal streaming, framing, mutation, or multi-volume support.

## Build and verification

Run the normal compilation, fast comprehensive Tier 1 tests, API compatibility, and publication checks with:

```text
./gradlew check
```

The slower upstream-corpus and interoperability tests are available as Tier 2. `tier2Test` runs only that tier, while
`checkTier2` also includes the normal checks:

```text
./gradlew tier2Test
./gradlew checkTier2
```

Stress, scalability, and low-heap tests are isolated in Tier 3. `tier3Test` runs only that tier, while `checkTier3`
includes all lower tiers and normal checks:

```text
./gradlew tier3Test
./gradlew checkTier3
```

Upstream test data is downloaded from official source archives into the project-local test-data cache when an applicable
optional tier is requested; binary corpus files are not stored in this repository.

The build targets Java 17. The checked-in API baselines make unreviewed public or protected signature changes fail the
normal `check` lifecycle.

## License

Arkivo is available under the [Mozilla Public License 2.0](LICENSE).
