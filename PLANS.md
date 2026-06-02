# Arkivo Development Plan

## Goals

Arkivo should provide a modern Java compression and archive library with a unified API across compression formats and archive formats.

The primary API surface should be based on Java NIO:

- Use `Path` instead of `File`.
- Use `ByteChannel`, `ReadableByteChannel`, `WritableByteChannel`, and `SeekableByteChannel` as the core I/O abstractions.
- Provide `InputStream` and `OutputStream` convenience methods only as adapters.
- Provide convenient APIs based on `FileSystem` where a format can support them naturally.
- Keep format-specific implementations modular so users can depend only on the formats they need.

## Module Structure

The project should use a multi-module layout.

```text
arkivo-base
arkivo-codecs
arkivo-codecs-gzip
arkivo-codecs-zlib
arkivo-codecs-xz
arkivo-codecs-zstd
arkivo-archives
arkivo-archives-zip
arkivo-archives-tar
arkivo-archives-7z
arkivo-archives-rar
arkivo-all
```

`arkivo-base` should contain the shared abstractions, common exceptions, metadata types, registry support, and low-level NIO utilities.

`arkivo-codecs` should be an aggregate module for compression codec APIs and common codec utilities.

`arkivo-codecs-*` modules should implement individual compression formats.

`arkivo-archives` should be an aggregate module for archive APIs and common archive utilities.

`arkivo-archives-*` modules should implement individual archive formats, including their own `FileSystem` implementations when applicable.

`arkivo-all` should be an aggregate dependency module that brings in all supported formats.

The core `FileSystem` abstractions and reusable support classes belong in `arkivo-base`, while each concrete archive `FileSystem` implementation belongs in the corresponding format module.

## Naming Model

The public archive API should use `Arkivo` as the project-specific replacement for the generic `Archive` noun.

This keeps the API distinct from Apache Commons Compress, the JDK ZIP API, and other archive libraries while remaining short and readable.

The preferred naming pattern is:

```text
FormatArkivoEditor mutable archive handle for editing an existing archive
FormatArkivoReader read-only random-access reader
FormatArkivoWriter writer for creating a new archive
FormatArkivoStreamingReader sequential-only reader when a format needs a distinct streaming API
FormatArkivoStreamingWriter sequential-only writer when a format needs a distinct streaming API
FormatArkivoFileSystem
```

For ZIP, the primary types should be:

```java
ZipArkivoEditor
ZipArkivoReader
ZipArkivoWriter
ZipArkivoStreamingReader
ZipArkivoStreamingWriter
ZipArkivoFileSystem
```

`ZipArkivoEditor` should be used to modify an existing archive.

`ZipArkivoReader` should be read-only.

`ZipArkivoWriter` should create a new archive.

Sequential streaming APIs should still use `FormatArkivoReader` and `FormatArkivoWriter`. The API contract should document whether a reader or writer requires `SeekableByteChannel` or can operate on sequential channels.

`FormatArkivoStreamingReader` and `FormatArkivoStreamingWriter` should be introduced only when a format needs separate sequential-only APIs in addition to its normal reader and writer. For ZIP, these names are reserved for stream-oriented processing that cannot rely on the central directory index.

Primary factory methods should use `open(...)`. The target class name defines the operation role:

```java
ZipArkivoEditor.open(path)
ZipArkivoReader.open(path)
ZipArkivoWriter.open(path)
ZipArkivoStreamingReader.open(channel)
ZipArkivoStreamingWriter.open(channel)
```

`ZipArkivoWriter.open(path)` should use create-new semantics by default. Replacement or truncation should require explicit open options, such as `StandardOpenOption.CREATE` and `StandardOpenOption.TRUNCATE_EXISTING`, or Arkivo-specific open options.

## Core Abstractions

`arkivo-base` should define the shared contracts used by all format modules.

Potential core types include:

```java
ArkivoEditor
ArkivoFormat
ArkivoReader
ArkivoWriter
ArkivoFileSystem
ArkivoFileSystemProviderSupport
CompressionFormat
CompressionEncoder
CompressionDecoder
```

The base module should define the contracts and support code as a format-independent foundation.

Format detection and implementation lookup should use a service registration model, such as `ServiceLoader`, with concrete providers supplied by format modules.

## Compression API

Compression formats operate on a single byte stream.

The primary API should accept and return NIO channels:

```java
ReadableByteChannel
WritableByteChannel
ByteChannel
```

