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
arkivo-archive
arkivo-codec
arkivo-codec-all
arkivo-codec-bcj
arkivo-codec-bzip2
arkivo-codec-deflate
arkivo-codec-deflate64
arkivo-codec-delta
arkivo-codec-gzip
arkivo-codec-lzma
arkivo-codec-zlib
arkivo-codec-xz
arkivo-codec-zstd
arkivo-archive-all
arkivo-archive-ar
arkivo-archive-zip
arkivo-archive-tar
arkivo-archive-7z
arkivo-archive-rar
arkivo-all
```

`arkivo-archive` should contain shared archive-format discovery, reusable `FileSystem` support, password and volume-source contracts, and archive-oriented NIO utilities.

`arkivo-codec` should contain codec APIs, service discovery, reusable byte-transform support, and codec-oriented NIO utilities without depending on archive modules.

`arkivo-codec-*` modules should implement individual codecs and reversible byte transforms.

`arkivo-codec-all` should aggregate all official codec modules.

`arkivo-archive-*` modules should implement individual archive formats, including their own `FileSystem` implementations when applicable.

`arkivo-archive-all` should aggregate all official archive-format modules.

`arkivo-all` should be an aggregate dependency module that brings in all supported formats.

The core `FileSystem` abstractions and reusable support classes belong in `arkivo-archive`, while each concrete archive `FileSystem` implementation belongs in the corresponding format module.

## Naming Model

The public archive API should use `Arkivo` as the project-specific replacement for the generic `Archive` noun.

This keeps the API distinct from Apache Commons Compress, the JDK ZIP API, and other archive libraries while remaining short and readable.

The preferred naming pattern is:

```text
FormatArkivoFileSystem
FormatArkivoEntryAttributes
FormatArkivoEntryAttributeView
```

For ZIP, the primary types should be:

```java
ZipArkivoFormat
ZipArkivoFileSystem
ZipArkivoEntryAttributes
ZipArkivoEntryAttributeView
```

`ZipArkivoFileSystem` should be the primary public API for ZIP archive access.

ZIP entry metadata should be exposed through NIO file attribute APIs, including `ZipArkivoEntryAttributes` and `ZipArkivoEntryAttributeView`.

Primary factory methods should use `open(...)`. The target class name defines the operation role:

```java
ZipArkivoFileSystem.open(path)
ZipArkivoFileSystem.open(path, environment)
```

Common file system configuration options should live on `ArkivoFileSystem` as typed `ArkivoFileSystemOption`
constants, such as `ArkivoFileSystem.THREAD_SAFETY`.
Format-specific file system configuration options should live on the concrete file system utility class as typed
`ArkivoFileSystemOption` constants.
Environment option keys should use a compact Arkivo namespace. Common option keys should use `arkivo.<option>`,
such as `arkivo.threadSafety`, while format-specific option keys should use `arkivo.<format>.<option>`, such as
`arkivo.zip.passwordProvider`.
`ArkivoFileSystemOption` should model the namespace and local option name separately, with the environment key derived
from those parts.
Standalone string key constants should not be exposed by concrete file system utility classes; callers can use
`ArkivoFileSystemOption.key()` when they need the stable map key.

Low-level reader, writer, editor, and streaming APIs should be introduced when a concrete use case cannot be served well through `FileSystem` and file attribute views.

## Core Abstractions

`arkivo-archive` should define the shared contracts used by all format modules.

Potential core types include:

```java
ArkivoFormat
ArkivoFormats
ArkivoPasswordProvider
ArkivoVolumeSource
ArkivoFileSystemOption
ArkivoFileSystem
ArkivoFileSystemFormat
ArkivoVolumeFileSystemFormat
ArkivoStreamingReaderFormat
ArkivoStreamingWriterFormat
ArkivoStreamingReader
ArkivoStreamingWriter
ArkivoEditStorage
ArkivoStoredContent
ArkivoCommitTarget
ArkivoCommitOutput
ArkivoSourceMutationPolicy
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

`CompressionCodecs` should open encoder and decoder contexts by stable codec name and should automatically open decoders for streams with reliable signatures. Context factories should apply explicit channel ownership consistently during lookup, detection, setup, and close. Named channel transfers should retain caller endpoints, while stream adapters should own their input or output stream.

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

ZIP-specific attributes should expose both typed common ZIP properties and raw low-level fields. Typed fields should include compression method, encryption method, sizes, CRC values, and comments. Raw fields should include encoded paths, raw comments, extra data, general purpose bit flags, version fields, and internal and external file attributes.

