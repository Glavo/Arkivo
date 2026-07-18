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
arkivo-archive
arkivo-codec
arkivo-codec-all
arkivo-codec-bzip2
arkivo-codec-deflate
arkivo-codec-lzma
arkivo-codec-ppmd
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

`arkivo-base` contains internal low-level primitives shared by implementation modules without exposing a public API.

`arkivo-archive` should contain shared archive-format discovery, reusable `FileSystem` support, password and volume-source contracts, and archive-oriented NIO utilities.

`arkivo-codec` should contain codec APIs, service discovery, reusable byte-transform support, and codec-oriented NIO utilities without depending on archive modules.

`arkivo-codec-*` modules should implement cohesive codec families and reversible byte transforms.

`arkivo-codec-all` should aggregate all official codec modules.

`arkivo-archive-*` modules should implement individual archive formats, including their own `FileSystem` implementations when applicable.

`arkivo-archive-all` should aggregate all official archive-format modules.

`arkivo-all` should be an aggregate dependency module that brings in all supported formats.

The core `FileSystem` abstractions and reusable support classes belong in `arkivo-archive`, while each concrete archive `FileSystem` implementation belongs in the corresponding format module.

The qualified `org.glavo.arkivo.archive.internal` package centralizes reusable archive NIO state machines, including
slash-separated paths, fixed directory streams, immutable byte-array channels, forward-only output channels, principal
implementations, POSIX conversion, managed source adapters, and staged random-access body channels with shared access,
append, change-tracking, and completion semantics. Indexed content storage also shares identity ownership, bounded
transfers, empty-body adapters, default storage selection, and retryable open-failure cleanup. Format modules retain
only behavior coupled to their container metadata, storage ownership, or encoding rules. Installed archive file system
providers share nested-URI parsing, percent-decoded entry paths, atomic instance publication, duplicate-open rejection,
stale-instance recovery, and close-driven unregistration across AR, TAR, ZIP, 7z, and RAR.

Published JPMS descriptors mirror Gradle API exposure. Archive-format modules transitively expose `arkivo-archive`,
codec implementation modules transitively expose `arkivo-codec`, and implementation-only dependencies remain
non-transitive. Staged Maven verification derives Arkivo dependency scopes from each packaged module descriptor, so
`requires transitive` dependencies must use Maven `compile` scope while ordinary `requires` dependencies use `runtime`
scope. Aggregate verification also checks the exact public and qualified exports, transitive requirements, service uses,
and service providers of every packaged Arkivo module.

