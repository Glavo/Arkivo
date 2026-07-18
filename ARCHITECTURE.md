# Arkivo Architecture

Arkivo provides two related API families: compression codecs and archive containers. Both families use Java NIO types
as their primary transport and storage abstractions, while stream-based methods are adapters over the same underlying
operations.

This document describes the public programming model and module boundaries. Detailed contracts remain on the relevant
types and methods in the generated Javadoc.

## Public API foundations

### NIO integration

Arkivo uses the standard NIO abstractions according to the shape of the data:

- `ByteBuffer` drives incremental compression engines without binding them to a transport.
- `ReadableByteChannel` and `WritableByteChannel` carry forward-only compressed or archive data.
- `SeekableByteChannel` represents random-access archives and logical multi-volume inputs.
- `FileSystem`, `Path`, `Files`, and NIO attribute views expose archive entries as a hierarchical namespace when a
  format supports random access.
- `InputStream` and `OutputStream` overloads adapt stream-oriented applications to channel-based implementations.

Buffer operations use each buffer's position as the authoritative consumed or produced byte count. Unless a method
explicitly documents otherwise, Arkivo does not change a buffer's limit or byte order and does not retain the buffer
after the method returns.

### Configuration and operation state

Completed configuration values are immutable. A `CompressionCodec` value contains compression parameters and can be
shared safely between threads. Mutable builders are confined to configuration assembly and are not safe for concurrent
use unless their type says otherwise. Each encoder, decoder, channel, stream, archive reader, archive writer, and
archive file system owns independent operation state.

Configuration methods named `withXxx` return an immutable value and do not change the receiver; an implementation may
return the receiver when the requested setting is already present. Format-specific builders assemble configurations
with multiple related parameters and produce the same immutable codec values.

### Resource ownership

Factories that accept an existing channel or stream distinguish borrowed resources from owned resources. Codec adapter
overloads without an explicit `ResourceOwnership` argument borrow their endpoint; closing the adapter leaves the
borrowed endpoint open. An adapter created with `ResourceOwnership.OWNED` closes its endpoint when the adapter closes.

Archive factories document ownership on each overload because opening an archive may transfer a channel, a repeatable
source, a volume source, or a transaction. Once a factory successfully transfers ownership, closing the returned
reader, writer, or file system releases that resource. Setup failures close resources whose ownership was already
transferred and retain the primary failure, adding cleanup failures as suppressed exceptions.

## Compression model

### Formats and codecs

`CompressionFormat` is the service-discovered identity of a compression format. It provides stable names, file
extensions, signature probing, and the default codec configuration. `CompressionFormats` and
`CompressionFormatRegistry` discover, find, and detect installed formats.

`CompressionCodec<C>` describes one immutable algorithm and container configuration. Its optional configuration
subinterfaces expose parameters only where they have a meaningful common contract. Format-specific codec types expose
additional settings that cannot be represented uniformly.

### Incremental engines

`CompressionEncoder` and `CompressionDecoder` are stateful, transport-independent engines. They are not safe for
concurrent use. Each operation advances source and target positions by exactly the bytes it consumes and produces, then
returns a `CodecOutcome` explaining why it stopped.

| Outcome | Meaning |
| --- | --- |
| `NEEDS_INPUT` | The supplied source was exhausted before the encoding boundary was reached. |
| `NEEDS_OUTPUT` | The target filled while the engine still had pending work. |
| `NEEDS_DICTIONARY` | Decoding requires a format-specific dictionary before it can continue. |
| `FLUSHED` | Pending encoded data reached a non-terminal decodable boundary. |
| `BOUNDARY_REACHED` | A non-terminal format unit, such as a frame, completed. |
| `FINISHED` | The requested encoding or decoding operation completed. |

Encoding normally alternates `encode` calls with fresh input or output space. After the application has supplied all
uncompressed input, it repeats `finish` until `FINISHED`. A flush-capable encoder can emit a decodable boundary without
ending the encoding. A framed encoder can use `finishFrame` to end the current frame and remain ready for a following
frame; terminal `finish` ends the complete encoding session.

Decoding uses `decode` while more compressed input may arrive. Once the application knows that no additional input
exists, `finish` distinguishes a complete encoding from a truncated one. Framed convenience operations can stop after
one complete frame, leaving following frames or trailing bytes in the source buffer.

`reset` abandons current progress and restores the engine to its original codec configuration. `close` releases engine
resources without implicitly finishing or consuming more data.

### Adapters and operation options

Codec channel and stream adapters run the same engines through blocking I/O contracts. Transfer methods process a
complete input without closing caller-owned endpoints. Allocating buffer methods are intended for results that fit in a
single Java `ByteBuffer`; allocating decompression therefore requires a finite output bound.

`EncodingOptions` carries exact source-size metadata for one encoding operation. A framed encoder applies that metadata
to its first frame; following frames begin without a declared source size. `DecodingOptions` carries operation-scoped
bounds for decoded output, history windows, and decoder working memory. The output bound is enforced around every
decoder; format implementations apply window and memory bounds to the structures and allocations they can account for.
The memory value is not a JVM heap limit. Enforced-limit violations use dedicated checked exception types so callers
can distinguish resource policy from malformed input.
Dictionary-capable formats use format-specific `CompressionDictionary` and `DictionaryRequest` types because
dictionary identity and representation differ between algorithms.