ZIP file systems should expose the optional preamble bytes stored before the ZIP archive body, such as self-extracting executable stubs, through channel-based APIs instead of forcing the content into memory.

Streaming archive APIs use channel-first reader and writer format contracts for forward-only access. AR, TAR, and ZIP expose streaming readers and writers; 7z exposes a streaming writer backed by seekable staging, while RAR exposes a read-only streaming reader.

AR, TAR, and single-volume ZIP complete-rewrite updates support both path-backed archives and arbitrary repeatable seekable channel sources. Non-path update sessions require an explicit commit target and can publish a derived archive without mutating the source; fixed path targets accept the absent source path while source-replacement targets reject it.

TAR channel-source updates preserve either the explicitly selected compression codec or the codec auto-detected from a repeatable source. Update mode validates both decoder and encoder availability before exposing a writable file system, including codecs without reliable stream signatures when selected explicitly.

ZIP channel-source updates borrow the source while indexing it, retain ownership through commit, preserve preamble bytes and surviving local records through exact range copies, and rebuild the central directory with relocated offsets. General multi-volume sources remain read-only; channel-source updates publish one derived archive and do not silently collapse a split source layout.

7z compression pipelines support ordered chains of Delta and BCJ executable filters, including x86, PowerPC, IA-64, ARM, ARM-Thumb, SPARC, ARM64, and RISC-V transforms. ARM64 and RISC-V use their modern single-byte 7z method IDs. BCJ readers and writers support optional unsigned 32-bit start offsets and enforce architecture-specific alignment.

7z entry attributes expose immutable structured coder graphs in declaration order, including raw coder properties, input and output stream ranges, bind pairs, physical packed-stream ordinals, per-output unpack sizes, and the final decoded output.

7z entry attributes expose physical read layout through immutable packed-range descriptors, logical archive offsets, decoded folder offsets, substream ordinals and counts, packed and decoded CRC-32 values, exact solid-substream classification, and named 7z attributes. Copy substreams expose directly addressable slices, while compressed solid entries expose the shared folder inputs required for decoding.

7z output supports configurable solid folders with an exact maximum non-empty file count. Consecutive files share one compression, filter, and optional AES pipeline only while their coder settings match; the writer emits per-file substream sizes and CRC-32 values and supports solid output through file-system creation, complete-rewrite updates, streaming writers, encrypted headers, and split publication.

ZIP file system support should distinguish normal editable file system mode from append-only streaming creation mode.

In normal editable mode, the implementation may use copy-on-write or temporary storage to mutate existing archives. In streaming creation mode, the file system should create a new archive, write entries sequentially, avoid keeping full entry contents in memory, write data descriptors when sizes are not known up front, and write the central directory when the file system is closed.

Streaming creation mode should reject operations that do not fit append-only ZIP output, such as reading archive entries, deleting entries, moving entries, or overwriting an entry that has already been written.

## Open Options and Storage Layout

Archive file system factories should support format-specific open options in addition to simple `Path` and channel overloads.

Open environment maps should carry operation-level settings that are not entry metadata, including passwords, encryption defaults, character set policies, timestamp policies, overwrite behavior, and format-specific compatibility choices.

ZIP entry name decoding should prefer authoritative Unicode metadata before falling back to an environment policy.
Readers should first honor validated Info-ZIP Unicode path and comment extra fields, then the UTF-8 general purpose bit flag, then the configured `ZipEntryNameEncoding` policy.
The policy should support standard CP437 fallback, an explicit fallback charset, and deterministic automatic detection from a caller-provided or default candidate list.

Split or multi-volume archives should be modeled as an archive storage layout rather than as item metadata or compression codec behavior.

Single-file archives may continue to use `Path`, `ReadableByteChannel`, `WritableByteChannel`, and `SeekableByteChannel` overloads. Split archives need storage abstractions that can resolve multiple physical volumes into one logical archive view.

Read support should be able to locate and open a sequence of volumes, such as numbered 7z, zip, or rar parts. Write support should be able to create successive volumes according to explicit file system environment keys such as maximum volume size and naming policy.

Random-access formats should expose split archives through a logical seekable view so parser code can work with archive offsets without repeatedly handling physical volume boundaries.

## Passwords and Encryption

Passwords and other credentials should be treated as operation inputs, not archive metadata.

Archive open environments should provide password or password-provider configuration for encrypted archives. Password providers should allow future support for formats that need more than one password attempt or entry-specific password lookup.