The aggregate API boundary suite records the reviewed public and protected API type set and inspects every reachable class,
field, constructor, method, generic, record, exception, and sealed-type signature. Public signatures may use only JDK
types or exported Arkivo types from the same module or a transitively required module. Sealed declarations may keep
their permitted implementations encapsulated inside the declaring module.

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
ZipArkivoFileSystem.open(path, options)
```

Common file system configuration options should live on `ArkivoFileSystem` as typed `ArchiveOption`
constants, such as `ArkivoFileSystem.THREAD_SAFETY`.
Format-specific file system configuration options should live on the concrete file system utility class as typed
`ArchiveOption` constants.
Application-facing factories should accept immutable `ArchiveOptions`; raw NIO environment maps should be converted
at the `FileSystemProvider` boundary.
Option keys should use a compact Arkivo namespace. Common option keys should use `arkivo.<option>`,
such as `arkivo.threadSafety`, while format-specific option keys should use `arkivo.<format>.<option>`, such as
`arkivo.zip.passwordProvider`.
`ArchiveOption` should model the namespace and local option name separately, with the environment key derived
from those parts.
Standalone string key constants should not be exposed by concrete file system utility classes; callers can use
`ArchiveOption.key()` when they need the stable NIO environment key.

Low-level reader, writer, editor, and streaming APIs should be introduced when a concrete use case cannot be served well through `FileSystem` and file attribute views.

## Core Abstractions

`arkivo-archive` should define the shared contracts used by all format modules.

Potential core types include:

```java
ArkivoFormat
ArkivoFormats
ArchiveFormatRegistry
ArchiveEntryAttributes
ArchiveOption
ArchiveOptions
ArkivoPasswordProvider
ArkivoVolumeSource
ArkivoPathVolumeFormat
ArkivoFileSystem
ArkivoFileSystemFormat
ArkivoVolumeFileSystemFormat
ArkivoVolumeChannel
ArkivoVolumePathLayout
ArkivoPathVolumeTarget
ArkivoStreamingReaderFormat
ArkivoVolumeStreamingReaderFormat
ArkivoStreamingWriterFormat
ArkivoVolumeStreamingWriterFormat
ArkivoStreamingReader
ArkivoStreamingWriter
ArkivoEditStorage
ArkivoStoredContent
ArkivoCommitTarget
ArkivoCommitOutput
CompressionCodec
CompressionFormat
CompressionFormatRegistry
CompressionFormats
```

The base module should define the contracts and support code as a format-independent foundation.

Format detection and implementation lookup should use direct `ServiceLoader<CompressionFormat>` registration, with
concrete format identities supplied by implementation modules.

## Compression API

Compression formats operate on a single byte stream.

The primary incremental API uses caller-owned `ByteBuffer` pairs through `CompressionEncoder` and
`CompressionDecoder`. Implementations consume and produce bytes by advancing buffer positions and must not retain source
or target buffers after an operation returns. Shared adapters expose the same engines as `ReadableByteChannel` and
`WritableByteChannel` contexts with explicit endpoint ownership.

Deflate, Deflate64, gzip, and zlib share pure Java format-parameterized buffer engines. BZip2, Zstandard, raw LZMA,
LZMA-alone, raw LZMA2, XZ, and PPMd also provide transport-independent buffer engines. PPMd range decoding preserves
partially normalized arithmetic intervals and context-chain progress across arbitrarily fragmented caller buffers.

Allocating and fixed `ByteBuffer` operations drive transport-independent engines directly. They accept heap and direct
buffers in any source and target combination, including read-only sources and nonzero buffer ranges. Targets must be
writable, and one buffer instance cannot serve as both source and target. Immutable codec configurations and
operation-scoped `DecompressionLimits` follow the same validation and exception contract across adapters.

`CompressionFormats` should find format identities and create adapters for their default codecs by stable format name.
Codec factories use `newEncoder()` and `newDecoder()` for transport-independent engines,
`newWritableByteChannel()` and `newReadableByteChannel()` for channel contexts, and `newOutputStream()` and
`newInputStream()` for stream adapters. Decoder factories should automatically detect streams with reliable signatures.
Adapter factories should apply explicit resource ownership consistently during lookup, detection, setup, and close.
Channel and stream adapters retain caller endpoints by default and transfer ownership only when
`ResourceOwnership.OWNED` is selected explicitly.

Zstandard signature detection recognizes the standard frame magic and all sixteen skippable-frame identifiers without
changing the probe buffer. Generic decoder factories accept streams beginning with skippable frames, and framed decoder
contexts can stop after one frame while preserving prefetched later frames.
The Zstandard codec also exposes explicit standard and magicless frame formats across channel, buffer, and frame-
inspection APIs. Magicless streams require caller selection and do not participate in signature detection. Decoder
checksum policy verifies XXH64 trailers by default or consumes them without verification when
`ZstdCodec.withVerifyChecksums(false)` is selected, preserving subsequent concatenated-frame boundaries in either
physical format.

XZ exposes the same decompression checksum policy for optional Block Checks. The default configuration verifies every
supported Check, while `XZCodec.withVerifyChecksums(false)` consumes the format-defined Check field without calculating
it and can therefore decode future Check IDs whose algorithms are not yet implemented. Stream, Block Header, Index, and
Footer CRC validation remains mandatory because those checks protect the container structure rather than optional
content integrity.

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

Archive paths resolve symbolic links component by component in `Path.toRealPath()`, preserve the normalized lexical path
when `NOFOLLOW_LINKS` is requested, detect link cycles, and use resolved paths for `Files.isSameFile` identity checks.
Readable archive file systems follow symbolic links for input streams, read-only byte channels, directory streams, and
typed or named attribute reads by default. Attribute reads with `NOFOLLOW_LINKS` expose the link itself, and
`Files.readSymbolicLink` remains the link-target text API. File attribute views retain their link traversal policy and
resolve links for each operation; writable view setters apply that same policy to the target or link entry. Forward-only
write file systems do not imply readable entry bodies merely because their paths and metadata are visible.

Archive file system providers share non-recursive copy behavior for regular files, directories, and symbolic links.
Copies follow symbolic links by default, preserve links with `NOFOLLOW_LINKS`, honor `REPLACE_EXISTING`, and transfer
basic timestamps with `COPY_ATTRIBUTES`. Writable archive targets receive supported timestamps while the entry header
is created so local entry records and central metadata remain consistent.

Moves within one writable archive file system rename complete entry subtrees and validate every collision before
changing session state. ZIP complete-rewrite moves update both local and central header names while preserving the
original compressed or encrypted payload bytes, descriptors, CRC values, comments, and unrelated extra fields.
Ordinary cross-provider `Files.move` operations use the JDK copy-then-delete path, while `ATOMIC_MOVE` remains limited
to one archive namespace.

ZIP complete-rewrite update sessions persist last-modified timestamps, POSIX permissions, internal and external file
attributes, and raw entry comments for both source entries and entries written during the session. Timestamp updates
rewrite matching local and central header fields while retaining compressed or encrypted payload bytes exactly.
Traditional encrypted entries using a timestamp-derived data-descriptor verification byte reject changes that would
alter that byte, because preserving the encrypted body would otherwise make password verification fail.
Compression, encryption, sizes, checksums, and raw extra fields remain write-time settings because changing them after
an entry body is complete can require body re-encoding or invalidate generated ZIP64 and encryption metadata.

Entry metadata should use standard NIO file attributes and format-specific attribute views:

```java
ZipArkivoEntryAttributes
ZipArkivoEntryAttributeView
```

ZIP-specific attributes should expose both typed common ZIP properties and raw low-level fields. Typed fields should include compression method, encryption method, sizes, CRC values, and comments. Raw fields should include encoded paths, raw comments, extra data, general purpose bit flags, version fields, and internal and external file attributes.

ZIP file systems should expose the optional preamble bytes stored before the ZIP archive body, such as self-extracting executable stubs, through channel-based APIs instead of forcing the content into memory.

ZIP Deflate, BZip2, Deflate64, raw LZMA, XZ, and Zstandard entry reading and writing use the general codec context API.
The ZIP module has a non-transitive implementation dependency on `arkivo-codec-deflate` for its mandatory core method
and depends on `arkivo-codec` while discovering the other compression formats at runtime. Streaming Deflate, BZip2,
Deflate64, raw LZMA, XZ, and Zstandard entries with data descriptors preserve exact compressed-stream boundaries through
decoder progress and unconsumed-input reporting. ZIP LZMA retains a container adapter for its property header while the
raw EOS-framed payload uses the optional raw LZMA format's default codec.

Streaming archive APIs use channel-first reader and writer format contracts for forward-only access. AR, TAR, and ZIP expose streaming readers and writers; 7z exposes a streaming writer backed by seekable staging, while RAR exposes a read-only streaming reader.
Common archive read options limit logical entry count, individual entry size, total logical entry size, and cumulative
metadata size across AR, TAR, ZIP, 7z, and RAR file-system and streaming readers. Declared entry sizes are rejected
before entry data is exposed; forward-only entries whose size is absent from metadata are limited by observed decoded
bytes, including skipped bytes. Fixed and variable metadata regions are charged before buffering or decoded expansion.
Structured `ArkivoReadLimitException` failures remain sticky for the lifetime of a reader so parsing cannot resume
after a configured boundary is exceeded.

RAR file systems build a metadata-only index at open time and lazily decode each readable body through bounded edit
storage on first access. Repeated reads and RAR5 content redirections share the materialized body by identity. Lazy
solid-entry access rescans the owned repeatable volume source and reconstructs preceding compression history without
retaining unrelated bodies; cached storage cleanup waits for active body channels and remains retryable after failures.

AR, TAR, single-volume ZIP, and single-volume 7z complete-rewrite updates support both path-backed archives and arbitrary repeatable seekable channel sources. Non-path update sessions require an explicit commit target and can publish a derived archive without mutating the source; fixed path targets accept the absent source path while source-replacement targets reject it.

`ArkivoFormats` preserves that update contract for both detected and explicitly named formats when opening owned seekable channels or repeatable channel sources. It forwards immutable options unchanged, transfers endpoint ownership exactly once, and rejects non-path updates without a commit target before exposing a writable file system.

Named and detected streaming factories use channel-first endpoints. Stream overloads adapt to the same channel factories, named single-volume and multi-volume operations share capability resolution, and closeable endpoints transfer exactly once after argument validation. Detection borrows its source, while paths and volume targets remain caller-owned configuration values; selected formats own only the transactions opened from volume targets.

`ArkivoFormats` provides named transactional creation and both detected and named complete-rewrite updates for multi-volume random-access formats. The unified factories preserve caller-selected split sizes, transfer source ownership once after argument validation, and leave target publication and rollback to the selected format.

`ArkivoFormats` provides detected and named multi-volume streaming reader factories through
`ArkivoVolumeStreamingReaderFormat`. ZIP reads byte-contiguous split storage through the shared logical volume channel,
while RAR preserves format-level continuation signatures, end markers, and split-entry state across physical volumes.

`ArkivoFormats` provides named multi-volume streaming writer factories through `ArkivoVolumeStreamingWriterFormat`. ZIP and 7z publish split output through transactional volume targets, while formats limited to one forward-only stream do not advertise the multi-volume capability.

TAR channel-source updates preserve either the explicitly selected compression codec or the codec auto-detected from a repeatable source. Update mode validates both decoder and encoder availability before exposing a writable file system, including codecs without reliable stream signatures when selected explicitly.

ZIP channel-source updates borrow the source while indexing it, retain ownership through commit, preserve preamble bytes and surviving local records through exact range copies, and rebuild the central directory with relocated offsets. Complete-rewrite sessions retain a read-only companion for surviving entries and stage uncompressed bodies for immediate reads of completed replacements and additions. General volume sources remain read-only through `open`; explicit volume-source updates publish a complete replacement through a transactional volume target with a caller-selected split size and disk-relative local-header metadata.

ZIP complete-rewrite entry channels stage uncompressed bodies through `ArkivoEditStorage` before emitting replacement
local records. They support combined read/write access, random positioning, append, truncation, and standard create
semantics while keeping direct creation and append-only output channels forward-only.

7z channel-source updates stage decoded entry bodies through edit storage and re-encode surviving entries with the selected compression, filters, solid policy, password, and header-encryption policy. General volume sources remain read-only through the open API; explicit volume-source updates use a transactional volume target and a caller-selected split size.

7z compression pipelines support ordered chains of Delta and BCJ executable filters, including x86, PowerPC, IA-64, ARM, ARM-Thumb, SPARC, ARM64, and RISC-V transforms. ARM64 and RISC-V use their modern single-byte 7z method IDs. BCJ readers and writers support optional unsigned 32-bit start offsets and enforce architecture-specific alignment. BCJ2 is exposed as a sole graph filter: output is split into MAIN, CALL, JUMP, and range branches, the first three branches use the selected compression, and every physical branch is encrypted independently when data encryption is enabled. BCJ2 folders use temporary-file staging to preserve contiguous physical streams with bounded heap use.

7z Deflate, Deflate64, BZip2, raw LZMA, LZMA2, and Zstandard coder reading and writing use the general codec context API and discover optional
formats at runtime. PPMd7 reading uses the same optional format discovery and passes the coder's model order, memory size,
and exact unpack size as typed raw-stream options. Decoder contexts enforce the coder's declared unpack size. Raw LZMA and
LZMA2 properties remain 7z metadata while the payloads are handled by codecs derived from the optional raw formats' defaults.

7z entry attributes expose immutable structured coder graphs in declaration order, including raw coder properties, input and output stream ranges, bind pairs, physical packed-stream ordinals, per-output unpack sizes, and the final decoded output.

7z entry attributes expose physical read layout through immutable packed-range descriptors, logical archive offsets, decoded folder offsets, substream ordinals and counts, packed and decoded CRC-32 values, exact solid-substream classification, and named 7z attributes. Copy substreams expose directly addressable slices, while compressed solid entries expose the shared folder inputs required for decoding.

7z output supports configurable solid folders with an exact maximum non-empty file count. Consecutive files share one compression, filter, and optional AES pipeline only while their coder settings match; the writer emits per-file substream sizes and CRC-32 values and supports solid output through file-system creation, complete-rewrite updates, streaming writers, encrypted headers, and split publication.

ZIP file system support should distinguish normal editable file system mode from append-only streaming creation mode.

In normal editable mode, the implementation may use copy-on-write or temporary storage to mutate existing archives. In streaming creation mode, the file system should create a new archive, write entries sequentially, avoid keeping full entry contents in memory, write data descriptors when sizes are not known up front, and write the central directory when the file system is closed.

Streaming creation mode should reject operations that do not fit append-only ZIP output, such as reading archive entries, deleting entries, moving entries, or overwriting an entry that has already been written.

## Open Options and Storage Layout

Archive file system factories should support format-specific open options in addition to simple `Path` and channel overloads.

`ArchiveOptions` should carry operation-level settings that are not entry metadata, including passwords, encryption defaults, character set policies, timestamp policies, overwrite behavior, and format-specific compatibility choices.

ZIP entry name decoding should prefer authoritative Unicode metadata before falling back to a configured option.
Readers should first honor validated Info-ZIP Unicode path and comment extra fields, then the UTF-8 general purpose bit flag, then the configured `ZipLegacyCharsetDetector`, falling back to CP437 when detection is inconclusive.
The detector should receive an isolated read-only byte view, may select a charset or report an inconclusive result, and should remain pluggable rather than embedding a fixed heuristic candidate list in the archive API.

Split or multi-volume archives should be modeled as an archive storage layout rather than as item metadata or compression codec behavior.

`ArkivoVolumeChannel` presents a finite `ArkivoVolumeSource` as one read-only logical `SeekableByteChannel`, exposes
physical volume boundaries, and owns the independently opened channels without taking ownership of the source contract.
ZIP and 7z share this implementation for cross-volume random access and consistent failure cleanup.

`ArkivoVolumePathLayout` gives callers and format modules explicit control over final-volume-dependent path naming and stale-volume discovery. `ArkivoPathVolumeTarget` applies reusable staged publication, create-new validation, replacement cleanup, and rollback restoration to any such layout; ZIP and 7z use this common transaction implementation for path-backed split output.

`ArkivoPathVolumeFormat` exposes conventional input-path discovery without moving format-specific naming rules into the
core module. ZIP discovers numbered disks from final-disk metadata, while 7z and RAR resolve their conventional
numbered sequences from the first volume path. Path-based streaming reader factories use this capability before
falling back to one forward-only file and its optional outer codec transformation.

Single-file archives may continue to use `Path`, `ReadableByteChannel`, `WritableByteChannel`, and `SeekableByteChannel` overloads. Split archives need storage abstractions that can resolve multiple physical volumes into one logical archive view.

Read support should be able to locate and open a sequence of volumes, such as numbered 7z, zip, or rar parts. Write support should be able to create successive volumes according to explicit archive options such as maximum volume size and naming policy.

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
arkivo-archive-zip
  ZipArkivoFormat
  ZipArkivoFileSystem
  ZipArkivoEntryAttributes
  ZipArkivoEntryAttributeView
  internal.ZipArkivoFileSystemProvider

arkivo-archive-7z
  SevenZipArkivoFormat
  SevenZipArkivoFileSystem
  internal.SevenZipArkivoFileSystemProvider

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

Every installed archive format module registers its JDK `FileSystemProvider` implementation for both JPMS and
classpath service discovery:

```java
FileSystems.newFileSystem(archivePath)
```

Provider implementations are encapsulated in each format module's internal package. Their service registration lives
in the concrete format module, while URI parsing, path format claiming, and registered instance lifecycle behavior
remain shared archive infrastructure.

Provider integration tests open AR, TAR, ZIP, 7z, and RAR through `FileSystems.newFileSystem(URI, ...)`, resolve entry
URIs through `Path.of(URI)`, exercise content and extension routing through `FileSystems.newFileSystem(Path)`, and
verify discovery from packaged JARs on both the classpath and module path.

Provider URI schemes should use `arkivo+format`, such as `arkivo+zip`, to keep Arkivo providers distinct while making the concrete archive format visible in the scheme.

## Thread Safety

Archive file system instances should be safe to use concurrently from multiple threads.

Immutable `Path` objects and immutable attribute snapshots may be shared freely between threads. Open channels, input streams, and output streams are not required to be thread-safe.

Read-only file systems should allow concurrent entry reads. Editable file systems should allow concurrent reads where possible, but mutating operations should be serialized or rejected when they conflict with another active operation.

Reads should observe the entry state captured when the channel or attribute snapshot is opened. Entries that are currently being written should not be readable unless a concrete format implementation explicitly documents stronger behavior.

Closing a file system should synchronize with active operations and prevent new operations from starting.

The aggregate concurrency suite generates AR, TAR, ZIP, solid 7z, and stored RAR4 archives without binary fixtures,
repeatedly reads them from eight simultaneous tasks, and verifies every default file system honors `CONCURRENT_READ`.
The same suite proves real provider operations exclude close until an in-flight directory read finishes and verifies
`STRICT` close invalidates open entry channels, input streams, and directory iterators for every format.

The aggregate mutation suite verifies AR, TAR, ZIP, and 7z update sessions retain the state captured by open entry
channels, directory streams, and attribute snapshots across replacement, deletion, move, addition, and metadata
changes, then verifies the committed state after reopening. AR, TAR, and 7z reject new reads of an entry while its
replacement channel is open; ZIP explicitly exposes the preceding committed state until that replacement commits.

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

The aggregate benchmark source set uses JMH for transport-independent buffer engines, channel-adapter codec
throughput, ZIP and 7z indexing, and concurrent random-entry reads. Benchmarks are compiled by the normal quality
gate but run separately so machine-dependent timing does not make builds flaky. Deterministic gates verify that 50,000 ZIP entries can be indexed with a 48 MiB maximum
heap, that 80 MiB decoded ZIP and 7z entries and a 64 MiB RAR entry can be opened and randomly read with a 32 MiB
maximum heap through hybrid `ArkivoEditStorage` staging, and that ZIP and solid 7z entries can be repeatedly read from
eight concurrent tasks without corruption. The RAR probe also verifies that metadata-only file-system opening stages no
entry body before its first content access.

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
4. Implement the raw Deflate, Deflate64, gzip, and zlib codec family module to validate the buffer-first compression API.
5. Define typed archive options, password provider contracts, and split archive source abstractions.
6. Implement `ZipArkivoFileSystem` inside `arkivo-archive-zip`.
7. Implement ZIP-specific entry attribute views.
8. Implement ZIP file system mutation support with explicit writeback semantics.
9. Add lower-level reader, writer, or streaming APIs only if FileSystem APIs are not sufficient for a concrete format.
10. Add xz and zstd codec modules.
11. Add tar support after deciding whether the public surface should be a filesystem, streaming API, or both.
12. Extend 7z support from Copy, LZMA, LZMA2, substreams, and basic metadata to multi-coder filter pipelines and encrypted headers.
13. Add rar read support and a read-only rar filesystem.

## Publication and Release

Every module publishes a Maven binary, sources, Javadoc, POM, and Gradle Module Metadata artifact. Published JARs
include the repository MPL 2.0 license. POM metadata identifies the project, license, developer, source repository, issue
tracker, and exact compile or runtime dependency scope.

The workspace-local Maven staging task validates all module coordinates, artifacts, checksums, metadata, and dependency
scopes. Central Portal bundles require a non-SNAPSHOT release version and an in-memory OpenPGP key, validate signatures
and checksums before packaging, and omit repository-level Maven metadata from the upload archive.

GitHub Actions run the complete check and Javadoc gates on the minimum Java version for Linux and Windows and on the
current LTS Java version for Linux.

The aggregate verification suite freezes the reviewed 1.0 API as text rather than a checked-in binary artifact. It
checks the exact exported type set and all public and protected JVM descriptors, generic declarations, inheritance,
sealed hierarchies, flags, annotation defaults, and inlinable primitive and string constant values. Intentional API
changes are reviewed through the generated baseline, while unreviewed additions, removals, and signature changes fail
the normal `check` lifecycle.

## Compatibility Notes

RAR support should initially be read-only.

The aggregate verification suite dynamically generates AR, TAR, ZIP, and 7z archives with an independent
implementation and verifies that Arkivo can read them. It also verifies the reverse direction by reading Arkivo's
streaming output with that implementation. These fixtures cover representative long-name, Unicode, POSIX metadata,
symbolic-link, and compression behavior without storing binary samples in the repository.
Split interoperability fixtures cover standards-sized ZIP archives in both directions and encrypted, header-encrypted
7z archives in both directions. ZIP split writers emit the required leading split signature, constrain configured
volume sizes to 64 KiB through the unsigned 32-bit limit, keep local and central directory header records within one
disk, and place the ZIP64 locator and end-of-central-directory record on the same disk.

BCJ2 decoding validates the exact MAIN, CALL, JUMP, and range stream sizes, branch-stream alignment, the complete
output-size relation, and the terminal zero range code used by the official decoder. The production writer is compared
byte-for-byte with a deterministic independent encoder pinned to the side-stream sizes and SHA-256 values produced by
7-Zip 26.01. Archive tests cover every supported branch compression, solid folders, four independent AES branches,
encrypted headers, split publication, and complete-rewrite updates. An optional `ARKIVO_7Z_EXECUTABLE` gate lets an
official 7-Zip CLI fully test generated BCJ2 archives without requiring a checked-in binary fixture.

Deterministic malformed-input verification generates archive and codec fixtures at test time, applies representative
truncations and 128 fixed-seed bit mutations per format, and fully consumes every accepted entry under explicit output,
metadata, and time bounds. Generated AR, TAR, ZIP, 7z, and stored RAR4 archives cover detection, random-access file
systems, and forward-only readers where supported; the same failure contract covers every installed bidirectional codec
decoder. Corrupt input must produce checked I/O failures rather than leaking parser indexing, argument-validation,
unsupported-capability, native-library, or resource-exhaustion runtime failures.

Opt-in real-world tests resolve immutable upstream source releases and individual fixtures through a project-local
content-addressed cache outside `build`, verify size and SHA-256 before use, and stage only reviewed data in disposable
build outputs. The ordinary test and clean lifecycles do not access the network or delete the persistent cache. Zstandard
pins the official 1.5.7 golden compression, decompression, malformed-frame, dictionary, and dictionary-input vectors, including the zero-weight entropy-table regression;
XZ pins the XZ Utils 5.8.3 valid, malformed, and unsupported XZ and LZMA_Alone decoder vectors; and BZip2 pins the
official 1.0.8 reference samples for exact decoding, block-size boundary encoding, and concatenated streams. Valid
decompression outputs are checked against upstream reference bytes or sizes and SHA-256 values produced by the
matching official CLI. Raw Deflate, zlib, gzip, and Deflate64 additionally use individually pinned Apache Commons
Compress 1.28.0 fixtures, including gzip 1.13 concatenated members and COMPRESS-380 Deflate64 regressions, without
storing the binary fixtures in the repository. Archive compatibility additionally pins the official libarchive 3.8.7
source release and stages only its license and reviewed uuencoded fixtures. Runtime decoding covers AR, GNU TAR, ZIP,
7z, RAR4, and RAR5, including long names, large numeric fields, invalid CRC handling, compressed data, directories,
and symbolic links without committing decoded archives. ZIP optional-codec fixtures cover BZip2, LZMA, XZ, and
Zstandard through both random-access and forward-only readers, including malformed-stream hang and leak regressions.
Traditional PKWARE and WinZip AES-128/256 fixtures verify absent, incorrect, and correct passwords, stored and
Deflate payloads, large multi-entry authentication, and resource ownership in both reader modes.
The 7z set exercises Copy, LZMA1, LZMA2, BZip2, Deflate, PPMd7, Zstandard,
Delta, BCJ, BCJ2, ARM, ARM64, RISC-V, PowerPC, and SPARC graphs, mixed and solid folders, and all supported AES header
and data combinations. The same corpus verifies unified random-access detection over arbitrary in-memory seekable
channels and forward-only AR, TAR, ZIP, and RAR decoding over non-seekable readable channels.

Some formats may not support efficient random write operations. These formats should expose streaming or copy-on-write APIs instead of pretending to support in-place mutation.

Format-specific features should be represented through concrete format APIs rather than shared capability interfaces or boolean capability objects.

Feature details such as supported encryption methods, header encryption support, split volume naming schemes, and write-time volume size constraints should remain in format-specific options and attribute views.
