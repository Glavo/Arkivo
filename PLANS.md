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

`arkivo-base` should contain shared format discovery, typed feature contracts, reusable `FileSystem` support, password and volume-source contracts, compression codec contracts, and low-level NIO utilities.

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
FormatArkivoFileSystem
FormatArkivoFileSystemOptions
FormatArkivoEntryAttributes
FormatArkivoEntryAttributeView
```

For ZIP, the primary types should be:

```java
ZipArkivoFormat
ZipArkivoFileSystem
ZipArkivoFileSystemOptions
ZipArkivoEntryAttributes
ZipArkivoEntryAttributeView
```

`ZipArkivoFileSystem` should be the primary public API for ZIP archive access.

ZIP entry metadata should be exposed through NIO file attribute APIs, including `ZipArkivoEntryAttributes` and `ZipArkivoEntryAttributeView`.

Primary factory methods should use `open(...)`. The target class name defines the operation role:

```java
ZipArkivoFileSystem.open(path)
ZipArkivoFileSystem.open(path, options)
```

Low-level reader, writer, editor, and streaming APIs may be introduced later only when a concrete use case cannot be served well through `FileSystem` and file attribute views.

## Core Abstractions

`arkivo-base` should define the shared contracts used by all format modules.

Potential core types include:

```java
ArkivoFormat
ArkivoFormats
ArkivoFileSystemFormat
ArkivoVolumeFileSystemFormat
ArkivoFileSystemProviderSupport
ArkivoPasswordProvider
ArkivoVolumeSource
CompressionCodec
CompressionCodecs
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

Archive formats operate on multiple entries and should expose `FileSystem` APIs first where the format can support a stable directory-like view.

Random-access archive formats such as zip and 7z should prioritize NIO `FileSystem` support.

Reading entry contents should use standard NIO operations such as `Files.newByteChannel`, `Files.copy`, and `Files.readAllBytes`.

Writing and editing archive contents should use standard NIO operations such as `Files.write`, `Files.copy`, `Files.createDirectories`, `Files.delete`, and `Files.move`.

Entry metadata should use standard NIO file attributes and format-specific attribute views:

```java
ZipArkivoEntryAttributes
ZipArkivoEntryAttributeView
```

Streaming archive APIs may be added later for formats that cannot naturally expose an efficient `FileSystem`, such as tar.

## Open Options and Storage Layout

Archive file system factories should support format-specific open options in addition to simple `Path` and channel overloads.

Open options should carry operation-level settings that are not entry metadata, including passwords, encryption defaults, character set policies, timestamp policies, overwrite behavior, and format-specific compatibility choices.

Split or multi-volume archives should be modeled as an archive storage layout rather than as item metadata or compression codec behavior.

Single-file archives may continue to use `Path`, `ReadableByteChannel`, `WritableByteChannel`, and `SeekableByteChannel` overloads. Split archives need storage abstractions that can resolve multiple physical volumes into one logical archive view.

Read support should be able to locate and open a sequence of volumes, such as numbered 7z, zip, or rar parts. Write support should be able to create successive volumes according to explicit file system options such as maximum volume size and naming policy.

Random-access formats should expose split archives through a logical seekable view so parser code can work with archive offsets without repeatedly handling physical volume boundaries.

## Passwords and Encryption

Passwords and other credentials should be treated as operation inputs, not archive metadata.

Archive open options should provide password or password-provider configuration for encrypted archives. Password providers should allow future support for formats that need more than one password attempt or entry-specific password lookup.

Encryption settings for newly written archives should be explicit write options. When a format supports entry-level encryption, such as ZIP, the entry writing model should allow per-item encryption choices in addition to archive-level defaults.

Formats with archive-level encryption features, such as 7z header encryption, should expose those features through format-specific write options.

The public API should distinguish between persistent file attributes, encryption method selection, and sensitive password material. Passwords should remain operation inputs and must not be exposed as archive entry attributes.

## FileSystem Integration

`FileSystem` support should be implemented inside each archive format module.

For example:

```text
arkivo-archives-zip
  ZipArkivoFormat
  ZipArkivoFileSystem
  ZipArkivoFileSystemOptions
  ZipArkivoEntryAttributes
  ZipArkivoEntryAttributeView
  ZipArkivoFileSystemProvider

arkivo-archives-tar
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

The provider implementation and registration should live in the concrete format module, not in a shared filesystem module.

A format module should not register an unfinished `FileSystemProvider` service, because an installed provider participates in global JDK provider discovery.

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

## File Attributes

Archive metadata should be exposed through standard NIO file attributes and format-specific attribute views instead of a shared entry object model.

Common metadata should map to standard NIO concepts where possible:

- Raw encoded entry path bytes
- Decoded entry path text
- Regular file, directory, and symbolic link classification
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

Each archive format should define only the attribute interfaces it needs, such as `ZipArkivoEntryAttributes` and `ZipArkivoEntryAttributeView`.

## Implementation Order

1. Convert the repository to a Gradle multi-module project.
2. Implement `arkivo-base` with format discovery, typed feature contracts, `FileSystem` support contracts, password and volume-source contracts, compression codec contracts, and low-level buffer utilities.
3. Implement gzip, zlib, and raw deflate codec modules to validate the channel-first compression API.
4. Define archive file system options, password provider contracts, and split archive source abstractions.
5. Implement `ZipArkivoFileSystem` inside `arkivo-archives-zip`.
6. Implement ZIP-specific entry attribute views.
7. Implement ZIP file system mutation support with explicit writeback semantics.
8. Add lower-level reader, writer, or streaming APIs only if FileSystem APIs are not sufficient for a concrete format.
9. Add xz and zstd codec modules.
10. Add tar support after deciding whether the public surface should be a filesystem, streaming API, or both.
11. Add 7z read support and a read-only 7z filesystem.
12. Add rar read support and a read-only rar filesystem.

## Compatibility Notes

RAR support should initially be read-only.

Some formats may not support efficient random write operations. These formats should expose streaming or copy-on-write APIs instead of pretending to support in-place mutation.

Format-specific features should be represented through typed interfaces and concrete format APIs rather than a shared boolean capability object.

Feature details such as supported encryption methods, header encryption support, split volume naming schemes, and write-time volume size constraints should remain in format-specific options and attribute views.