Encryption settings for newly written archives should be explicit write environment values. When a format supports entry-level encryption, such as ZIP, the entry writing model should allow per-item encryption choices in addition to archive-level defaults.

Formats with archive-level encryption features, such as 7z header encryption, should expose those features through format-specific write environment keys.

The public API should distinguish between persistent file attributes, encryption method selection, and sensitive password material. Passwords should remain operation inputs and must not be exposed as archive entry attributes.

## FileSystem Integration

`FileSystem` support should be implemented inside each archive format module.

For example:

```text
arkivo-archive-zip
  ZipArkivoFormat
  ZipArkivoFileSystem
  ZipArkivoEntryAttributes
  ZipArkivoEntryAttributeView
  ZipArkivoFileSystemProvider

arkivo-archive-7z
  SevenZipArkivoFormat
  SevenZipArkivoFileSystem
  SevenZipArkivoFileSystemProvider

arkivo-archive-tar
  TarArkivoFileSystem
```

The public API may expose convenience factories such as:

```java
ZipArkivoFileSystem.open(path)
SevenZipArkivoFileSystem.open(path)
```

Concrete archive file systems should extend `ArkivoFileSystem`.
Public archive file system classes may be sealed abstract API types, with concrete implementations kept in the
format module's internal package so implementation state and helper methods are not exposed as public API.

Formats may also register JDK `FileSystemProvider` implementations when the integration is useful:

```java
FileSystems.newFileSystem(archivePath)
```

The provider implementation and registration should live in the concrete format module, not in a shared filesystem module.

A format module should not register an unfinished `FileSystemProvider` service, because an installed provider participates in global JDK provider discovery.

Provider URI schemes should use `arkivo+format`, such as `arkivo+zip`, to keep Arkivo providers distinct while making the concrete archive format visible in the scheme.

## Thread Safety

Archive file system instances should be safe to use concurrently from multiple threads.

Immutable `Path` objects and immutable attribute snapshots may be shared freely between threads. Open channels, input streams, and output streams are not required to be thread-safe.

Read-only file systems should allow concurrent entry reads. Editable file systems should allow concurrent reads where possible, but mutating operations should be serialized or rejected when they conflict with another active operation.

Reads should observe the entry state captured when the channel or attribute snapshot is opened. Entries that are currently being written should not be readable unless a concrete format implementation explicitly documents stronger behavior.

Closing a file system should synchronize with active operations and prevent new operations from starting.

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

Optional or absent object metadata should use `@Nullable`, not `Optional`.
Optional or absent numeric metadata should use primitive numeric types with named negative sentinel constants when the
valid value domain is non-negative, such as `UNKNOWN_SIZE = -1L` or `UNKNOWN_OFFSET = -1L`.

Immutable collections, arrays, and buffer views should use JetBrains immutability annotations where applicable.

Each archive format should define only the attribute interfaces it needs, such as `ZipArkivoEntryAttributes` and `ZipArkivoEntryAttributeView`.

## Implementation Order

1. Convert the repository to a Gradle multi-module project.
2. Implement `arkivo-archive` with archive-format discovery, `FileSystem` support contracts, password and volume-source contracts, and archive-oriented buffer utilities.
3. Implement `arkivo-codec` with independent codec contracts, service discovery, and byte-transform support.
4. Implement gzip, zlib, and raw deflate codec modules to validate the channel-first compression API.
5. Define archive file system environment keys, password provider contracts, and split archive source abstractions.
6. Implement `ZipArkivoFileSystem` inside `arkivo-archive-zip`.
7. Implement ZIP-specific entry attribute views.
8. Implement ZIP file system mutation support with explicit writeback semantics.
9. Add lower-level reader, writer, or streaming APIs only if FileSystem APIs are not sufficient for a concrete format.
10. Add xz and zstd codec modules.
11. Add tar support after deciding whether the public surface should be a filesystem, streaming API, or both.
12. Extend 7z support from Copy, LZMA, LZMA2, substreams, and basic metadata to multi-coder filter pipelines and encrypted headers.
13. Add rar read support and a read-only rar filesystem.

## Compatibility Notes

RAR support should initially be read-only.

Some formats may not support efficient random write operations. These formats should expose streaming or copy-on-write APIs instead of pretending to support in-place mutation.

Format-specific features should be represented through concrete format APIs rather than shared capability interfaces or boolean capability objects.

Feature details such as supported encryption methods, header encryption support, split volume naming schemes, and write-time volume size constraints should remain in format-specific environment keys and attribute views.