## Archive model

### Formats and capabilities

`ArkivoFormat` is the service-discovered identity of an archive format. Capability subinterfaces describe supported
operations instead of assuming every container has the same access model:

- `ArkivoFileSystemFormat` opens archives with a stable random-access namespace.
- `ArkivoStreamingReaderFormat` reads entries from a forward-only source.
- `ArkivoStreamingWriterFormat` writes entries to a forward-only target.
- volume capability interfaces accept logical multi-volume sources and targets.

`ArkivoFormats` performs discovery and selects only formats implementing the capability required by the requested
operation.

### Archive file systems

An `ArkivoFileSystem` maps archive entries to ordinary NIO paths. Entry contents and directories are accessed through
`Files` and channels, while common and format-specific metadata is exposed through NIO file attributes and attribute
views. Publication is mode-specific: forward-only creation may write directly to its target as entries complete and
write the archive trailer on close, while complete-rewrite and transactional volume sessions publish their replacement
only when close succeeds.

Open entry streams, channels, and directory streams participate in the file system lifecycle. With `NONE`, callers
coordinate operations and close entry resources before closing the file system. `CONCURRENT_READ` waits for in-flight
operations, then leaves existing resource wrappers for their callers to close while rejecting further operations on
them. `STRICT` additionally force-closes registered entry resources. After a file system closes, new file-system
operations fail with the corresponding NIO closed-resource exception.

### Streaming readers and writers

`ArkivoStreamingReader` is a forward-only cursor. `next()` positions the cursor at an entry without requiring an entry
object or eager metadata parsing. Attribute methods read metadata for the current cursor position. The body can be
opened once; advancing or closing the reader closes an outstanding body channel before releasing the underlying source.
Attribute snapshots remain usable after the cursor advances.

`ArkivoStreamingWriter` permits one pending entry at a time. A `beginXxx` method returns an entry handle whose attribute
views remain configurable until the body opens. Closing the handle commits an entry without a body. Opening a body
channel or stream transfers entry completion to that body; another entry cannot begin until the body closes
successfully. Closing the writer completes any pending entry, writes format termination data, and releases the target.

### Metadata, credentials, and volumes

Common metadata uses `ArchiveEntryAttributes` and standard NIO attribute types. Format modules add typed attribute views
for fields that are part of that container format. Attribute snapshots describe stored archive state; passwords and
other credentials remain operation inputs rather than persistent entry attributes.

`ArkivoPasswordProvider` can select credentials from archive and entry context without exposing password material as
metadata. Multi-volume APIs separate logical archive offsets from physical volume naming and storage. Parsers therefore
operate on a logical source while the source or target implementation owns volume discovery and creation.

`ArchiveReadLimits` bounds archive metadata, entry counts, entry sizes, aggregate sizes, compression windows, and
decoder memory. Formats enforce only the limits applicable to their structure.

## Modules

| Module | Responsibility |
| --- | --- |
| `org.glavo.arkivo.base` | Qualified, low-level implementation primitives shared by selected modules. |
| `org.glavo.arkivo.codec` | Compression contracts, discovery, engines, adapters, limits, and transforms. |
| `org.glavo.arkivo.codec.*` | Pure Java implementations for individual compression families. |
| `org.glavo.arkivo.codec.all` | Transitive aggregate of all official codec modules. |
| `org.glavo.arkivo.archive` | Archive contracts, discovery, sources, targets, limits, and NIO foundations. |
| `org.glavo.arkivo.archive.*` | Implementations for individual archive formats. |
| `org.glavo.arkivo.archive.all` | Transitive aggregate of all official archive modules. |
| `org.glavo.arkivo.all` | Complete aggregate plus transparent compression probing for streaming archives. |

Implementation dependencies use ordinary JPMS requirements. Public API dependencies use transitive requirements so a
consumer can resolve every type appearing in an exported signature. Internal packages are either unexported or
qualified to the specific implementation modules that consume them.

## Service discovery

Archive and compression implementations register their format classes through both JPMS `provides` declarations and
classpath `META-INF/services` resources. Each format has a public no-argument constructor for service loading and an
`instance()` method for direct access to its shared descriptor.

Registries validate stable names, aliases, probe sizes, and duplicate registrations before exposing an immutable view.
Buffer-based signature matching inspects only the required prefix and does not alter caller buffer state. Channel-based
probing follows the replay and ownership contract of its probe result. Operations that require more than a format
identity select the corresponding capability interface before opening resources.

## Concurrency

Immutable formats, codec configurations, options, limits, and metadata snapshots are safe to share. Encoders, decoders,
streaming cursors, and ordinary channel or stream instances are stateful and require external synchronization when used
by more than one thread. Archive file systems coordinate operations according to their configured thread-safety and
close policies; the relevant file system type documents the visibility and conflict rules for its format.