Convenience methods may support `InputStream` and `OutputStream`, but these should be adapters over the channel-based implementation.

Initial compression formats:

- gzip
- zlib
- raw deflate
- xz
- zstd

## Archive API

Archive formats operate on multiple entries and should expose both streaming and random-access APIs where the format supports them.

Streaming archive APIs should be used for formats that can naturally read or write entries sequentially, such as tar.

Random-access archive APIs should be used for formats that support central directories, indexes, or seekable structures, such as zip and 7z.

Sequential and random-access behavior should be described by each reader or writer contract.

Potential API families:

```java
ArkivoEditor
ArkivoReader
ArkivoWriter
```

`ArkivoReader` and `ArkivoWriter` should prefer `SeekableByteChannel` for random-access formats.

`ArkivoEditor` should represent a mutable handle for editing an existing archive. Early implementations may use copy-on-write semantics instead of promising true in-place modification.

## FileSystem Integration

`FileSystem` support should be implemented inside each archive format module.

For example:

```text
arkivo-archives-zip
  ZipArkivoEditor
  ZipArkivoReader
  ZipArkivoWriter
  ZipArkivoStreamingReader
  ZipArkivoStreamingWriter
  ZipArkivoFileSystem
  ZipArkivoFileSystemProvider

arkivo-archives-tar
  TarArkivoReader
  TarArkivoWriter
  TarArkivoFileSystem
```

The public API may expose convenience factories such as:

```java
ZipArkivoFileSystem.open(path)
SevenZipArkivoFileSystem.open(path)
```

Formats may also register JDK `FileSystemProvider` implementations when the integration is useful:

```java
FileSystems.newFileSystem(archivePath)
```

The provider registration should live in the concrete format module, not in a shared filesystem module.

## Performance Requirements

The implementation should prioritize high performance and low memory overhead.

Parsing should prefer reusable `ByteBuffer` instances over temporary byte arrays and short-lived wrapper objects.

Important rules:

- Parse headers, footers, central directories, and metadata blocks with `ByteBuffer`.
- Use `ByteBuffer.order(...)` for endian-sensitive structures.
- Avoid loading whole files or whole entry contents into memory.
- Decode entry names lazily when possible.
- Parse comments, extra fields, and extended attributes lazily when possible.
- Build lightweight indexes for random-access formats instead of full object graphs.
- Reuse buffers across sequential parsing and writing operations.
- Compute CRC, hashes, and size statistics incrementally while data is streamed.
- Avoid per-entry temporary allocations in hot paths where a reusable structure is practical.

Public APIs should remain clear and immutable, but internal data structures may use compact records and lazily parsed views.

Possible internal implementation types:

```java
ZipArkivoEntryRecord
ZipCentralDirectoryRecord
TarHeaderBlock
SevenZipFolderRecord
```

## Entry Metadata

The shared entry model should support common archive metadata:

- Entry path
- Regular file, directory, symbolic link, and special file types
- Uncompressed size
- Compressed size
- Last modified time
- Creation time
- Last access time
- Permissions
- Owner and group information
- Link target
- Format-specific attributes

Time values should use `FileTime`.

Optional or absent metadata should use `@Nullable`, not `Optional`.

Immutable collections, arrays, and buffer views should use JetBrains immutability annotations where applicable.

## Implementation Order

1. Convert the repository to a Gradle multi-module project.
2. Implement `arkivo-base` with NIO-first abstractions, common metadata types, exceptions, service registration, and low-level buffer utilities.
3. Implement tar streaming reader and writer to validate the archive entry model and low-allocation header parsing.
4. Implement gzip, zlib, and raw deflate codec modules to validate the channel-first compression API.
5. Implement `ZipArkivoReader` with central directory indexing.
6. Implement `ZipArkivoWriter`.
7. Implement mutable `ZipArkivoEditor` editing support with explicit commit semantics.
8. Implement `ZipArkivoFileSystem` inside `arkivo-archives-zip`.
9. Add xz and zstd codec modules.
10. Add 7z read support and a read-only 7z filesystem.
11. Add rar read support and a read-only rar filesystem.

## Compatibility Notes

RAR support should initially be read-only.

Some formats may not support efficient random write operations. These formats should expose streaming or copy-on-write APIs instead of pretending to support in-place mutation.

Format-specific capabilities should be represented explicitly so callers can discover whether a format supports streaming read, streaming write, random read, random write, editing, or filesystem views.
