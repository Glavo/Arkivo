// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.glavo.arkivo.archive.internal.ArkivoReadLimitTracker;
import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.rar.RarArkivoEntryAttributes;
import org.glavo.arkivo.archive.rar.RarArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributes;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;

/// Implements the public forward-only RAR streaming reader API.
@NotNullByDefault
public final class RarArkivoStreamingReaderImpl extends RarArkivoStreamingReader {
    /// The maximum self-extracting stub size searched before the RAR signature.
    private static final int MAX_SFX_SIZE = 1024 * 1024;

    /// The maximum RAR5 header data size accepted by current RAR implementations.
    private static final int MAX_HEADER_SIZE = 2 * 1024 * 1024;

    /// The RAR5 archive signature.
    private static final byte @Unmodifiable [] RAR5_SIGNATURE =
            new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x01, 0x00};

    /// The RAR4 archive signature.
    private static final byte @Unmodifiable [] RAR4_SIGNATURE =
            new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x00};

    /// The internal version marker for RAR4 archives.
    private static final int RAR_VERSION_4 = 4;

    /// The internal version marker for RAR5 archives.
    private static final int RAR_VERSION_5 = 5;

    /// The main archive header type.
    private static final int HEADER_TYPE_MAIN = 1;

    /// The file header type.
    private static final int HEADER_TYPE_FILE = 2;

    /// The service header type.
    private static final int HEADER_TYPE_SERVICE = 3;

    /// The encrypted archive header type.
    private static final int HEADER_TYPE_ARCHIVE_ENCRYPTION = 4;

    /// The end of archive header type.
    private static final int HEADER_TYPE_END = 5;

    /// The RAR4 main archive header type.
    private static final int RAR4_HEADER_TYPE_MAIN = 0x73;

    /// The RAR4 file header type.
    private static final int RAR4_HEADER_TYPE_FILE = 0x74;

    /// The RAR4 end of archive header type.
    private static final int RAR4_HEADER_TYPE_END = 0x7b;

    /// RAR5 end header flag indicating that another archive volume follows.
    private static final long END_FLAG_NEXT_VOLUME = 0x0001L;

    /// RAR4 end header flag indicating that another archive volume follows.
    private static final long RAR4_END_FLAG_NEXT_VOLUME = 0x0001L;

    /// RAR5 main archive flag indicating that compressed files can share a solid dictionary.
    private static final long RAR5_MAIN_FLAG_SOLID = 0x0004L;

    /// Header flag indicating that an extra area is present.
    private static final long HEADER_FLAG_EXTRA_AREA = 0x0001L;

    /// Header flag indicating that a data area follows the header.
    private static final long HEADER_FLAG_DATA_AREA = 0x0002L;

    /// Header flag indicating that data continues from the previous volume.
    private static final long HEADER_FLAG_CONTINUE_PREVIOUS = 0x0008L;

    /// Header flag indicating that data continues in the next volume.
    private static final long HEADER_FLAG_CONTINUE_NEXT = 0x0010L;

    /// RAR4 common header flag indicating that a data area follows the header.
    private static final long RAR4_HEADER_FLAG_DATA_AREA = 0x8000L;

    /// RAR4 main header flag indicating that archive headers are encrypted.
    private static final long RAR4_MAIN_FLAG_PASSWORD = 0x0080L;

    /// RAR4 main header flag indicating that compressed files can share a solid dictionary.
    private static final long RAR4_MAIN_FLAG_SOLID = 0x0008L;

    /// RAR4 main header flag indicating that an old-style archive comment is embedded in the header.
    private static final long RAR4_MAIN_FLAG_COMMENT = 0x0002L;

    /// RAR4 main header flag indicating that an encryption-version byte is present.
    private static final long RAR4_MAIN_FLAG_ENCRYPTION_VERSION = 0x0200L;

    /// RAR4 file header flag indicating that data continues from the previous volume.
    private static final long RAR4_FILE_FLAG_CONTINUE_PREVIOUS = 0x0001L;

    /// RAR4 file header flag indicating that data continues in the next volume.
    private static final long RAR4_FILE_FLAG_CONTINUE_NEXT = 0x0002L;

    /// RAR4 file header flag indicating that entry data is encrypted.
    private static final long RAR4_FILE_FLAG_PASSWORD = 0x0004L;

    /// RAR4 file header flag indicating that an old-style file comment is embedded in the header.
    private static final long RAR4_FILE_FLAG_COMMENT = 0x0008L;

    /// RAR4 file header flag indicating that this entry continues a solid compression stream.
    private static final long RAR4_FILE_FLAG_SOLID = 0x0010L;

    /// RAR4 file header mask containing dictionary size or the directory marker.
    private static final long RAR4_FILE_DICTIONARY_MASK = 0x00e0L;

    /// RAR4 file header dictionary value marking a directory entry.
    private static final long RAR4_FILE_IS_DIRECTORY = 0x00e0L;

    /// RAR4 file header flag indicating that high size fields are present.
    private static final long RAR4_FILE_FLAG_LARGE = 0x0100L;

    /// RAR4 file header flag indicating that the name field includes Unicode metadata.
    private static final long RAR4_FILE_FLAG_UNICODE = 0x0200L;

    /// RAR4 file header flag indicating that an eight-byte encryption salt is present.
    private static final long RAR4_FILE_FLAG_SALT = 0x0400L;

    /// RAR4 file header flag indicating that extended timestamp fields are present.
    private static final long RAR4_FILE_FLAG_EXTENDED_TIME = 0x1000L;

    /// The RAR4 main header size excluding its two-byte CRC and embedded old-style comments.
    private static final int RAR4_MAIN_HEADER_DATA_SIZE = 11;

    /// The RAR4 file header size excluding its two-byte CRC, variable fields, and embedded comments.
    private static final int RAR4_FILE_HEADER_DATA_SIZE = 30;

    /// The raw RAR4 host operating system value for MS-DOS entries.
    private static final int RAR4_HOST_OS_MSDOS = 0;

    /// The raw RAR4 host operating system value for OS/2 entries.
    private static final int RAR4_HOST_OS_OS2 = 1;

    /// The raw RAR4 host operating system value for Windows entries.
    private static final int RAR4_HOST_OS_WINDOWS = 2;

    /// The raw RAR4 host operating system value for Unix entries.
    private static final int RAR4_HOST_OS_UNIX = 3;

    /// The RAR4 compression method byte for stored entries.
    private static final int RAR4_METHOD_STORE = 0x30;

    /// The RAR4 compression method byte for the fastest compressed entries.
    private static final int RAR4_METHOD_FASTEST = 0x31;

    /// The RAR4 compression method byte for the best compressed entries.
    private static final int RAR4_METHOD_BEST = 0x35;

    /// The largest RAR4 Unix symbolic-link target accepted for eager metadata decoding.
    private static final int MAXIMUM_RAR4_SYMBOLIC_LINK_TARGET_SIZE = 1 << 20;

    /// File flag indicating that the entry is a directory.
    private static final long FILE_FLAG_DIRECTORY = 0x0001L;

    /// File flag indicating that the Unix modification time field is present.
    private static final long FILE_FLAG_UNIX_TIME = 0x0002L;

    /// File flag indicating that the data CRC32 field is present.
    private static final long FILE_FLAG_DATA_CRC = 0x0004L;

    /// File flag indicating that the unpacked size field should be ignored.
    private static final long FILE_FLAG_UNKNOWN_UNPACKED_SIZE = 0x0008L;

    /// The mask for RAR5 compression method bits.
    private static final long COMPRESSION_METHOD_MASK = 0x0380L;

    /// The number of low bits before the compression method field.
    private static final int COMPRESSION_METHOD_SHIFT = 7;

    /// The mask for the RAR5 compression algorithm version.
    private static final long COMPRESSION_ALGORITHM_MASK = 0x003fL;

    /// The RAR5 compression flag indicating that this entry continues a solid stream.
    private static final long COMPRESSION_FLAG_SOLID = 0x0040L;

    /// The number of low bits before the RAR5 dictionary power field.
    private static final int COMPRESSION_DICTIONARY_POWER_SHIFT = 10;

    /// The RAR5 dictionary power mask used by compression algorithm version zero.
    private static final long COMPRESSION_DICTIONARY_POWER_V5_MASK = 0x0fL;

    /// The RAR5 dictionary power mask used by compression algorithm version one.
    private static final long COMPRESSION_DICTIONARY_POWER_V7_MASK = 0x1fL;

    /// The mask for the RAR7 fractional dictionary size field.
    private static final long COMPRESSION_DICTIONARY_FRACTION_MASK = 0x1fL;

    /// The number of low bits before the RAR7 fractional dictionary size field.
    private static final int COMPRESSION_DICTIONARY_FRACTION_SHIFT = 15;

    /// The RAR7 flag selecting RAR5-compatible Huffman coding.
    private static final long COMPRESSION_FLAG_RAR5_COMPATIBLE = 0x0010_0000L;

    /// The file encryption extra record type.
    private static final int EXTRA_TYPE_FILE_ENCRYPTION = 0x01;

    /// The supported RAR5 encryption algorithm version.
    private static final long RAR5_ENCRYPTION_VERSION = 0L;

    /// Encryption flag indicating that password verification data is present.
    private static final long ENCRYPTION_FLAG_PASSWORD_CHECK = 0x0001L;

    /// File encryption flag selecting password-dependent checksums.
    private static final long ENCRYPTION_FLAG_TWEAKED_CHECKSUMS = 0x0002L;

    /// All recognized file encryption flags.
    private static final long ENCRYPTION_FLAG_MASK =
            ENCRYPTION_FLAG_PASSWORD_CHECK | ENCRYPTION_FLAG_TWEAKED_CHECKSUMS;

    /// The file hash extra record type.
    private static final int EXTRA_TYPE_FILE_HASH = 0x02;

    /// The file time extra record type.
    private static final int EXTRA_TYPE_FILE_TIME = 0x03;

    /// The file system redirection extra record type.
    private static final int EXTRA_TYPE_REDIRECTION = 0x05;

    /// The Unix owner extra record type.
    private static final int EXTRA_TYPE_UNIX_OWNER = 0x06;

    /// Unix owner flag indicating that the user name is present.
    private static final long UNIX_OWNER_USER_NAME = 0x0001L;

    /// Unix owner flag indicating that the group name is present.
    private static final long UNIX_OWNER_GROUP_NAME = 0x0002L;

    /// Unix owner flag indicating that the numeric user identifier is present.
    private static final long UNIX_OWNER_USER_ID = 0x0004L;

    /// Unix owner flag indicating that the numeric group identifier is present.
    private static final long UNIX_OWNER_GROUP_ID = 0x0008L;

    /// File time flag indicating that time values use Unix time instead of Windows FILETIME.
    private static final long FILE_TIME_UNIX = 0x0001L;

    /// File time flag indicating that the modification time is present.
    private static final long FILE_TIME_MODIFIED = 0x0002L;

    /// File time flag indicating that the creation time is present.
    private static final long FILE_TIME_CREATED = 0x0004L;

    /// File time flag indicating that the last access time is present.
    private static final long FILE_TIME_ACCESSED = 0x0008L;

    /// File time flag indicating that Unix time values include nanosecond precision fields.
    private static final long FILE_TIME_UNIX_NANOS = 0x0010L;

    /// The number of 100-nanosecond Windows FILETIME ticks per second.
    private static final long WINDOWS_FILETIME_TICKS_PER_SECOND = 10_000_000L;

    /// The Unix epoch offset in Windows FILETIME seconds.
    private static final long WINDOWS_FILETIME_UNIX_EPOCH_SECONDS = 11_644_473_600L;

    /// The RAR5 BLAKE2sp hash byte length.
    private static final int BLAKE2SP_HASH_SIZE = 32;

    /// The RAR5 hash type for BLAKE2sp.
    private static final long HASH_TYPE_BLAKE2SP = 0L;

    /// The bounded pipe capacity used between the RAR4 decoder and stream consumer.
    private static final int RAR_DECODE_PIPE_SIZE = 64 * 1024;

    /// The fallback timestamp used when a RAR entry omits all time metadata.
    private static final FileTime MISSING_TIME = FileTime.fromMillis(0L);

    /// The backing archive input stream.
    private final InputStream source;

    /// The password provider for encrypted RAR headers and entries, or `null` when none is configured.
    private final @Nullable ArkivoPasswordProvider passwordProvider;

    /// The common archive read-limit tracker.
    private final ArkivoReadLimitTracker readLimits;

    /// Whether bodies skipped during entry iteration must be integrity-checked.
    private final boolean validateSkippedBodies;

    /// The CRC32 calculator reused for header validation.
    private final CRC32 headerCrc32 = new CRC32();

    /// The CRC32 calculator reused for stored file data validation.
    private final CRC32 dataCrc32 = new CRC32();

    /// The BLAKE2sp calculator reused for stored RAR5 file data validation.
    private final RarBlake2sp dataBlake2sp = new RarBlake2sp();

    /// The current entry attributes, or `null` when no entry is active.
    private @Nullable RarEntryAttributes currentAttributes;

    /// The current physical file-part attributes, or `null` when no entry part is active.
    private @Nullable RarEntryAttributes currentPartAttributes;

    /// The encryption metadata for the current physical file part, or `null` when it is not encrypted.
    private @Nullable RarEncryptionInfo currentPartEncryption;

    /// The format-specific decompression metadata for the current physical file part.
    private @Nullable RarCompressionInfo currentPartCompression;

    /// The remaining packed bytes in the current entry data area.
    private long currentPackedRemaining;

    /// Whether the current entry body has already been opened.
    private boolean currentBodyOpened;

    /// Whether the current entry body has already been consumed or skipped completely.
    private boolean currentBodyConsumed;

    /// Whether the current stored body is being checked against a CRC32 value.
    private boolean currentCrcActive;

    /// Whether the current stored body CRC32 has already been verified.
    private boolean currentCrcVerified;

    /// The expected unsigned CRC32 value for the current stored body.
    private long currentExpectedCrc32 = RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE;

    /// Whether the current stored RAR5 body is being checked against a BLAKE2sp hash.
    private boolean currentBlake2spActive;

    /// Whether the current stored RAR5 body BLAKE2sp hash has already been verified.
    private boolean currentBlake2spVerified;

    /// The expected BLAKE2sp hash for the current stored RAR5 body, or `null` when absent.
    private byte @Nullable @Unmodifiable [] currentExpectedBlake2sp;

    /// The password-derived key for a tweaked current checksum, or `null` for a plain checksum.
    private byte @Nullable [] currentHashKey;

    /// The active encrypted entry stream, or `null` before an encrypted body is opened.
    private @Nullable EncryptedEntryInputStream currentEncryptedInput;

    /// The active decompression stream, or `null` before a compressed body is opened.
    private @Nullable DecodedInputStream currentDecodedInput;

    /// The lazily initialized RAR4 decoder session retaining solid dictionary state.
    private Rar4Decoder.@Nullable Session rar4DecoderSession;

    /// Whether packed RAR4 data was skipped and invalidated a future solid continuation.
    private boolean rar4DecoderHistoryInvalid;

    /// The lazily initialized RAR5 decoder session retaining solid dictionary state.
    private Rar5Decoder.@Nullable Session rar5DecoderSession;

    /// Whether packed RAR5 data was skipped and invalidated a future solid continuation.
    private boolean rar5DecoderHistoryInvalid;

    /// The active RAR5 AES key for encrypted archive headers, or `null` before an encryption header.
    private byte @Nullable [] archiveHeaderKey;

    /// Whether subsequent RAR4 headers are independently salt-prefixed and AES encrypted.
    private boolean rar3HeadersEncrypted;

    /// Whether the current RAR4 archive declares a solid compression sequence.
    private boolean rar4ArchiveSolid;

    /// Whether the current RAR5 archive declares a solid compression sequence.
    private boolean rar5ArchiveSolid;

    /// Whether the RAR signature has been consumed.
    private boolean signatureRead;

    /// The archive version detected from the RAR signature.
    private int rarVersion;

    /// Whether the end of archive marker has been consumed.
    private boolean finished;

    /// Whether this reader is open for archive operations.
    private boolean open = true;

    /// Whether the backing archive input stream has been closed.
    private boolean sourceClosed;

    /// Creates a streaming RAR reader.
    public RarArkivoStreamingReaderImpl(InputStream source) {
        this(source, null, Map.of());
    }

    /// Creates a streaming RAR reader that owns a multi-volume source.
    public RarArkivoStreamingReaderImpl(ArkivoVolumeSource source) {
        this(source, null, Map.of());
    }

    /// Creates a streaming RAR reader that owns a multi-volume source with an optional password provider.
    public RarArkivoStreamingReaderImpl(
            ArkivoVolumeSource source,
            @Nullable ArkivoPasswordProvider passwordProvider
    ) {
        this(source, passwordProvider, Map.of());
    }

    /// Creates a configured streaming RAR reader that owns a multi-volume source.
    public RarArkivoStreamingReaderImpl(
            ArkivoVolumeSource source,
            @Nullable ArkivoPasswordProvider passwordProvider,
            Map<String, ?> environment
    ) {
        this(new RarVolumeInputStream(source, true), passwordProvider, environment);
    }

    /// Creates a streaming RAR reader with an optional password provider.
    public RarArkivoStreamingReaderImpl(InputStream source, @Nullable ArkivoPasswordProvider passwordProvider) {
        this(source, passwordProvider, Map.of());
    }

    /// Creates a configured streaming RAR reader with an optional password provider.
    public RarArkivoStreamingReaderImpl(
            InputStream source,
            @Nullable ArkivoPasswordProvider passwordProvider,
            Map<String, ?> environment
    ) {
        this(source, passwordProvider, environment, true);
    }

    /// Creates a configured reader with explicit skipped-body validation behavior.
    RarArkivoStreamingReaderImpl(
            InputStream source,
            @Nullable ArkivoPasswordProvider passwordProvider,
            Map<String, ?> environment,
            boolean validateSkippedBodies
    ) {
        this.source = Objects.requireNonNull(source, "source");
        this.passwordProvider = passwordProvider;
        this.readLimits = ArkivoReadLimitTracker.fromEnvironment(environment);
        this.validateSkippedBodies = validateSkippedBodies;
    }

    /// Advances to the next RAR file entry and returns whether an entry is available.
    @Override
    public boolean next() throws IOException {
        ensureOpen();
        readLimits.requireWithinLimits();
        readSignature();
        if (finished) {
            currentAttributes = null;
            currentPartAttributes = null;
            return false;
        }

        skipCurrentEntryBody();
        currentAttributes = null;
        currentPartAttributes = null;
        currentPartEncryption = null;
        currentPartCompression = null;

        while (true) {
            @Nullable BlockHeader block = readBlockHeader();
            if (block == null) {
                finished = true;
                return false;
            }

            switch (block.type()) {
                case HEADER_TYPE_MAIN -> {
                    readMainHeaderMetadata(block);
                    skipBlockData(block);
                }
                case HEADER_TYPE_FILE -> {
                    ParsedFileHeader parsedFile = parseFileHeader(block);
                    RarEntryAttributes attributes = parsedFile.attributes();
                    readLimits.acceptEntry(attributes.path(), attributes.unpackedSize());
                    currentAttributes = attributes;
                    currentPartAttributes = attributes;
                    currentPartEncryption = parsedFile.encryptionInfo();
                    currentPartCompression = parsedFile.compressionInfo();
                    currentPackedRemaining = block.dataSize();
                    currentBodyOpened = false;
                    currentBodyConsumed = false;
                    prepareCurrentIntegrity();
                    if (rarVersion == 4 && isRar4UnixSymbolicLink(attributes)) {
                        RarEntryAttributes symbolicLinkAttributes = readRar4UnixSymbolicLink(attributes);
                        currentAttributes = symbolicLinkAttributes;
                        currentPartAttributes = symbolicLinkAttributes;
                    }
                    return true;
                }
                case HEADER_TYPE_SERVICE -> skipBlockData(block);
                case HEADER_TYPE_ARCHIVE_ENCRYPTION -> {
                    enableEncryptedHeaders(block);
                    skipBlockData(block);
                }
                case HEADER_TYPE_END -> {
                    skipBlockData(block);
                    if (!endHeaderHasNextVolume(block)) {
                        finished = true;
                        return false;
                    }
                    advanceAfterEndHeader();
                }
                default -> skipBlockData(block);
            }
        }
    }

    /// Reads the current archive entry attributes as the requested attribute type.
    @Override
    public <A extends BasicFileAttributes> A readAttributes(Class<A> type) throws IOException {
        ensureOpen();
        Objects.requireNonNull(type, "type");
        RarEntryAttributes attributes = currentAttributes;
        if (attributes == null) {
            throw new IllegalStateException("RAR streaming reader is not positioned at an entry");
        }
        if (type == BasicFileAttributes.class || type == RarArkivoEntryAttributes.class || type == PosixFileAttributes.class) {
            return type.cast(attributes);
        }
        throw new UnsupportedOperationException("Unsupported RAR streaming attributes type: " + type.getName());
    }

    /// Returns whether Arkivo can decode the current regular-file body with the configured format support.
    boolean isCurrentBodyReadable() {
        RarEntryAttributes attributes = currentAttributes;
        if (attributes == null
                || !attributes.isRegularFile()
                || attributes.continuesFromPreviousVolume()) {
            return false;
        }
        if (attributes.compressionMethod() == 0) {
            if (!attributes.isEncrypted()) {
                return true;
            }
            RarEncryptionInfo encryptionInfo = currentPartEncryption;
            return encryptionInfo instanceof Rar5EncryptionInfo
                    || encryptionInfo instanceof Rar3EncryptionInfo rar3Info
                    && isSupportedRar4EncryptionVersion(rar3Info.extractionVersion());
        }

        RarCompressionInfo compressionInfo = currentPartCompression;
        if (compressionInfo == null
                || !isSupportedCompressionMethod(attributes.compressionMethod())
                || attributes.unpackedSize() == RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE) {
            return false;
        }
        if (compressionInfo instanceof Rar4CompressionInfo rar4Info) {
            if (!Rar4Decoder.supports(rar4Info.extractionVersion())
                    || rar4Info.solid() && rar4DecoderHistoryInvalid) {
                return false;
            }
            return !attributes.isEncrypted()
                    || currentPartEncryption instanceof Rar3EncryptionInfo rar3Info
                    && isSupportedRar4EncryptionVersion(rar3Info.extractionVersion());
        }

        Rar5CompressionInfo rar5Info = (Rar5CompressionInfo) compressionInfo;
        if (rar5Info.algorithmVersion() > 1
                || !Rar5Decoder.supportsDictionary(
                rar5Info.dictionaryPower(),
                rar5Info.dictionaryFraction()
        )
                || rar5Info.solid() && rar5DecoderHistoryInvalid) {
            return false;
        }
        return !attributes.isEncrypted() || currentPartEncryption instanceof Rar5EncryptionInfo;
    }

    /// Opens a readable channel for the current supported file entry.
    @Override
    public ReadableByteChannel openChannel() throws IOException {
        ensureOpen();
        RarEntryAttributes attributes = currentAttributes;
        if (attributes == null) {
            throw new IOException("RAR streaming reader has not advanced to an entry");
        }
        if (currentBodyOpened) {
            throw new IOException("RAR entry body has already been opened");
        }
        if (attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
            currentBodyOpened = true;
            return StreamChannelAdapters.readableChannel(InputStream.nullInputStream());
        }
        if (currentBodyConsumed) {
            throw new IOException("RAR entry body has already been opened");
        }
        if (attributes.continuesFromPreviousVolume()) {
            throw new IOException("RAR entry starts in a previous volume");
        }
        if (attributes.compressionMethod() != 0) {
            return StreamChannelAdapters.readableChannel(openCompressedInput(attributes));
        }
        if (attributes.isEncrypted()) {
            return openEncryptedChannel(attributes);
        }
        currentBodyOpened = true;
        InputStream entryInput = new EntryInputStream();
        if (attributes.unpackedSize() == RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE) {
            entryInput = readLimits.trackUnknownEntrySize(attributes.path(), entryInput);
        }
        return StreamChannelAdapters.readableChannel(entryInput);
    }

    /// Opens one supported compressed body through its format-specific stateful decoder.
    private DecodedInputStream openCompressedInput(RarEntryAttributes attributes) throws IOException {
        RarCompressionInfo compressionInfo = currentPartCompression;
        if (compressionInfo instanceof Rar4CompressionInfo) {
            return openRar4CompressedInput(attributes);
        }
        if (compressionInfo instanceof Rar5CompressionInfo) {
            return openRar5CompressedInput(attributes);
        }
        throw new IOException("RAR entry compression metadata is unavailable");
    }

    /// Opens one supported RAR4 compressed input through the stateful decoder session.
    private DecodedInputStream openRar4CompressedInput(RarEntryAttributes attributes) throws IOException {
        RarCompressionInfo rawCompressionInfo = currentPartCompression;
        if (!(rawCompressionInfo instanceof Rar4CompressionInfo compressionInfo)
                || !isSupportedCompressionMethod(attributes.compressionMethod())
                || !Rar4Decoder.supports(compressionInfo.extractionVersion())) {
            throw new IOException("Unsupported RAR compression method: " + attributes.compressionMethod());
        }
        if (attributes.unpackedSize() == RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE) {
            throw new IOException("Compressed RAR4 entry has an unknown unpacked size");
        }

        InputStream packedInput = openRar4PackedInput(attributes);
        try {
            Rar4Decoder.Session session = rar4DecoderSession();
            if (!compressionInfo.solid()) {
                rar4DecoderHistoryInvalid = false;
            }
            return startDecodedInput(
                    packedInput,
                    new Rar4DecompressingReadableByteChannel(session, compressionInfo),
                    attributes
            );
        } catch (IOException | RuntimeException | Error exception) {
            closeFailedPackedInput(packedInput, exception);
            throw exception;
        }
    }

    /// Opens one supported RAR5 compressed input through the stateful decoder session.
    private DecodedInputStream openRar5CompressedInput(RarEntryAttributes attributes) throws IOException {
        RarCompressionInfo rawCompressionInfo = currentPartCompression;
        if (!(rawCompressionInfo instanceof Rar5CompressionInfo compressionInfo)
                || !isSupportedCompressionMethod(attributes.compressionMethod())
                || compressionInfo.algorithmVersion() > 1
                || !Rar5Decoder.supportsDictionary(
                compressionInfo.dictionaryPower(),
                compressionInfo.dictionaryFraction()
        )) {
            throw new IOException("Unsupported RAR compression method: " + attributes.compressionMethod());
        }
        if (compressionInfo.solid() && rar5DecoderHistoryInvalid) {
            throw new IOException("RAR5 solid entry is missing its preceding decompression history");
        }
        if (attributes.unpackedSize() == RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE) {
            throw new IOException("Compressed RAR5 entry has an unknown unpacked size");
        }

        InputStream packedInput;
        if (attributes.isEncrypted()) {
            RarEncryptionInfo encryptionInfo = currentPartEncryption;
            if (!(encryptionInfo instanceof Rar5EncryptionInfo rar5EncryptionInfo)) {
                throw new IOException("Unsupported encrypted RAR5 compression metadata");
            }
            packedInput = openRar5PackedInput(attributes, rar5EncryptionInfo);
        } else {
            packedInput = new PackedEntryInputStream();
        }

        try {
            Rar5Decoder.Session session = rar5DecoderSession();
            if (!compressionInfo.solid()) {
                rar5DecoderHistoryInvalid = false;
            }
            return startDecodedInput(
                    packedInput,
                    new Rar5DecompressingReadableByteChannel(session, compressionInfo),
                    attributes
            );
        } catch (IOException | RuntimeException | Error exception) {
            closeFailedPackedInput(packedInput, exception);
            Rar5Crypto.clear(currentHashKey);
            currentHashKey = null;
            throw exception;
        }
    }

    /// Starts one bounded decompression worker and installs its current-entry state.
    private DecodedInputStream startDecodedInput(
            InputStream packedInput,
            DecompressingReadableByteChannel decoder,
            RarEntryAttributes attributes
    ) throws IOException {
        DecodedInputStream decodedInput = new DecodedInputStream(packedInput, decoder, attributes);
        currentDecodedInput = decodedInput;
        currentBodyOpened = true;
        currentCrcActive = false;
        currentCrcVerified = false;
        currentExpectedCrc32 = attributes.dataCrc32();
        currentBlake2spActive = false;
        currentBlake2spVerified = false;
        currentExpectedBlake2sp = null;
        return decodedInput;
    }

    /// Closes a packed stream after decoder startup fails and suppresses any close failure.
    private static void closeFailedPackedInput(InputStream packedInput, Throwable problem) {
        try {
            packedInput.close();
        } catch (IOException closeException) {
            problem.addSuppressed(closeException);
        }
    }

    /// Opens the bounded plaintext packed stream consumed by the RAR4 decompressor.
    private InputStream openRar4PackedInput(RarEntryAttributes attributes) throws IOException {
        if (!attributes.isEncrypted()) {
            return new PackedEntryInputStream();
        }

        RarEncryptionInfo encryptionInfo = currentPartEncryption;
        if (!(encryptionInfo instanceof Rar3EncryptionInfo rar3Info)
                || !isSupportedRar4EncryptionVersion(rar3Info.extractionVersion())) {
            throw new IOException("Unsupported encrypted RAR4 compression version");
        }
        ArkivoPasswordProvider provider = passwordProvider;
        if (provider == null) {
            throw new IOException("RAR password provider is required for encrypted entry: " + attributes.path());
        }
        byte @Nullable [] password = provider.passwordForArchive();
        if (password == null) {
            throw new IOException("RAR password provider did not supply an archive password");
        }
        try {
            if (RarLegacyCrypto.supports(rar3Info.extractionVersion())) {
                if (rar3Info.salt() != null) {
                    throw new IOException("Legacy RAR encryption must not contain an AES salt");
                }
                return new LegacyEncryptedPackedInputStream(
                        RarLegacyCrypto.decryptor(rar3Info.extractionVersion(), password)
                );
            }

            try (Rar3Crypto.DerivedKeys keys = Rar3Crypto.deriveKeys(password, rar3Info.salt())) {
                byte[] key = keys.key();
                byte[] initializationVector = keys.initializationVector();
                try {
                    return new EncryptedPackedInputStream(Rar3Crypto.decryptor(key, initializationVector));
                } finally {
                    Rar3Crypto.clear(key);
                    Rar3Crypto.clear(initializationVector);
                }
            }
        } finally {
            Rar3Crypto.clear(password);
        }
    }

    /// Returns the lazily initialized RAR4 decoder session for this archive stream.
    private Rar4Decoder.Session rar4DecoderSession() throws IOException {
        Rar4Decoder.Session session = rar4DecoderSession;
        if (session == null) {
            session = Rar4Decoder.newSession();
            if (rar4DecoderHistoryInvalid) {
                session.invalidateHistory();
            }
            rar4DecoderSession = session;
        }
        return session;
    }

    /// Returns the lazily initialized RAR5 decoder session for this archive stream.
    private Rar5Decoder.Session rar5DecoderSession() {
        Rar5Decoder.Session session = rar5DecoderSession;
        if (session == null) {
            session = Rar5Decoder.newSession();
            if (rar5DecoderHistoryInvalid) {
                session.invalidateHistory();
            }
            rar5DecoderSession = session;
        }
        return session;
    }
    /// Opens and validates one supported encrypted stored entry body.
    private ReadableByteChannel openEncryptedChannel(RarEntryAttributes attributes) throws IOException {
        RarEncryptionInfo encryptionInfo = currentPartEncryption;
        if (encryptionInfo == null) {
            throw new IOException("RAR entry encryption metadata is unavailable");
        }
        if (attributes.unpackedSize() == RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE) {
            throw new IOException("Encrypted RAR stored entry has an unknown unpacked size");
        }
        if (encryptionInfo instanceof Rar3EncryptionInfo rar3EncryptionInfo) {
            return openRar3EncryptedChannel(attributes, rar3EncryptionInfo);
        }
        return openRar5EncryptedChannel(attributes, (Rar5EncryptionInfo) encryptionInfo);
    }

    /// Opens one supported encrypted RAR4 stored entry body.
    private ReadableByteChannel openRar3EncryptedChannel(
            RarEntryAttributes attributes,
            Rar3EncryptionInfo encryptionInfo
    ) throws IOException {
        int extractionVersion = encryptionInfo.extractionVersion();
        if (!isSupportedRar4EncryptionVersion(extractionVersion)) {
            throw new IOException("Unsupported legacy RAR encryption version: " + extractionVersion);
        }
        ArkivoPasswordProvider provider = passwordProvider;
        if (provider == null) {
            throw new IOException("RAR password provider is required for encrypted entry: " + attributes.path());
        }

        byte @Nullable [] password = provider.passwordForArchive();
        if (password == null) {
            throw new IOException("RAR password provider did not supply an archive password");
        }
        try {
            if (RarLegacyCrypto.supports(extractionVersion)) {
                if (encryptionInfo.salt() != null) {
                    throw new IOException("Legacy RAR encryption must not contain an AES salt");
                }
                RarLegacyCrypto.Decryptor decryptor = RarLegacyCrypto.decryptor(extractionVersion, password);
                return startEncryptedChannel(
                        attributes,
                        new LegacyEncryptedPackedInputStream(decryptor),
                        decryptor.blockSize(),
                        null
                );
            }

            try (Rar3Crypto.DerivedKeys keys = Rar3Crypto.deriveKeys(password, encryptionInfo.salt())) {
                byte[] key = keys.key();
                byte[] initializationVector = keys.initializationVector();
                try {
                    return startEncryptedChannel(
                            attributes,
                            new EncryptedPackedInputStream(Rar3Crypto.decryptor(key, initializationVector)),
                            Rar3Crypto.BLOCK_SIZE,
                            null
                    );
                } finally {
                    Rar3Crypto.clear(key);
                    Rar3Crypto.clear(initializationVector);
                }
            }
        } finally {
            Rar3Crypto.clear(password);
        }
    }

    /// Opens one RAR5 AES-256 encrypted stored entry body.
    private ReadableByteChannel openRar5EncryptedChannel(
            RarEntryAttributes attributes,
            Rar5EncryptionInfo encryptionInfo
    ) throws IOException {
        InputStream decryptedInput = openRar5PackedInput(attributes, encryptionInfo);
        return startEncryptedChannel(
                attributes,
                decryptedInput,
                Rar5Crypto.BLOCK_SIZE,
                currentHashKey
        );
    }

    /// Opens one AES-decrypted RAR5 packed stream and retains its optional checksum key.
    private InputStream openRar5PackedInput(
            RarEntryAttributes attributes,
            Rar5EncryptionInfo encryptionInfo
    ) throws IOException {
        ArkivoPasswordProvider provider = passwordProvider;
        if (provider == null) {
            throw new IOException("RAR5 password provider is required for encrypted entry: " + attributes.path());
        }

        byte @Nullable [] password = provider.passwordForArchive();
        if (password == null) {
            throw new IOException("RAR5 password provider did not supply an archive password");
        }
        byte @Nullable [] hashKey = null;
        try (Rar5Crypto.DerivedKeys keys = Rar5Crypto.deriveKeys(
                password,
                encryptionInfo.salt(),
                encryptionInfo.kdfLog()
        )) {
            byte @Nullable [] expectedPasswordCheck = encryptionInfo.passwordCheck();
            if (expectedPasswordCheck != null && !keys.matchesPasswordCheck(expectedPasswordCheck)) {
                throw new IOException("Incorrect RAR5 password for entry: " + attributes.path());
            }

            byte[] aesKey = keys.aesKey();
            try {
                if (encryptionInfo.tweakedChecksums()) {
                    hashKey = keys.hashKey();
                }
                EncryptedPackedInputStream packedInput = new EncryptedPackedInputStream(
                        Rar5Crypto.decryptor(aesKey, encryptionInfo.initializationVector())
                );
                currentHashKey = hashKey;
                hashKey = null;
                return packedInput;
            } finally {
                Rar5Crypto.clear(aesKey);
            }
        } finally {
            Rar5Crypto.clear(hashKey);
            Rar5Crypto.clear(password);
        }
    }
    /// Installs one initialized decryptor as the active encrypted entry body.
    private ReadableByteChannel startEncryptedChannel(
            RarEntryAttributes attributes,
            InputStream decryptedInput,
            int blockSize,
            byte @Nullable [] hashKey
    ) {
        EncryptedEntryInputStream encryptedInput = new EncryptedEntryInputStream(
                decryptedInput,
                attributes.unpackedSize(),
                blockSize
        );
        currentEncryptedInput = encryptedInput;
        currentHashKey = hashKey;
        currentBodyOpened = true;
        currentCrcActive = attributes.dataCrc32() != RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE;
        currentCrcVerified = false;
        currentExpectedCrc32 = attributes.dataCrc32();
        if (currentCrcActive) {
            dataCrc32.reset();
        }
        currentExpectedBlake2sp = attributes.blake2spHash();
        currentBlake2spActive = currentPartCompression instanceof Rar5CompressionInfo
                && (currentExpectedBlake2sp != null || attributes.continuesInNextVolume());
        currentBlake2spVerified = false;
        if (currentBlake2spActive) {
            dataBlake2sp.reset();
        }
        return StreamChannelAdapters.readableChannel(encryptedInput);
    }

    /// Closes this streaming reader.
    @Override
    public void close() throws IOException {
        if (!open && sourceClosed) {
            return;
        }
        DecodedInputStream decodedInput = currentDecodedInput;
        Rar4Decoder.Session rar4Session = rar4DecoderSession;
        Rar5Decoder.Session rar5Session = rar5DecoderSession;
        open = false;
        currentAttributes = null;
        currentPartAttributes = null;
        currentPartEncryption = null;
        currentPartCompression = null;
        clearCurrentBodyState();
        Rar5Crypto.clear(archiveHeaderKey);
        archiveHeaderKey = null;
        rar3HeadersEncrypted = false;
        rar4ArchiveSolid = false;
        rar5ArchiveSolid = false;
        rar4DecoderHistoryInvalid = false;
        rar5DecoderHistoryInvalid = false;
        rar4DecoderSession = null;
        rar5DecoderSession = null;

        @Nullable IOException failure = null;
        try {
            source.close();
            sourceClosed = true;
        } catch (IOException exception) {
            failure = exception;
        }
        if (decodedInput != null) {
            try {
                decodedInput.awaitWorker();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (rar4Session != null) {
            rar4Session.release();
        }
        if (rar5Session != null) {
            rar5Session.release();
        }
        currentPackedRemaining = 0L;
        if (failure != null) {
            throw failure;
        }
    }

    /// Reads and validates the RAR signature.
    private void readSignature() throws IOException {
        if (signatureRead) {
            return;
        }

        byte[] window = new byte[RAR5_SIGNATURE.length];
        int count = 0;
        for (int readBytes = 0; readBytes < MAX_SFX_SIZE + RAR5_SIGNATURE.length; readBytes++) {
            int value = source.read();
            if (value < 0) {
                throw new EOFException("RAR signature is missing");
            }
            window[count % window.length] = (byte) value;
            count++;
            if (endsWith(window, count, RAR5_SIGNATURE)) {
                readLimits.acceptMetadata(RAR5_SIGNATURE.length, null);
                rarVersion = RAR_VERSION_5;
                signatureRead = true;
                return;
            }
            if (endsWith(window, count, RAR4_SIGNATURE)) {
                readLimits.acceptMetadata(RAR4_SIGNATURE.length, null);
                rarVersion = RAR_VERSION_4;
                signatureRead = true;
                return;
            }
        }
        throw new IOException("RAR signature is missing");
    }

    /// Returns whether the rolling window ends with the expected byte sequence.
    private static boolean endsWith(byte[] window, int count, byte[] expected) {
        if (count < expected.length) {
            return false;
        }
        int start = count - expected.length;
        for (int index = 0; index < expected.length; index++) {
            if (window[(start + index) % window.length] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    /// Reads the next RAR block header, or `null` at archive EOF.
    private @Nullable BlockHeader readBlockHeader() throws IOException {
        if (source instanceof RarVolumeInputStream volumeInput && volumeInput.advanceAtHeaderBoundary()) {
            resetContinuationVolumeState();
        }
        if (rarVersion == RAR_VERSION_4) {
            return readRar4BlockHeader();
        }
        return readRar5BlockHeader();
    }

    /// Advances past end-of-volume padding after a next-volume end header.
    private void advanceAfterEndHeader() throws IOException {
        if (source instanceof RarVolumeInputStream volumeInput) {
            if (!volumeInput.advanceAfterEndHeader()) {
                throw new EOFException("Unexpected end of RAR multi-volume archive");
            }
            resetContinuationVolumeState();
        }
    }

    /// Clears format state that is independently declared by each physical volume.
    private void resetContinuationVolumeState() {
        Rar5Crypto.clear(archiveHeaderKey);
        archiveHeaderKey = null;
        rar3HeadersEncrypted = false;
        rar4ArchiveSolid = false;
        rar5ArchiveSolid = false;
    }

    /// Reads the next RAR5 block header, or `null` at archive EOF.
    private @Nullable BlockHeader readRar5BlockHeader() throws IOException {
        return archiveHeaderKey != null
                ? readEncryptedRar5BlockHeader()
                : readPlainRar5BlockHeader();
    }

    /// Reads the next unencrypted RAR5 block header, or `null` at archive EOF.
    private @Nullable BlockHeader readPlainRar5BlockHeader() throws IOException {
        byte[] crcBytes = new byte[Integer.BYTES];
        int first = source.read();
        if (first < 0) {
            return null;
        }
        crcBytes[0] = (byte) first;
        readFully(crcBytes, 1, crcBytes.length - 1, "Unexpected end of RAR header CRC32");
        long expectedCrc = littleEndianUInt32(crcBytes, 0);

        VintBytes headerSize = readSourceVint("RAR header size", 3);
        if (headerSize.value() > MAX_HEADER_SIZE) {
            throw new IOException("RAR header is too large");
        }
        readLimits.acceptMetadata(Integer.BYTES + headerSize.bytes().length + headerSize.value(), null);
        byte[] headerData = new byte[(int) headerSize.value()];
        readFully(headerData, 0, headerData.length, "Unexpected end of RAR header");

        return decodeRar5BlockHeader(expectedCrc, headerSize.bytes(), headerData);
    }

    /// Reads and decrypts the next AES-protected RAR5 block header.
    private @Nullable BlockHeader readEncryptedRar5BlockHeader() throws IOException {
        byte[] initializationVector = new byte[Rar5Crypto.BLOCK_SIZE];
        byte[] firstCiphertext = new byte[Rar5Crypto.BLOCK_SIZE];
        byte[] firstPlaintext = new byte[Rar5Crypto.BLOCK_SIZE];
        byte[] ciphertext = new byte[Rar5Crypto.BLOCK_SIZE];
        byte[] decryptedBlock = new byte[Rar5Crypto.BLOCK_SIZE];
        byte @Nullable [] plaintext = null;
        Rar5Crypto.@Nullable CbcDecryptor decryptor = null;
        try {
            int first = source.read();
            if (first < 0) {
                return null;
            }
            initializationVector[0] = (byte) first;
            readFully(
                    initializationVector,
                    1,
                    initializationVector.length - 1,
                    "Unexpected end of RAR5 encrypted header initialization vector"
            );

            readFully(
                    firstCiphertext,
                    0,
                    firstCiphertext.length,
                    "Unexpected end of RAR5 encrypted header"
            );
            byte[] headerKey = Objects.requireNonNull(archiveHeaderKey, "archiveHeaderKey");
            decryptor = Rar5Crypto.decryptor(headerKey, initializationVector);
            decryptor.decryptBlock(firstCiphertext, firstPlaintext);

            HeaderReader sizeReader = new HeaderReader(firstPlaintext, Integer.BYTES, firstPlaintext.length);
            long headerDataSize = sizeReader.readVint("RAR encrypted header size");
            int sizeLength = sizeReader.position() - Integer.BYTES;
            if (sizeLength > 3 || headerDataSize > MAX_HEADER_SIZE) {
                throw new IOException("RAR encrypted header is too large");
            }
            int plainHeaderSize = checkedAdd(
                    Integer.BYTES + sizeLength,
                    headerDataSize,
                    "RAR encrypted header size"
            );
            int encryptedHeaderSize = alignedHeaderSize(plainHeaderSize);
            readLimits.acceptMetadata((long) initializationVector.length + encryptedHeaderSize, null);
            plaintext = new byte[encryptedHeaderSize];
            System.arraycopy(firstPlaintext, 0, plaintext, 0, firstPlaintext.length);

            for (int offset = Rar5Crypto.BLOCK_SIZE; offset < encryptedHeaderSize; offset += Rar5Crypto.BLOCK_SIZE) {
                readFully(ciphertext, 0, ciphertext.length, "Unexpected end of RAR5 encrypted header");
                decryptor.decryptBlock(ciphertext, decryptedBlock);
                System.arraycopy(decryptedBlock, 0, plaintext, offset, decryptedBlock.length);
            }

            long expectedCrc = littleEndianUInt32(plaintext, 0);
            byte[] headerSizeBytes = Arrays.copyOfRange(
                    plaintext,
                    Integer.BYTES,
                    Integer.BYTES + sizeLength
            );
            byte[] headerData = Arrays.copyOfRange(
                    plaintext,
                    Integer.BYTES + sizeLength,
                    plainHeaderSize
            );
            return decodeRar5BlockHeader(expectedCrc, headerSizeBytes, headerData);
        } finally {
            if (decryptor != null) {
                decryptor.clear();
            }
            Rar5Crypto.clear(initializationVector);
            Rar5Crypto.clear(firstCiphertext);
            Rar5Crypto.clear(firstPlaintext);
            Rar5Crypto.clear(plaintext);
            Rar5Crypto.clear(ciphertext);
            Rar5Crypto.clear(decryptedBlock);
        }
    }

    /// Returns a 16-byte-aligned encrypted header size.
    private static int alignedHeaderSize(int plainHeaderSize) throws IOException {
        int remainder = plainHeaderSize % Rar5Crypto.BLOCK_SIZE;
        if (remainder == 0) {
            return plainHeaderSize;
        }
        return checkedAdd(
                plainHeaderSize,
                Rar5Crypto.BLOCK_SIZE - remainder,
                "RAR encrypted header size"
        );
    }

    /// Returns whether an end-of-archive block declares that another volume follows.
    private static boolean endHeaderHasNextVolume(BlockHeader block) throws IOException {
        if (block.version() == RAR_VERSION_4) {
            return (block.flags() & RAR4_END_FLAG_NEXT_VOLUME) != 0L;
        }

        HeaderReader reader = new HeaderReader(block.headerData(), block.fieldsOffset(), block.extraOffset());
        long flags = reader.readVint("RAR end of archive flags");
        if (reader.position() != block.extraOffset()) {
            throw new IOException("RAR end of archive header has trailing data");
        }
        return (flags & END_FLAG_NEXT_VOLUME) != 0L;
    }

    /// Validates and decodes one complete RAR5 block header.
    private BlockHeader decodeRar5BlockHeader(
            long expectedCrc,
            byte[] headerSizeBytes,
            byte[] headerData
    ) throws IOException {

        headerCrc32.reset();
        headerCrc32.update(headerSizeBytes, 0, headerSizeBytes.length);
        headerCrc32.update(headerData, 0, headerData.length);
        if (headerCrc32.getValue() != expectedCrc) {
            throw new IOException("Invalid RAR header CRC32");
        }

        HeaderReader reader = new HeaderReader(headerData, 0, headerData.length);
        int type = readIntVint(reader, "RAR header type");
        long flags = reader.readVint("RAR header flags");
        long extraSize = 0L;
        long dataSize = 0L;
        if ((flags & HEADER_FLAG_EXTRA_AREA) != 0) {
            extraSize = reader.readVint("RAR extra area size");
        }
        if ((flags & HEADER_FLAG_DATA_AREA) != 0) {
            dataSize = reader.readVint("RAR data area size");
        }
        if (extraSize > headerData.length - reader.position()) {
            throw new IOException("RAR extra area exceeds header size");
        }
        int extraOffset = headerData.length - (int) extraSize;
        if (reader.position() > extraOffset) {
            throw new IOException("RAR header fields exceed header size");
        }
        return new BlockHeader(RAR_VERSION_5, type, flags, dataSize, headerData, reader.position(), extraOffset);
    }

    /// Parses an archive encryption header, authenticates its password, and enables encrypted-header reads.
    private void enableEncryptedHeaders(BlockHeader block) throws IOException {
        if (block.version() != RAR_VERSION_5) {
            throw new IOException("RAR archive encryption headers are only valid in RAR5");
        }
        if (archiveHeaderKey != null) {
            throw new IOException("RAR5 archive contains duplicate encryption headers");
        }
        HeaderReader reader = new HeaderReader(block.headerData(), block.fieldsOffset(), block.extraOffset());
        long version = reader.readVint("RAR archive encryption version");
        if (version != RAR5_ENCRYPTION_VERSION) {
            throw new IOException("Unsupported RAR5 archive encryption version: " + version);
        }
        long flags = reader.readVint("RAR archive encryption flags");
        if ((flags & ~ENCRYPTION_FLAG_PASSWORD_CHECK) != 0L) {
            throw new IOException("Unsupported RAR5 archive encryption flags: 0x" + Long.toHexString(flags));
        }
        int kdfLog = reader.readUnsignedByte("RAR archive encryption KDF count");
        if (kdfLog > Rar5Crypto.MAX_KDF_LOG) {
            throw new IOException("Unsupported RAR5 KDF iteration exponent: " + kdfLog);
        }
        byte[] salt = reader.readBytes(Rar5Crypto.SALT_SIZE, "RAR archive encryption salt");
        byte @Nullable [] passwordCheck = null;
        if ((flags & ENCRYPTION_FLAG_PASSWORD_CHECK) != 0L) {
            byte[] candidate = reader.readBytes(
                    Rar5Crypto.PASSWORD_CHECK_SIZE,
                    "RAR archive encryption password check"
            );
            byte[] checksum = reader.readBytes(
                    Rar5Crypto.PASSWORD_CHECK_CHECKSUM_SIZE,
                    "RAR archive encryption password check checksum"
            );
            if (Rar5Crypto.hasValidPasswordCheckChecksum(candidate, checksum)) {
                passwordCheck = candidate;
            } else {
                Rar5Crypto.clear(candidate);
            }
            Rar5Crypto.clear(checksum);
        }
        if (reader.position() != block.extraOffset()) {
            throw new IOException("RAR archive encryption header has trailing data");
        }

        ArkivoPasswordProvider provider = passwordProvider;
        if (provider == null) {
            throw new IOException("RAR5 password provider is required for encrypted headers");
        }
        byte @Nullable [] password = provider.passwordForArchive();
        if (password == null) {
            throw new IOException("RAR5 password provider did not supply an archive password");
        }
        try (Rar5Crypto.DerivedKeys keys = Rar5Crypto.deriveKeys(password, salt, kdfLog)) {
            if (passwordCheck != null && !keys.matchesPasswordCheck(passwordCheck)) {
                throw new IOException("Incorrect RAR5 archive password");
            }
            archiveHeaderKey = keys.aesKey();
        } finally {
            Rar5Crypto.clear(password);
            Rar5Crypto.clear(salt);
            Rar5Crypto.clear(passwordCheck);
        }
    }

    /// Reads the next RAR4 block header, or `null` at archive EOF.
    private @Nullable BlockHeader readRar4BlockHeader() throws IOException {
        return rar3HeadersEncrypted ? readEncryptedRar4BlockHeader() : readPlainRar4BlockHeader();
    }

    /// Reads the next unencrypted RAR4 block header, or `null` at archive EOF.
    private @Nullable BlockHeader readPlainRar4BlockHeader() throws IOException {
        byte[] header = new byte[7];
        int first = source.read();
        if (first < 0) {
            return null;
        }
        header[0] = (byte) first;
        readFully(header, 1, header.length - 1, "Unexpected end of RAR4 header");

        int headerSize = littleEndianUInt16(header, 5);
        if (headerSize < header.length) {
            throw new IOException("RAR4 header is too small");
        }
        if (headerSize > MAX_HEADER_SIZE) {
            throw new IOException("RAR4 header is too large");
        }
        readLimits.acceptMetadata(headerSize, null);
        header = Arrays.copyOf(header, headerSize);
        readFully(header, 7, header.length - 7, "Unexpected end of RAR4 header");
        return decodeRar4BlockHeader(header);
    }

    /// Reads and decrypts the next independently salted RAR 3.x archive header.
    private @Nullable BlockHeader readEncryptedRar4BlockHeader() throws IOException {
        ArkivoPasswordProvider provider = passwordProvider;
        if (provider == null) {
            throw new IOException("RAR3 password provider is required for encrypted headers");
        }
        byte @Nullable [] password = provider.passwordForArchive();
        if (password == null) {
            throw new IOException("RAR3 password provider did not supply an archive password");
        }

        byte[] salt = new byte[Rar3Crypto.SALT_SIZE];
        byte[] firstCiphertext = new byte[Rar3Crypto.BLOCK_SIZE];
        byte[] firstPlaintext = new byte[Rar3Crypto.BLOCK_SIZE];
        byte[] ciphertext = new byte[Rar3Crypto.BLOCK_SIZE];
        byte[] decryptedBlock = new byte[Rar3Crypto.BLOCK_SIZE];
        byte @Nullable [] plaintext = null;
        byte @Nullable [] key = null;
        byte @Nullable [] initializationVector = null;
        Rar3Crypto.@Nullable CbcDecryptor decryptor = null;
        try {
            int first = source.read();
            if (first < 0) {
                return null;
            }
            salt[0] = (byte) first;
            readFully(salt, 1, salt.length - 1, "Unexpected end of RAR3 encrypted header salt");

            try (Rar3Crypto.DerivedKeys keys = Rar3Crypto.deriveKeys(password, salt)) {
                key = keys.key();
                initializationVector = keys.initializationVector();
            }
            decryptor = Rar3Crypto.decryptor(
                    Objects.requireNonNull(key, "key"),
                    Objects.requireNonNull(initializationVector, "initializationVector")
            );

            try {
                readFully(
                        firstCiphertext,
                        0,
                        firstCiphertext.length,
                        "Unexpected end of RAR3 encrypted header"
                );
                decryptor.decryptBlock(firstCiphertext, firstPlaintext);
                int headerSize = littleEndianUInt16(firstPlaintext, 5);
                if (headerSize < 7 || headerSize > MAX_HEADER_SIZE) {
                    throw new IOException("Invalid decrypted RAR4 header size");
                }

                int encryptedHeaderSize = alignedHeaderSize(headerSize);
                readLimits.acceptMetadata((long) salt.length + encryptedHeaderSize, null);
                plaintext = new byte[encryptedHeaderSize];
                System.arraycopy(firstPlaintext, 0, plaintext, 0, firstPlaintext.length);
                for (int offset = Rar3Crypto.BLOCK_SIZE;
                     offset < encryptedHeaderSize;
                     offset += Rar3Crypto.BLOCK_SIZE) {
                    readFully(ciphertext, 0, ciphertext.length, "Unexpected end of RAR3 encrypted header");
                    decryptor.decryptBlock(ciphertext, decryptedBlock);
                    System.arraycopy(decryptedBlock, 0, plaintext, offset, decryptedBlock.length);
                }

                byte[] header = Arrays.copyOf(plaintext, headerSize);
                try {
                    return decodeRar4BlockHeader(header);
                } finally {
                    Rar3Crypto.clear(header);
                }
            } catch (IOException exception) {
                throw new IOException("Incorrect RAR3 archive password or corrupt encrypted header", exception);
            }
        } finally {
            if (decryptor != null) {
                decryptor.clear();
            }
            Rar3Crypto.clear(password);
            Rar3Crypto.clear(salt);
            Rar3Crypto.clear(key);
            Rar3Crypto.clear(initializationVector);
            Rar3Crypto.clear(firstCiphertext);
            Rar3Crypto.clear(firstPlaintext);
            Rar3Crypto.clear(ciphertext);
            Rar3Crypto.clear(decryptedBlock);
            Rar3Crypto.clear(plaintext);
        }
    }

    /// Validates and decodes one complete plaintext RAR4 block header.
    private BlockHeader decodeRar4BlockHeader(byte[] header) throws IOException {
        if (header.length < 7 || littleEndianUInt16(header, 5) != header.length) {
            throw new IOException("Invalid RAR4 header size");
        }

        byte[] headerData = Arrays.copyOfRange(header, Short.BYTES, header.length);

        headerCrc32.reset();
        int rawType = Byte.toUnsignedInt(headerData[0]);
        long flags = littleEndianUInt16(headerData, 1);
        int crcDataLength = rar4HeaderCrcDataLength(headerData, rawType, flags);
        headerCrc32.update(headerData, 0, crcDataLength);
        if ((headerCrc32.getValue() & 0xffffL) != littleEndianUInt16(header, 0)) {
            throw new IOException("Invalid RAR4 header CRC16");
        }

        int type = rar4HeaderType(rawType);
        if (type == HEADER_TYPE_MAIN && (flags & RAR4_MAIN_FLAG_PASSWORD) != 0) {
            rar3HeadersEncrypted = true;
        }
        if (type == HEADER_TYPE_MAIN && (flags & RAR4_MAIN_FLAG_SOLID) != 0) {
            rar4ArchiveSolid = true;
        }

        long dataSize = 0L;
        if (type == HEADER_TYPE_FILE) {
            dataSize = rar4PackedSize(headerData, flags);
        } else if ((flags & RAR4_HEADER_FLAG_DATA_AREA) != 0) {
            if (headerData.length < 9) {
                throw new IOException("RAR4 data area size is missing");
            }
            dataSize = littleEndianUInt32(headerData, 5);
        }
        return new BlockHeader(RAR_VERSION_4, type, flags, dataSize, headerData, 5, crcDataLength);
    }

    /// Returns the RAR4 header-data prefix covered by the stored CRC16.
    private static int rar4HeaderCrcDataLength(byte[] headerData, int rawType, long flags) throws IOException {
        if (rawType == RAR4_HEADER_TYPE_MAIN && (flags & RAR4_MAIN_FLAG_COMMENT) != 0L) {
            int length = RAR4_MAIN_HEADER_DATA_SIZE;
            if ((flags & RAR4_MAIN_FLAG_ENCRYPTION_VERSION) != 0L) {
                length++;
            }
            if (length > headerData.length) {
                throw new IOException("RAR4 main header fields exceed the header size");
            }
            return length;
        }
        if (rawType == RAR4_HEADER_TYPE_FILE && (flags & RAR4_FILE_FLAG_COMMENT) != 0L) {
            return rar4FileMetadataEnd(headerData, flags);
        }
        return headerData.length;
    }

    /// Returns the end offset of RAR4 file metadata before an embedded old-style comment.
    private static int rar4FileMetadataEnd(byte[] headerData, long flags) throws IOException {
        if (headerData.length < RAR4_FILE_HEADER_DATA_SIZE) {
            throw new IOException("RAR4 file header is too small");
        }
        int position = RAR4_FILE_HEADER_DATA_SIZE;
        if ((flags & RAR4_FILE_FLAG_LARGE) != 0L) {
            position = checkedRar4HeaderEnd(position, Long.BYTES, headerData.length, "large file size fields");
        }
        int nameLength = littleEndianUInt16(headerData, 24);
        position = checkedRar4HeaderEnd(position, nameLength, headerData.length, "entry name");
        if ((flags & RAR4_FILE_FLAG_SALT) != 0L) {
            position = checkedRar4HeaderEnd(position, Rar3Crypto.SALT_SIZE, headerData.length, "encryption salt");
        }
        if ((flags & RAR4_FILE_FLAG_EXTENDED_TIME) != 0L) {
            int flagsEnd = checkedRar4HeaderEnd(position, Short.BYTES, headerData.length, "extended time flags");
            int timeFlags = littleEndianUInt16(headerData, position);
            position = flagsEnd;
            for (int index = 0; index < 4; index++) {
                int mode = (timeFlags >>> ((3 - index) * 4)) & 0x0f;
                if ((mode & 0x08) == 0) {
                    continue;
                }
                if (index != 0) {
                    position = checkedRar4HeaderEnd(
                            position,
                            Integer.BYTES,
                            headerData.length,
                            "extended DOS time"
                    );
                }
                position = checkedRar4HeaderEnd(
                        position,
                        mode & 0x03,
                        headerData.length,
                        "extended time precision"
                );
            }
        }
        return position;
    }

    /// Adds one variable RAR4 field length and validates that it remains inside the header.
    private static int checkedRar4HeaderEnd(int position, int length, int limit, String description)
            throws IOException {
        int end = checkedAdd(position, length, "RAR4 " + description + " size");
        if (end > limit) {
            throw new IOException("RAR4 " + description + " exceeds the header size");
        }
        return end;
    }

    /// Maps a raw RAR4 header type to the reader's normalized block type.
    private static int rar4HeaderType(int rawType) {
        return switch (rawType) {
            case RAR4_HEADER_TYPE_MAIN -> HEADER_TYPE_MAIN;
            case RAR4_HEADER_TYPE_FILE -> HEADER_TYPE_FILE;
            case RAR4_HEADER_TYPE_END -> HEADER_TYPE_END;
            default -> HEADER_TYPE_SERVICE;
        };
    }

    /// Reads the packed data size stored in a RAR4 file header.
    private static long rar4PackedSize(byte[] headerData, long flags) throws IOException {
        if (headerData.length < 30) {
            throw new IOException("RAR4 file header is too small");
        }
        long lowPackedSize = littleEndianUInt32(headerData, 5);
        if ((flags & RAR4_FILE_FLAG_LARGE) == 0) {
            return lowPackedSize;
        }
        if (headerData.length < 38) {
            throw new IOException("RAR4 large file size fields are missing");
        }
        return combineUInt64(littleEndianUInt32(headerData, 30), lowPackedSize, "RAR4 packed size");
    }


    /// Records archive-level compression state from one main header.
    private void readMainHeaderMetadata(BlockHeader block) throws IOException {
        if (block.version() != RAR_VERSION_5) {
            return;
        }
        HeaderReader reader = new HeaderReader(block.headerData(), block.fieldsOffset(), block.extraOffset());
        long archiveFlags = reader.readVint("RAR5 main archive flags");
        rar5ArchiveSolid = (archiveFlags & RAR5_MAIN_FLAG_SOLID) != 0L;
    }
    /// Parses a RAR file header block.
    private static ParsedFileHeader parseFileHeader(BlockHeader block) throws IOException {
        if (block.version() == RAR_VERSION_4) {
            return parseRar4FileHeader(block);
        }
        return parseRar5FileHeader(block);
    }

    /// Parses a RAR5 file header block.
    private static ParsedFileHeader parseRar5FileHeader(BlockHeader block) throws IOException {
        HeaderReader reader = new HeaderReader(block.headerData(), block.fieldsOffset(), block.extraOffset());
        long fileFlags = reader.readVint("RAR file flags");
        long rawUnpackedSize = reader.readVint("RAR unpacked size");
        long fileAttributes = reader.readVint("RAR file attributes");
        FileTime lastModifiedTime = MISSING_TIME;
        if ((fileFlags & FILE_FLAG_UNIX_TIME) != 0) {
            lastModifiedTime = fileTimeFromEpochSecond(reader.readUInt32("RAR modification time"));
        }
        long dataCrc32 = RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE;
        if ((fileFlags & FILE_FLAG_DATA_CRC) != 0) {
            dataCrc32 = reader.readUInt32("RAR data CRC32");
        }
        long compressionInformation = reader.readVint("RAR compression information");
        int compressionMethod = (int) ((compressionInformation & COMPRESSION_METHOD_MASK) >>> COMPRESSION_METHOD_SHIFT);
        int algorithmVersion = (int) (compressionInformation & COMPRESSION_ALGORITHM_MASK);
        boolean solid = (compressionInformation & COMPRESSION_FLAG_SOLID) != 0L;
        long dictionaryPowerMask = algorithmVersion == 0
                ? COMPRESSION_DICTIONARY_POWER_V5_MASK
                : COMPRESSION_DICTIONARY_POWER_V7_MASK;
        int dictionaryPower = (int) (compressionInformation >>> COMPRESSION_DICTIONARY_POWER_SHIFT
                & dictionaryPowerMask);
        int dictionaryFraction = algorithmVersion == 0
                ? 0
                : (int) (compressionInformation >>> COMPRESSION_DICTIONARY_FRACTION_SHIFT
                & COMPRESSION_DICTIONARY_FRACTION_MASK);
        boolean version7 = algorithmVersion == 1
                && (compressionInformation & COMPRESSION_FLAG_RAR5_COMPATIBLE) == 0L;
        Rar5CompressionInfo compressionInfo = new Rar5CompressionInfo(
                algorithmVersion,
                dictionaryPower,
                dictionaryFraction,
                version7,
                solid
        );
        int hostOs = readIntVint(reader, "RAR host OS");
        int nameLength = readIntVint(reader, "RAR name length");
        String path = validatePath(reader.readString(nameLength, "RAR entry name"));
        ExtraMetadata extraMetadata = parseExtraArea(block.headerData(), block.extraOffset(), block.headerData().length);
        if (extraMetadata.lastModifiedTime != null) {
            lastModifiedTime = extraMetadata.lastModifiedTime;
        }
        FileTime creationTime = extraMetadata.creationTime != null ? extraMetadata.creationTime : lastModifiedTime;
        FileTime lastAccessTime = extraMetadata.lastAccessTime != null ? extraMetadata.lastAccessTime : lastModifiedTime;

        boolean directory = (fileFlags & FILE_FLAG_DIRECTORY) != 0;
        boolean symbolicLink = extraMetadata.redirectionType == RarArkivoEntryAttributes.REDIRECTION_TYPE_UNIX_SYMLINK
                || extraMetadata.redirectionType == RarArkivoEntryAttributes.REDIRECTION_TYPE_WINDOWS_SYMLINK;
        boolean regularRedirection = extraMetadata.redirectionType == RarArkivoEntryAttributes.REDIRECTION_TYPE_HARD_LINK
                || extraMetadata.redirectionType == RarArkivoEntryAttributes.REDIRECTION_TYPE_FILE_COPY;
        boolean other = extraMetadata.redirectionType != RarArkivoEntryAttributes.NO_REDIRECTION_TYPE
                && !symbolicLink
                && !regularRedirection;
        long unpackedSize = (fileFlags & FILE_FLAG_UNKNOWN_UNPACKED_SIZE) != 0
                ? RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE
                : rawUnpackedSize;
        @Nullable Rar5EncryptionInfo encryptionInfo = extraMetadata.encryptionInfo;
        boolean splitFilePart = (block.flags() & (HEADER_FLAG_CONTINUE_PREVIOUS | HEADER_FLAG_CONTINUE_NEXT)) != 0;
        if (!directory
                && extraMetadata.redirectionType == RarArkivoEntryAttributes.NO_REDIRECTION_TYPE
                && compressionMethod == 0
                && !splitFilePart
                && unpackedSize != RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE) {
            long expectedStoredSize = encryptionInfo != null
                    ? alignedEncryptedSize(unpackedSize)
                    : unpackedSize;
            if (block.dataSize() != expectedStoredSize) {
                throw new IOException("RAR stored file packed and unpacked sizes differ");
            }
        }

        return new ParsedFileHeader(
                new RarEntryAttributes(
                        path,
                        directory,
                        symbolicLink,
                        other,
                        extraMetadata.linkName,
                        extraMetadata.redirectionType,
                        extraMetadata.redirectionFlags,
                        extraMetadata.redirectionTarget,
                        extraMetadata.userName,
                        extraMetadata.groupName,
                        extraMetadata.userId,
                        extraMetadata.groupId,
                        hostOs,
                        fileAttributes,
                        compressionMethod,
                        block.dataSize(),
                        unpackedSize,
                        dataCrc32,
                        extraMetadata.blake2spHash,
                        encryptionInfo != null,
                        (block.flags() & HEADER_FLAG_CONTINUE_PREVIOUS) != 0,
                        (block.flags() & HEADER_FLAG_CONTINUE_NEXT) != 0,
                        lastModifiedTime,
                        creationTime,
                        lastAccessTime
                ),
                encryptionInfo,
                compressionInfo
        );
    }

    /// Returns the 16-byte-block-aligned physical size for an encrypted stored file.
    private static long alignedEncryptedSize(long unpackedSize) throws IOException {
        long remainder = unpackedSize % RarCbcDecryptor.BLOCK_SIZE;
        if (remainder == 0L) {
            return unpackedSize;
        }
        long padding = RarCbcDecryptor.BLOCK_SIZE - remainder;
        if (unpackedSize > Long.MAX_VALUE - padding) {
            throw new IOException("RAR encrypted file size is too large");
        }
        return unpackedSize + padding;
    }

    /// Parses a RAR4 file header block.
    private static ParsedFileHeader parseRar4FileHeader(BlockHeader block) throws IOException {
        HeaderReader reader = new HeaderReader(block.headerData(), block.fieldsOffset(), block.extraOffset());
        long lowPackedSize = reader.readUInt32("RAR4 packed size");
        long lowUnpackedSize = reader.readUInt32("RAR4 unpacked size");
        int rawHostOs = reader.readUnsignedByte("RAR4 host OS");
        long dataCrc32 = reader.readUInt32("RAR4 data CRC32");
        FileTime lastModifiedTime = fileTimeFromDosTime((int) reader.readUInt32("RAR4 modification time"));
        int extractionVersion = reader.readUnsignedByte("RAR4 extraction version");
        int rawMethod = reader.readUnsignedByte("RAR4 compression method");
        int nameLength = reader.readUInt16("RAR4 name length");
        long fileAttributes = reader.readUInt32("RAR4 file attributes");

        long packedSize = lowPackedSize;
        long unpackedSize = lowUnpackedSize;
        if ((block.flags() & RAR4_FILE_FLAG_LARGE) != 0) {
            packedSize = combineUInt64(reader.readUInt32("RAR4 high packed size"), lowPackedSize, "RAR4 packed size");
            unpackedSize = combineUInt64(
                    reader.readUInt32("RAR4 high unpacked size"),
                    lowUnpackedSize,
                    "RAR4 unpacked size"
            );
        }
        if (packedSize != block.dataSize()) {
            throw new IOException("RAR4 file header packed size is inconsistent");
        }

        byte[] nameBytes = reader.readBytes(nameLength, "RAR4 entry name");
        String path = validatePath(decodeRar4Name(nameBytes, (block.flags() & RAR4_FILE_FLAG_UNICODE) != 0));
        byte @Nullable [] salt = (block.flags() & RAR4_FILE_FLAG_SALT) != 0
                ? reader.readBytes(Rar3Crypto.SALT_SIZE, "RAR4 file encryption salt")
                : null;
        int hostOs = rar4HostOs(rawHostOs);
        int compressionMethod = rar4CompressionMethod(rawMethod);
        boolean directory = (block.flags() & RAR4_FILE_DICTIONARY_MASK) == RAR4_FILE_IS_DIRECTORY
                || rar4FileAttributesMarkDirectory(rawHostOs, fileAttributes);
        boolean encrypted = (block.flags() & RAR4_FILE_FLAG_PASSWORD) != 0;
        @Nullable Rar3EncryptionInfo encryptionInfo = encrypted
                ? new Rar3EncryptionInfo(extractionVersion, salt)
                : null;
        boolean splitFilePart =
                (block.flags() & (RAR4_FILE_FLAG_CONTINUE_PREVIOUS | RAR4_FILE_FLAG_CONTINUE_NEXT)) != 0;
        if (encrypted && usesBlockRarEncryption(extractionVersion) && !splitFilePart
                && packedSize % RarCbcDecryptor.BLOCK_SIZE != 0L) {
            throw new IOException("RAR4 encrypted file data is not 16-byte block aligned");
        }
        if (!directory && compressionMethod == 0 && !splitFilePart) {
            long expectedPackedSize = encrypted && usesBlockRarEncryption(extractionVersion)
                    ? alignedEncryptedSize(unpackedSize)
                    : unpackedSize;
            if (packedSize != expectedPackedSize) {
                throw new IOException("RAR4 stored file packed and unpacked sizes differ");
            }
        }

        return new ParsedFileHeader(
                new RarEntryAttributes(
                        path,
                        directory,
                        false,
                        false,
                        null,
                        RarArkivoEntryAttributes.NO_REDIRECTION_TYPE,
                        0L,
                        null,
                        null,
                        null,
                        RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE,
                        RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE,
                        hostOs,
                        fileAttributes,
                        compressionMethod,
                        packedSize,
                        unpackedSize,
                        dataCrc32,
                        null,
                        encrypted,
                        (block.flags() & RAR4_FILE_FLAG_CONTINUE_PREVIOUS) != 0,
                        (block.flags() & RAR4_FILE_FLAG_CONTINUE_NEXT) != 0,
                        lastModifiedTime,
                        lastModifiedTime,
                        lastModifiedTime
                ),
                encryptionInfo,
                new Rar4CompressionInfo(
                        extractionVersion,
                        (block.flags() & RAR4_FILE_FLAG_SOLID) != 0L
                )
        );
    }

    /// Returns whether a RAR4 extraction version selects the RAR 3.x AES-128 scheme.
    private static boolean isRar3AesVersion(int extractionVersion) {
        return extractionVersion != 13
                && extractionVersion != 15
                && extractionVersion != 20
                && extractionVersion != 26;
    }

    /// Returns whether a RAR4 extraction version selects any implemented file-data cipher.
    private static boolean isSupportedRar4EncryptionVersion(int extractionVersion) {
        return RarLegacyCrypto.supports(extractionVersion) || isRar3AesVersion(extractionVersion);
    }

    /// Returns whether an encrypted RAR4 body is padded to a complete 16-byte block.
    private static boolean usesBlockRarEncryption(int extractionVersion) {
        return RarLegacyCrypto.usesBlockCipher(extractionVersion) || isRar3AesVersion(extractionVersion);
    }

    /// Combines two little-endian unsigned 32-bit halves into a positive signed `long`.
    private static long combineUInt64(long high, long low, String description) throws IOException {
        if ((high & 0x8000_0000L) != 0) {
            throw new IOException(description + " is too large");
        }
        return (high << 32) | low;
    }

    /// Decodes a RAR4 name field.
    private static String decodeRar4Name(byte[] nameBytes, boolean unicodeName) throws IOException {
        if (!unicodeName) {
            return new String(nameBytes, StandardCharsets.UTF_8);
        }

        int separatorIndex = -1;
        for (int index = 0; index < nameBytes.length; index++) {
            if (nameBytes[index] == 0) {
                separatorIndex = index;
                break;
            }
        }
        if (separatorIndex < 0) {
            throw new IOException("Invalid RAR4 Unicode name");
        }
        if (separatorIndex + 1 == nameBytes.length) {
            return new String(nameBytes, 0, separatorIndex, StandardCharsets.UTF_8);
        }

        int highByte = Byte.toUnsignedInt(nameBytes[separatorIndex + 1]);
        int encodedOffset = separatorIndex + 2;
        int fallbackOffset = 0;
        int flags = 0;
        int flagBits = 0;
        StringBuilder builder = new StringBuilder(separatorIndex);
        while (encodedOffset < nameBytes.length) {
            if (flagBits == 0) {
                flags = Byte.toUnsignedInt(nameBytes[encodedOffset++]);
                flagBits = 8;
                if (encodedOffset >= nameBytes.length) {
                    throw new IOException("Invalid RAR4 Unicode name");
                }
            }

            int flag = flags >>> 6;
            flags = (flags << 2) & 0xff;
            flagBits -= 2;
            switch (flag) {
                case 0 -> {
                    builder.append((char) Byte.toUnsignedInt(nameBytes[encodedOffset++]));
                    fallbackOffset++;
                }
                case 1 -> {
                    int lowByte = Byte.toUnsignedInt(nameBytes[encodedOffset++]);
                    builder.append((char) (lowByte | (highByte << 8)));
                    fallbackOffset++;
                }
                case 2 -> {
                    if (encodedOffset + 1 >= nameBytes.length) {
                        throw new IOException("Invalid RAR4 Unicode name");
                    }
                    int lowByte = Byte.toUnsignedInt(nameBytes[encodedOffset++]);
                    int nextHighByte = Byte.toUnsignedInt(nameBytes[encodedOffset++]);
                    builder.append((char) (lowByte | (nextHighByte << 8)));
                    fallbackOffset++;
                }
                case 3 -> {
                    int length = Byte.toUnsignedInt(nameBytes[encodedOffset++]);
                    if ((length & 0x80) == 0) {
                        int copyLength = length + 2;
                        if (fallbackOffset + copyLength > separatorIndex) {
                            throw new IOException("Invalid RAR4 Unicode name");
                        }
                        for (int index = 0; index < copyLength; index++) {
                            builder.append((char) Byte.toUnsignedInt(nameBytes[fallbackOffset++]));
                        }
                    } else {
                        if (encodedOffset >= nameBytes.length) {
                            throw new IOException("Invalid RAR4 Unicode name");
                        }
                        int correction = Byte.toUnsignedInt(nameBytes[encodedOffset++]);
                        int copyLength = (length & 0x7f) + 2;
                        if (fallbackOffset + copyLength > separatorIndex) {
                            throw new IOException("Invalid RAR4 Unicode name");
                        }
                        for (int index = 0; index < copyLength; index++) {
                            int correctedByte = Byte.toUnsignedInt(nameBytes[fallbackOffset++]) + correction;
                            builder.append((char) ((correctedByte & 0xff) | (highByte << 8)));
                        }
                    }
                }
                default -> throw new AssertionError(flag);
            }
        }
        return builder.toString();
    }

    /// Normalizes a raw RAR4 host OS value to the public host OS value set.
    private static int rar4HostOs(int rawHostOs) {
        return switch (rawHostOs) {
            case RAR4_HOST_OS_MSDOS, RAR4_HOST_OS_OS2, RAR4_HOST_OS_WINDOWS ->
                    RarArkivoEntryAttributes.HOST_OS_WINDOWS;
            case RAR4_HOST_OS_UNIX -> RarArkivoEntryAttributes.HOST_OS_UNIX;
            default -> rawHostOs;
        };
    }

    /// Returns the normalized compression method value for a raw RAR4 method byte.
    private static int rar4CompressionMethod(int rawMethod) {
        if (rawMethod == RAR4_METHOD_STORE) {
            return 0;
        }
        if (rawMethod >= RAR4_METHOD_FASTEST && rawMethod <= RAR4_METHOD_BEST) {
            return rawMethod - RAR4_METHOD_STORE;
        }
        return rawMethod;
    }

    /// Returns whether a normalized RAR method selects a supported decompression engine.
    private static boolean isSupportedCompressionMethod(int compressionMethod) {
        return compressionMethod >= RAR4_METHOD_FASTEST - RAR4_METHOD_STORE
                && compressionMethod <= RAR4_METHOD_BEST - RAR4_METHOD_STORE;
    }

    /// Returns whether RAR4 host-specific attributes mark a directory entry.
    private static boolean rar4FileAttributesMarkDirectory(int rawHostOs, long fileAttributes) {
        return switch (rawHostOs) {
            case RAR4_HOST_OS_MSDOS, RAR4_HOST_OS_OS2, RAR4_HOST_OS_WINDOWS -> (fileAttributes & 0x10L) != 0;
            case RAR4_HOST_OS_UNIX -> (fileAttributes & 0170000L) == 0040000L;
            default -> false;
        };
    }

    /// Returns whether RAR4 Unix file attributes describe a symbolic link.
    private static boolean isRar4UnixSymbolicLink(RarEntryAttributes attributes) {
        return attributes.hostOs() == RarArkivoEntryAttributes.HOST_OS_UNIX
                && (attributes.fileAttributes() & 0170000L) == 0120000L;
    }

    /// Decodes a RAR4 Unix symbolic-link target stored as the member body.
    private RarEntryAttributes readRar4UnixSymbolicLink(RarEntryAttributes attributes) throws IOException {
        long unpackedSize = attributes.unpackedSize();
        if (unpackedSize <= 0L || unpackedSize > MAXIMUM_RAR4_SYMBOLIC_LINK_TARGET_SIZE) {
            throw new IOException("RAR4 symbolic link target size is out of range");
        }

        byte[] targetBytes;
        try (InputStream input = openInputStream()) {
            targetBytes = input.readNBytes(Math.toIntExact(unpackedSize) + 1);
        }
        currentBodyOpened = false;
        if (targetBytes.length != unpackedSize) {
            throw new IOException("RAR4 symbolic link target size does not match its header");
        }
        for (byte value : targetBytes) {
            if (value == 0) {
                throw new IOException("RAR4 symbolic link target contains a null character");
            }
        }
        String target = new String(targetBytes, StandardCharsets.UTF_8);
        if (target.isEmpty()) {
            throw new IOException("RAR4 symbolic link target must not be empty");
        }

        return new RarEntryAttributes(
                attributes.path(),
                false,
                true,
                false,
                target,
                RarArkivoEntryAttributes.REDIRECTION_TYPE_UNIX_SYMLINK,
                0L,
                target,
                attributes.userName(),
                attributes.groupName(),
                attributes.userId(),
                attributes.groupId(),
                attributes.hostOs(),
                attributes.fileAttributes(),
                attributes.compressionMethod(),
                attributes.packedSize(),
                attributes.unpackedSize(),
                attributes.dataCrc32(),
                attributes.blake2spHash(),
                attributes.isEncrypted(),
                attributes.continuesFromPreviousVolume(),
                attributes.continuesInNextVolume(),
                attributes.lastModifiedTime(),
                attributes.creationTime(),
                attributes.lastAccessTime()
        );
    }

    /// Parses RAR file extra area records used by this reader.
    private static ExtraMetadata parseExtraArea(byte[] headerData, int offset, int limit) throws IOException {
        ExtraMetadata metadata = new ExtraMetadata();
        HeaderReader reader = new HeaderReader(headerData, offset, limit);
        while (reader.hasRemaining()) {
            long recordSize = reader.readVint("RAR extra record size");
            if (recordSize <= 0 || recordSize > reader.remaining()) {
                throw new IOException("Invalid RAR extra record boundary");
            }
            int recordEnd = checkedAdd(reader.position(), recordSize, "RAR extra record size");
            int type = readIntVint(reader, "RAR extra record type");
            switch (type) {
                case EXTRA_TYPE_FILE_ENCRYPTION -> parseFileEncryptionRecord(reader, recordEnd, metadata);
                case EXTRA_TYPE_FILE_HASH -> parseFileHashRecord(reader, recordEnd, metadata);
                case EXTRA_TYPE_FILE_TIME -> parseFileTimeRecord(reader, recordEnd, metadata);
                case EXTRA_TYPE_REDIRECTION -> parseRedirectionRecord(reader, recordEnd, metadata);
                case EXTRA_TYPE_UNIX_OWNER -> parseUnixOwnerRecord(reader, recordEnd, metadata);
                default -> {
                }
            }
            reader.position(recordEnd);
        }
        return metadata;
    }

    /// Parses and validates one RAR5 file encryption extra record.
    private static void parseFileEncryptionRecord(
            HeaderReader reader,
            int recordEnd,
            ExtraMetadata metadata
    ) throws IOException {
        if (metadata.encryptionInfo != null) {
            throw new IOException("RAR file header contains duplicate encryption records");
        }
        long version = reader.readVint("RAR file encryption version");
        if (version != RAR5_ENCRYPTION_VERSION) {
            throw new IOException("Unsupported RAR5 file encryption version: " + version);
        }
        long flags = reader.readVint("RAR file encryption flags");
        if ((flags & ~ENCRYPTION_FLAG_MASK) != 0L) {
            throw new IOException("Unsupported RAR5 file encryption flags: 0x" + Long.toHexString(flags));
        }
        int kdfLog = reader.readUnsignedByte("RAR file encryption KDF count");
        if (kdfLog > Rar5Crypto.MAX_KDF_LOG) {
            throw new IOException("Unsupported RAR5 KDF iteration exponent: " + kdfLog);
        }
        byte[] salt = reader.readBytes(Rar5Crypto.SALT_SIZE, "RAR file encryption salt");
        byte[] initializationVector = reader.readBytes(
                Rar5Crypto.BLOCK_SIZE,
                "RAR file encryption initialization vector"
        );
        byte @Nullable [] passwordCheck = null;
        if ((flags & ENCRYPTION_FLAG_PASSWORD_CHECK) != 0L) {
            byte[] candidate = reader.readBytes(
                    Rar5Crypto.PASSWORD_CHECK_SIZE,
                    "RAR file encryption password check"
            );
            byte[] checksum = reader.readBytes(
                    Rar5Crypto.PASSWORD_CHECK_CHECKSUM_SIZE,
                    "RAR file encryption password check checksum"
            );
            if (Rar5Crypto.hasValidPasswordCheckChecksum(candidate, checksum)) {
                passwordCheck = candidate;
            } else {
                Rar5Crypto.clear(candidate);
            }
            Rar5Crypto.clear(checksum);
        }
        if (reader.position() != recordEnd) {
            throw new IOException("RAR file encryption record has trailing data");
        }
        metadata.encryptionInfo = new Rar5EncryptionInfo(
                kdfLog,
                (flags & ENCRYPTION_FLAG_TWEAKED_CHECKSUMS) != 0L,
                salt,
                initializationVector,
                passwordCheck
        );
        Rar5Crypto.clear(salt);
        Rar5Crypto.clear(initializationVector);
        Rar5Crypto.clear(passwordCheck);
    }

    /// Parses a RAR file hash extra record.
    private static void parseFileHashRecord(HeaderReader reader, int recordEnd, ExtraMetadata metadata)
            throws IOException {
        long hashType = reader.readVint("RAR file hash type");
        if (hashType != HASH_TYPE_BLAKE2SP) {
            return;
        }
        if (recordEnd - reader.position() != BLAKE2SP_HASH_SIZE) {
            throw new IOException("RAR BLAKE2sp hash has invalid size");
        }
        metadata.blake2spHash = reader.readBytes(BLAKE2SP_HASH_SIZE, "RAR BLAKE2sp hash");
    }

    /// Parses a RAR file time extra record.
    private static void parseFileTimeRecord(HeaderReader reader, int recordEnd, ExtraMetadata metadata)
            throws IOException {
        long flags = reader.readVint("RAR file time flags");
        boolean unixTime = (flags & FILE_TIME_UNIX) != 0;
        boolean unixNanos = unixTime && (flags & FILE_TIME_UNIX_NANOS) != 0;

        @Nullable FileTime modifiedTime = (flags & FILE_TIME_MODIFIED) != 0
                ? readFileTimeValue(reader, recordEnd, unixTime, "modification time")
                : null;
        @Nullable FileTime creationTime = (flags & FILE_TIME_CREATED) != 0
                ? readFileTimeValue(reader, recordEnd, unixTime, "creation time")
                : null;
        @Nullable FileTime accessTime = (flags & FILE_TIME_ACCESSED) != 0
                ? readFileTimeValue(reader, recordEnd, unixTime, "last access time")
                : null;

        if (unixNanos) {
            if (modifiedTime != null) {
                modifiedTime = readFileTimeNanoseconds(reader, recordEnd, modifiedTime, "modification time");
            }
            if (creationTime != null) {
                creationTime = readFileTimeNanoseconds(reader, recordEnd, creationTime, "creation time");
            }
            if (accessTime != null) {
                accessTime = readFileTimeNanoseconds(reader, recordEnd, accessTime, "last access time");
            }
        }
        if (modifiedTime != null) {
            metadata.lastModifiedTime = modifiedTime;
        }
        if (creationTime != null) {
            metadata.creationTime = creationTime;
        }
        if (accessTime != null) {
            metadata.lastAccessTime = accessTime;
        }
    }

    /// Reads one RAR file time base value without optional Unix nanoseconds.
    private static FileTime readFileTimeValue(
            HeaderReader reader,
            int recordEnd,
            boolean unixTime,
            String description
    ) throws IOException {
        int encodedSize = unixTime ? Integer.BYTES : Long.BYTES;
        if (encodedSize > recordEnd - reader.position()) {
            throw new EOFException("Unexpected end of RAR " + description);
        }
        if (unixTime) {
            long seconds = reader.readUInt32("RAR " + description);
            return fileTimeFromEpochSecond(seconds, 0L, description);
        }
        long windowsFileTime = reader.readUInt64("RAR " + description);
        return fileTimeFromWindowsFileTime(windowsFileTime, description);
    }

    /// Reads and applies the separately stored RAR Unix nanosecond value.
    private static FileTime readFileTimeNanoseconds(
            HeaderReader reader,
            int recordEnd,
            FileTime baseTime,
            String description
    ) throws IOException {
        if (Integer.BYTES > recordEnd - reader.position()) {
            throw new EOFException("Unexpected end of RAR " + description + " nanoseconds");
        }
        long nanoseconds = reader.readUInt32("RAR " + description + " nanoseconds") & 0x3fff_ffffL;
        if (nanoseconds >= 1_000_000_000L) {
            return baseTime;
        }
        return fileTimeFromEpochSecond(baseTime.toInstant().getEpochSecond(), nanoseconds, description);
    }

    /// Parses a RAR file system redirection extra record.
    private static void parseRedirectionRecord(HeaderReader reader, int recordEnd, ExtraMetadata metadata)
            throws IOException {
        int redirectionType = readIntVint(reader, "RAR redirection type");
        long redirectionFlags = reader.readVint("RAR redirection flags");
        int nameLength = readIntVint(reader, "RAR redirection name length");
        if (nameLength > recordEnd - reader.position()) {
            throw new IOException("RAR redirection name exceeds record size");
        }
        String target = reader.readString(nameLength, "RAR redirection name");
        metadata.redirectionType = redirectionType;
        metadata.redirectionFlags = redirectionFlags;
        metadata.redirectionTarget = target;
        if (redirectionType == RarArkivoEntryAttributes.REDIRECTION_TYPE_UNIX_SYMLINK
                || redirectionType == RarArkivoEntryAttributes.REDIRECTION_TYPE_WINDOWS_SYMLINK) {
            metadata.linkName = target;
        }
    }

    /// Parses a RAR Unix owner extra record.
    private static void parseUnixOwnerRecord(HeaderReader reader, int recordEnd, ExtraMetadata metadata)
            throws IOException {
        long flags = reader.readVint("RAR Unix owner flags");
        if ((flags & UNIX_OWNER_USER_NAME) != 0) {
            metadata.userName = readRecordString(reader, recordEnd, "RAR Unix owner user name");
        }
        if ((flags & UNIX_OWNER_GROUP_NAME) != 0) {
            metadata.groupName = readRecordString(reader, recordEnd, "RAR Unix owner group name");
        }
        if ((flags & UNIX_OWNER_USER_ID) != 0) {
            metadata.userId = reader.readVint("RAR Unix owner user ID");
        }
        if ((flags & UNIX_OWNER_GROUP_ID) != 0) {
            metadata.groupId = reader.readVint("RAR Unix owner group ID");
        }
    }

    /// Reads a length-prefixed string from one extra record.
    private static String readRecordString(HeaderReader reader, int recordEnd, String description) throws IOException {
        int length = readIntVint(reader, description + " length");
        if (length > recordEnd - reader.position()) {
            throw new IOException(description + " exceeds record size");
        }
        return reader.readString(length, description);
    }

    /// Validates a decoded RAR entry path.
    private static String validatePath(String path) throws IOException {
        if (path.startsWith("/") || path.startsWith("\\")
                || path.length() >= 2 && path.charAt(1) == ':') {
            throw new IOException("RAR entry path must be relative");
        }
        boolean hasName = false;
        int start = 0;
        while (start <= path.length()) {
            int end = nextPathSeparator(path, start);
            String name = path.substring(start, end);
            if (!name.isEmpty() && !".".equals(name)) {
                if ("..".equals(name)) {
                    throw new IOException("RAR entry path must not contain ..");
                }
                hasName = true;
            }
            start = end + 1;
        }
        if (!hasName) {
            throw new IOException("RAR entry is missing a path");
        }
        return path;
    }

    /// Returns the index of the next archive path separator, or the path length.
    private static int nextPathSeparator(String path, int start) {
        int forwardSlash = path.indexOf('/', start);
        int backslash = path.indexOf('\\', start);
        if (forwardSlash < 0) {
            return backslash >= 0 ? backslash : path.length();
        }
        if (backslash < 0) {
            return forwardSlash;
        }
        return Math.min(forwardSlash, backslash);
    }

    /// Converts epoch seconds to a file time.
    private static FileTime fileTimeFromEpochSecond(long seconds) throws IOException {
        return fileTimeFromEpochSecond(seconds, 0L, "modification time");
    }

    /// Converts epoch seconds and nanoseconds to a file time.
    private static FileTime fileTimeFromEpochSecond(long seconds, long nanos, String description) throws IOException {
        try {
            return FileTime.from(Instant.ofEpochSecond(seconds, nanos));
        } catch (DateTimeException exception) {
            throw new IOException("RAR " + description + " is out of range", exception);
        }
    }

    /// Converts a Windows FILETIME value to a file time.
    private static FileTime fileTimeFromWindowsFileTime(long windowsFileTime, String description) throws IOException {
        long seconds = Math.floorDiv(windowsFileTime, WINDOWS_FILETIME_TICKS_PER_SECOND)
                - WINDOWS_FILETIME_UNIX_EPOCH_SECONDS;
        long nanos = Math.floorMod(windowsFileTime, WINDOWS_FILETIME_TICKS_PER_SECOND) * 100L;
        return fileTimeFromEpochSecond(seconds, nanos, description);
    }

    /// Converts a RAR4 DOS timestamp into a file time.
    private static FileTime fileTimeFromDosTime(int dosTime) throws IOException {
        if (dosTime == 0) {
            return MISSING_TIME;
        }
        int second = (dosTime & 0x1f) << 1;
        int minute = (dosTime >>> 5) & 0x3f;
        int hour = (dosTime >>> 11) & 0x1f;
        int day = (dosTime >>> 16) & 0x1f;
        int month = (dosTime >>> 21) & 0x0f;
        int year = ((dosTime >>> 25) & 0x7f) + 1980;
        if (day == 0 || month == 0) {
            return MISSING_TIME;
        }
        try {
            return FileTime.from(LocalDateTime.of(year, month, day, hour, minute, second).toInstant(ZoneOffset.UTC));
        } catch (DateTimeException exception) {
            throw new IOException("RAR modification time is out of range", exception);
        }
    }

    /// Prepares stored-body integrity validation state for the current entry.
    private void prepareCurrentIntegrity() {
        RarEntryAttributes attributes = Objects.requireNonNull(currentAttributes, "currentAttributes");
        currentCrcActive = attributes.compressionMethod() == 0
                && !attributes.isEncrypted()
                && !attributes.isDirectory()
                && !attributes.isSymbolicLink()
                && attributes.dataCrc32() != RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE;
        currentCrcVerified = false;
        currentExpectedCrc32 = attributes.dataCrc32();
        if (currentCrcActive) {
            dataCrc32.reset();
        }

        currentExpectedBlake2sp = attributes.blake2spHash();
        currentBlake2spActive = attributes.compressionMethod() == 0
                && !attributes.isEncrypted()
                && attributes.isRegularFile()
                && currentPartCompression instanceof Rar5CompressionInfo
                && (currentExpectedBlake2sp != null || attributes.continuesInNextVolume());
        currentBlake2spVerified = false;
        if (currentBlake2spActive) {
            dataBlake2sp.reset();
        }
    }

    /// Skips any unread bytes from the current entry body.
    private void skipCurrentEntryBody() throws IOException {
        if (currentBodyConsumed) {
            return;
        }
        boolean requireReadableContinuation = currentBodyOpened
                && currentAttributes != null
                && !currentAttributes.isEncrypted()
                && currentAttributes.compressionMethod() == 0
                && !currentAttributes.continuesFromPreviousVolume();
        try {
            DecodedInputStream decodedInput = currentDecodedInput;
            if (decodedInput == null && shouldDecodeSkippedCompressedEntry()) {
                RarEntryAttributes attributes = Objects.requireNonNull(currentAttributes, "currentAttributes");
                decodedInput = openCompressedInput(attributes);
            }

            EncryptedEntryInputStream encryptedInput = currentEncryptedInput;
            if (decodedInput != null) {
                decodedInput.drain();
            } else if (encryptedInput != null) {
                encryptedInput.drain();
            } else {
                while (true) {
                    if (currentPackedRemaining > 0) {
                        if (validateSkippedBodies && (currentCrcActive || currentBlake2spActive)) {
                            consumeStoredDataWithIntegrity(currentPackedRemaining);
                        } else {
                            skipFully(currentPackedRemaining);
                            currentPackedRemaining = 0L;
                        }
                    }
                    if (!moveToNextContinuationPartIfPresent(requireReadableContinuation)) {
                        break;
                    }
                }
                invalidateSkippedCompressionHistory();
            }
            if (validateSkippedBodies || currentBodyOpened) {
                verifyCurrentIntegrityIfComplete();
            }
        } finally {
            clearCurrentBodyState();
            currentBodyOpened = false;
            currentBodyConsumed = true;
            currentCrcActive = false;
            currentCrcVerified = false;
            currentExpectedCrc32 = RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE;
            currentBlake2spActive = false;
            currentBlake2spVerified = false;
            currentExpectedBlake2sp = null;
        }
    }

    /// Returns whether a skipped entry must be decompressed to preserve a solid dictionary.
    private boolean shouldDecodeSkippedCompressedEntry() {
        RarEntryAttributes attributes = currentAttributes;
        RarCompressionInfo compressionInfo = currentPartCompression;
        if (attributes == null
                || compressionInfo == null
                || attributes.compressionMethod() == 0
                || attributes.isEncrypted() && passwordProvider == null
                || !isCurrentBodyReadable()) {
            return false;
        }
        return compressionInfo.solid()
                || compressionInfo instanceof Rar4CompressionInfo && rar4ArchiveSolid
                || compressionInfo instanceof Rar5CompressionInfo && rar5ArchiveSolid;
    }

    /// Invalidates the active format's solid history when compressed packed bytes were skipped directly.
    private void invalidateSkippedCompressionHistory() {
        RarEntryAttributes attributes = currentAttributes;
        RarCompressionInfo compressionInfo = currentPartCompression;
        if (attributes == null || attributes.compressionMethod() == 0 || compressionInfo == null) {
            return;
        }
        if (compressionInfo instanceof Rar4CompressionInfo) {
            rar4DecoderHistoryInvalid = true;
            Rar4Decoder.Session session = rar4DecoderSession;
            if (session != null) {
                session.invalidateHistory();
            }
            return;
        }
        rar5DecoderHistoryInvalid = true;
        Rar5Decoder.Session session = rar5DecoderSession;
        if (session != null) {
            session.invalidateHistory();
        }
    }
    /// Clears sensitive or asynchronous state retained while an entry body is active.
    private void clearCurrentBodyState() {
        Rar5Crypto.clear(currentHashKey);
        currentHashKey = null;
        EncryptedEntryInputStream encryptedInput = currentEncryptedInput;
        if (encryptedInput != null) {
            encryptedInput.release();
        }
        currentEncryptedInput = null;
        DecodedInputStream decodedInput = currentDecodedInput;
        if (decodedInput != null) {
            decodedInput.release();
        }
        currentDecodedInput = null;
    }

    /// Advances to the next continuation part when the current physical part is exhausted.
    private boolean moveToNextContinuationPartIfPresent(boolean requireReadableData) throws IOException {
        RarEntryAttributes previousPartAttributes = currentPartAttributes;
        if (previousPartAttributes == null || !previousPartAttributes.continuesInNextVolume()) {
            return false;
        }

        @Nullable BlockHeader block;
        while (true) {
            block = readBlockHeader();
            if (block == null) {
                throw new EOFException("Unexpected end of RAR multi-volume entry");
            }
            if (block.type() == HEADER_TYPE_MAIN) {
                readMainHeaderMetadata(block);
                skipBlockData(block);
                continue;
            }
            if (block.type() == HEADER_TYPE_SERVICE) {
                skipBlockData(block);
                continue;
            }
            if (block.type() == HEADER_TYPE_END) {
                skipBlockData(block);
                if (!endHeaderHasNextVolume(block)) {
                    throw new IOException("RAR multi-volume entry ended before its continuation");
                }
                advanceAfterEndHeader();
                continue;
            }
            if (block.type() == HEADER_TYPE_ARCHIVE_ENCRYPTION) {
                enableEncryptedHeaders(block);
                skipBlockData(block);
                continue;
            }
            if (block.type() != HEADER_TYPE_FILE) {
                throw new IOException("RAR multi-volume entry continuation is missing");
            }
            break;
        }
        ParsedFileHeader parsedFile = parseFileHeader(block);
        RarEntryAttributes nextPartAttributes = parsedFile.attributes();
        validateContinuationPart(
                previousPartAttributes,
                nextPartAttributes,
                parsedFile.compressionInfo(),
                parsedFile.encryptionInfo(),
                requireReadableData
        );
        currentPartAttributes = nextPartAttributes;
        currentPartEncryption = parsedFile.encryptionInfo();
        currentPartCompression = parsedFile.compressionInfo();
        currentPackedRemaining = block.dataSize();
        if (currentCrcActive) {
            currentExpectedCrc32 = nextPartAttributes.dataCrc32();
        }
        if (currentBlake2spActive) {
            currentExpectedBlake2sp = nextPartAttributes.blake2spHash();
        }
        return true;
    }

    /// Validates that a physical file part belongs to the current logical entry.
    private void validateContinuationPart(
            RarEntryAttributes previousPartAttributes,
            RarEntryAttributes nextPartAttributes,
            RarCompressionInfo nextCompressionInfo,
            @Nullable RarEncryptionInfo nextEncryptionInfo,
            boolean requireReadableData
    ) throws IOException {
        RarEntryAttributes entryAttributes = Objects.requireNonNull(currentAttributes, "currentAttributes");
        if (!nextPartAttributes.continuesFromPreviousVolume()) {
            throw new IOException("RAR multi-volume entry continuation is missing the previous-volume flag");
        }
        if (!entryAttributes.path().equals(nextPartAttributes.path())) {
            throw new IOException("RAR multi-volume entry continuation path differs");
        }
        if (previousPartAttributes.isRegularFile() != nextPartAttributes.isRegularFile()
                || previousPartAttributes.isDirectory() != nextPartAttributes.isDirectory()
                || previousPartAttributes.isSymbolicLink() != nextPartAttributes.isSymbolicLink()
                || previousPartAttributes.isOther() != nextPartAttributes.isOther()) {
            throw new IOException("RAR multi-volume entry continuation type differs");
        }
        if (entryAttributes.unpackedSize() != RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE
                && nextPartAttributes.unpackedSize() != RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE
                && entryAttributes.unpackedSize() != nextPartAttributes.unpackedSize()) {
            throw new IOException("RAR multi-volume entry continuation size differs");
        }
        if (entryAttributes.compressionMethod() != nextPartAttributes.compressionMethod()) {
            throw new IOException("RAR multi-volume entry continuation compression method differs");
        }
        if (entryAttributes.isEncrypted() != nextPartAttributes.isEncrypted()) {
            throw new IOException("RAR multi-volume entry continuation encryption state differs");
        }
        RarCompressionInfo previousCompressionInfo =
                Objects.requireNonNull(currentPartCompression, "currentPartCompression");
        if (!previousCompressionInfo.equals(nextCompressionInfo)) {
            throw new IOException("RAR multi-volume entry continuation compression properties differ");
        }
        if (!encryptionMetadataMatches(currentPartEncryption, nextEncryptionInfo)) {
            throw new IOException("RAR multi-volume entry continuation encryption properties differ");
        }
        if (requireReadableData && !nextPartAttributes.isRegularFile()) {
            throw new IOException("RAR multi-volume entry continuation is not a regular file");
        }
    }

    /// Returns whether two physical parts use equivalent encryption parameters.
    private static boolean encryptionMetadataMatches(
            @Nullable RarEncryptionInfo first,
            @Nullable RarEncryptionInfo second
    ) {
        if (first == second) {
            return true;
        }
        if (first instanceof Rar3EncryptionInfo firstRar3
                && second instanceof Rar3EncryptionInfo secondRar3) {
            return firstRar3.extractionVersion() == secondRar3.extractionVersion()
                    && Arrays.equals(firstRar3.salt(), secondRar3.salt());
        }
        if (first instanceof Rar5EncryptionInfo firstRar5
                && second instanceof Rar5EncryptionInfo secondRar5) {
            return firstRar5.kdfLog() == secondRar5.kdfLog()
                    && firstRar5.tweakedChecksums() == secondRar5.tweakedChecksums()
                    && Arrays.equals(firstRar5.salt(), secondRar5.salt())
                    && Arrays.equals(firstRar5.initializationVector(), secondRar5.initializationVector())
                    && Arrays.equals(firstRar5.passwordCheck(), secondRar5.passwordCheck());
        }
        return false;
    }
    /// Ensures that the current logical entry has physical data available to read.
    private boolean ensureCurrentEntryDataAvailable() throws IOException {
        while (currentPackedRemaining == 0L) {
            if (!moveToNextContinuationPartIfPresent(true)) {
                verifyCurrentIntegrityIfComplete();
                return false;
            }
        }
        return true;
    }

    /// Skips all data attached to a non-file block.
    private void skipBlockData(BlockHeader block) throws IOException {
        skipFully(block.dataSize());
    }

    /// Consumes stored file bytes while updating all active integrity checks.
    private void consumeStoredDataWithIntegrity(long count) throws IOException {
        byte[] discard = new byte[8192];
        long remaining = count;
        while (remaining > 0) {
            int read = source.read(discard, 0, (int) Math.min(discard.length, remaining));
            if (read < 0) {
                throw new EOFException("Unexpected end of RAR entry data");
            }
            if (currentCrcActive) {
                dataCrc32.update(discard, 0, read);
            }
            if (currentBlake2spActive) {
                dataBlake2sp.update(discard, 0, read);
            }
            remaining -= read;
            currentPackedRemaining -= read;
        }
    }

    /// Skips exactly the requested byte count from the archive source.
    private void skipFully(long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = source.skip(remaining);
            if (skipped == 0) {
                if (source.read() < 0) {
                    throw new EOFException("Unexpected end of RAR data area");
                }
                skipped = 1L;
            }
            remaining -= skipped;
        }
    }

    /// Verifies all active stored-body checksums after the final physical part is consumed.
    private void verifyCurrentIntegrityIfComplete() throws IOException {
        RarEntryAttributes partAttributes = currentPartAttributes;
        if (currentPackedRemaining != 0L
                || partAttributes != null && partAttributes.continuesInNextVolume()) {
            return;
        }
        if (currentCrcActive && !currentCrcVerified) {
            long actual = dataCrc32.getValue();
            byte @Nullable [] hashKey = currentHashKey;
            if (hashKey != null) {
                actual = Rar5Crypto.keyedCrc32(actual, hashKey);
            }
            if (actual != currentExpectedCrc32) {
                throw new IOException("Invalid RAR entry CRC32");
            }
            currentCrcVerified = true;
        }

        byte @Nullable @Unmodifiable [] expectedBlake2sp = currentExpectedBlake2sp;
        if (currentBlake2spActive && !currentBlake2spVerified) {
            if (expectedBlake2sp != null) {
                verifyBlake2sp(dataBlake2sp, expectedBlake2sp, currentHashKey);
            }
            currentBlake2spVerified = true;
        }
    }

    /// Verifies a plain or password-dependent RAR5 BLAKE2sp body hash.
    private static void verifyBlake2sp(
            RarBlake2sp blake2sp,
            byte @Unmodifiable [] expected,
            byte @Nullable [] hashKey
    ) throws IOException {
        byte[] rawDigest = blake2sp.digest();
        byte[] actualDigest = rawDigest;
        try {
            if (hashKey != null) {
                actualDigest = Rar5Crypto.keyedBlake2sp(rawDigest, hashKey);
            }
            if (!MessageDigest.isEqual(actualDigest, expected)) {
                throw new IOException("Invalid RAR entry BLAKE2sp hash");
            }
        } finally {
            if (actualDigest != rawDigest) {
                Rar5Crypto.clear(actualDigest);
            }
            Rar5Crypto.clear(rawDigest);
        }
    }

    /// Reads a RAR variable length integer from the archive source.
    private VintBytes readSourceVint(String description, int maxBytes) throws IOException {
        byte[] bytes = new byte[maxBytes];
        long value = 0L;
        int shift = 0;
        for (int count = 0; count < maxBytes; count++) {
            int next = source.read();
            if (next < 0) {
                throw new EOFException("Unexpected end of " + description);
            }
            bytes[count] = (byte) next;
            if (count == 9 && (next & 0x7e) != 0) {
                throw new IOException(description + " is too large");
            }
            value |= (long) (next & 0x7f) << shift;
            if ((next & 0x80) == 0) {
                byte[] exact = new byte[count + 1];
                System.arraycopy(bytes, 0, exact, 0, exact.length);
                return new VintBytes(value, exact);
            }
            shift += 7;
        }
        throw new IOException(description + " is too large");
    }

    /// Reads bytes fully from the archive source.
    private void readFully(byte[] buffer, int offset, int length, String eofMessage) throws IOException {
        int position = offset;
        int end = offset + length;
        while (position < end) {
            int read = source.read(buffer, position, end - position);
            if (read < 0) {
                throw new EOFException(eofMessage);
            }
            position += read;
        }
    }

    /// Reads an integer-sized variable length integer.
    private static int readIntVint(HeaderReader reader, String description) throws IOException {
        long value = reader.readVint(description);
        if (value > Integer.MAX_VALUE) {
            throw new IOException(description + " is too large");
        }
        return (int) value;
    }

    /// Adds an unsigned size to a position after checking the result.
    private static int checkedAdd(int position, long size, String description) throws IOException {
        if (size > Integer.MAX_VALUE - position) {
            throw new IOException(description + " is too large");
        }
        return position + (int) size;
    }

    /// Reads a little-endian unsigned 32-bit integer from a byte array.
    private static long littleEndianUInt32(byte[] data, int offset) {
        return Integer.toUnsignedLong(ByteArrayAccess.readIntLittleEndian(data, offset));
    }

    /// Reads a little-endian unsigned 16-bit integer from a byte array.
    private static int littleEndianUInt16(byte[] data, int offset) {
        return Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(data, offset));
    }

    /// Requires this reader to be open.
    private void ensureOpen() throws IOException {
        if (!open) {
            throw new IOException("RAR streaming reader is closed");
        }
    }

    /// Stores one decoded RAR block header.
    ///
    /// @param version the detected RAR archive version
    /// @param type the RAR block type
    /// @param flags the RAR block flags
    /// @param dataSize the optional data area size
    /// @param headerData the CRC-covered header data bytes
    /// @param fieldsOffset the offset of block-type-specific fields
    /// @param extraOffset the offset of the optional extra area
    @NotNullByDefault
    private record BlockHeader(
            int version,
            int type,
            long flags,
            long dataSize,
            byte @Unmodifiable [] headerData,
            int fieldsOffset,
            int extraOffset
    ) {
        /// Creates a decoded RAR block header.
        private BlockHeader {
            Objects.requireNonNull(headerData, "headerData");
            if (version < 0 || type < 0 || flags < 0 || dataSize < 0 || fieldsOffset < 0 || extraOffset < fieldsOffset
                    || extraOffset > headerData.length) {
                throw new IllegalArgumentException("Invalid RAR block header");
            }
        }
    }

    /// Stores one parsed file header together with internal body metadata.
    ///
    /// @param attributes the public entry attributes
    /// @param encryptionInfo the format-specific encryption metadata, or `null` for an unencrypted entry
    /// @param compressionInfo the format-specific decompression metadata
    @NotNullByDefault
    private record ParsedFileHeader(
            RarEntryAttributes attributes,
            @Nullable RarEncryptionInfo encryptionInfo,
            RarCompressionInfo compressionInfo
    ) {
        /// Creates one parsed file header result.
        private ParsedFileHeader {
            Objects.requireNonNull(attributes, "attributes");
            Objects.requireNonNull(compressionInfo, "compressionInfo");
        }
    }

    /// Marks validated decompression metadata for one physical RAR file body.
    @NotNullByDefault
    private sealed interface RarCompressionInfo permits Rar4CompressionInfo, Rar5CompressionInfo {
        /// Returns whether this entry continues the preceding decompression dictionary.
        boolean solid();
    }

    /// Stores the RAR4 decompression state selectors for one physical file body.
    ///
    /// @param extractionVersion the RAR decompression algorithm version
    /// @param solid whether this entry continues the preceding decompression dictionary
    @NotNullByDefault
    private record Rar4CompressionInfo(int extractionVersion, boolean solid) implements RarCompressionInfo {
        /// Validates RAR4 decompression metadata.
        private Rar4CompressionInfo {
            if (extractionVersion < 0 || extractionVersion > 0xff) {
                throw new IllegalArgumentException("Invalid RAR4 extraction version");
            }
        }
    }

    /// Stores the RAR5 decompression state selectors for one physical file body.
    ///
    /// @param algorithmVersion the raw RAR5 or RAR7 compression algorithm version
    /// @param dictionaryPower the power component of the dictionary size
    /// @param dictionaryFraction the RAR7 fractional dictionary size component
    /// @param version7 whether the decoder must use RAR7 Huffman behavior
    /// @param solid whether this entry continues the preceding decompression dictionary
    @NotNullByDefault
    private record Rar5CompressionInfo(
            int algorithmVersion,
            int dictionaryPower,
            int dictionaryFraction,
            boolean version7,
            boolean solid
    ) implements RarCompressionInfo {
        /// Validates RAR5 decompression metadata.
        private Rar5CompressionInfo {
            if (algorithmVersion < 0 || algorithmVersion > COMPRESSION_ALGORITHM_MASK
                    || dictionaryPower < 0 || dictionaryPower > COMPRESSION_DICTIONARY_POWER_V7_MASK
                    || dictionaryFraction < 0
                    || dictionaryFraction > COMPRESSION_DICTIONARY_FRACTION_MASK
                    || algorithmVersion == 0 && dictionaryFraction != 0
                    || version7 && algorithmVersion != 1) {
                throw new IllegalArgumentException("Invalid RAR5 compression metadata");
            }
        }
    }

    /// Marks validated encryption metadata for one physical RAR file body.
    @NotNullByDefault
    private sealed interface RarEncryptionInfo permits Rar3EncryptionInfo, Rar5EncryptionInfo {
    }

    /// Stores RAR 3.x encryption parameters for one file body.
    ///
    /// @param extractionVersion the RAR extraction version selecting the encryption scheme
    /// @param salt the optional eight-byte AES derivation salt
    @NotNullByDefault
    private record Rar3EncryptionInfo(
            int extractionVersion,
            byte @Nullable @Unmodifiable [] salt
    ) implements RarEncryptionInfo {
        /// Creates immutable RAR 3.x encryption metadata.
        private Rar3EncryptionInfo {
            if (extractionVersion < 0 || salt != null && salt.length != Rar3Crypto.SALT_SIZE) {
                throw new IllegalArgumentException("Invalid RAR3 encryption metadata");
            }
            salt = salt != null ? salt.clone() : null;
        }
    }

    /// Stores validated RAR5 encryption parameters for one file body.
    ///
    /// @param kdfLog the binary logarithm of the PBKDF2 iteration count
    /// @param tweakedChecksums whether data checksums use the password-dependent representation
    /// @param salt the 16-byte PBKDF2 salt
    /// @param initializationVector the 16-byte AES-CBC initialization vector
    /// @param passwordCheck the validated 8-byte password verification value, or `null` when unavailable
    @NotNullByDefault
    private record Rar5EncryptionInfo(
            int kdfLog,
            boolean tweakedChecksums,
            byte @Unmodifiable [] salt,
            byte @Unmodifiable [] initializationVector,
            byte @Nullable @Unmodifiable [] passwordCheck
    ) implements RarEncryptionInfo {
        /// Creates immutable RAR5 encryption metadata.
        private Rar5EncryptionInfo {
            if (kdfLog < 0 || kdfLog > Rar5Crypto.MAX_KDF_LOG
                    || salt.length != Rar5Crypto.SALT_SIZE
                    || initializationVector.length != Rar5Crypto.BLOCK_SIZE
                    || passwordCheck != null && passwordCheck.length != Rar5Crypto.PASSWORD_CHECK_SIZE) {
                throw new IllegalArgumentException("Invalid RAR5 encryption metadata");
            }
            salt = salt.clone();
            initializationVector = initializationVector.clone();
            passwordCheck = passwordCheck != null ? passwordCheck.clone() : null;
        }
    }

    /// Stores a source-read variable length integer and its encoded bytes.
    ///
    /// @param value the decoded integer value
    /// @param bytes the exact encoded bytes consumed from the source
    @NotNullByDefault
    private record VintBytes(long value, byte @Unmodifiable [] bytes) {
        /// Creates a source-read variable length integer.
        private VintBytes {
            Objects.requireNonNull(bytes, "bytes");
            if (value < 0) {
                throw new IllegalArgumentException("value must not be negative");
            }
        }
    }

    /// Reads primitive values from a bounded RAR header byte range.
    @NotNullByDefault
    private static final class HeaderReader {
        /// The header bytes.
        private final byte @Unmodifiable [] data;

        /// The exclusive read limit.
        private final int limit;

        /// The current read position.
        private int position;

        /// Creates a header reader over the given byte range.
        private HeaderReader(byte @Unmodifiable [] data, int offset, int limit) {
            this.data = Objects.requireNonNull(data, "data");
            if (offset < 0 || limit < offset || limit > data.length) {
                throw new IllegalArgumentException("Invalid RAR header reader range");
            }
            this.position = offset;
            this.limit = limit;
        }

        /// Returns the current read position.
        private int position() {
            return position;
        }

        /// Moves the current read position.
        private void position(int position) {
            if (position < this.position || position > limit) {
                throw new IllegalArgumentException("Invalid RAR header reader position");
            }
            this.position = position;
        }

        /// Returns the remaining readable byte count.
        private int remaining() {
            return limit - position;
        }

        /// Returns whether any bytes remain.
        private boolean hasRemaining() {
            return position < limit;
        }

        /// Reads one RAR variable length integer.
        private long readVint(String description) throws IOException {
            long value = 0L;
            int shift = 0;
            for (int count = 0; count < 10; count++) {
                int next = readUnsignedByte(description);
                if (count == 9 && (next & 0x7e) != 0) {
                    throw new IOException(description + " is too large");
                }
                value |= (long) (next & 0x7f) << shift;
                if ((next & 0x80) == 0) {
                    return value;
                }
                shift += 7;
            }
            throw new IOException(description + " is too large");
        }

        /// Reads one little-endian unsigned 32-bit integer.
        private long readUInt32(String description) throws IOException {
            requireRemaining(Integer.BYTES, description);
            long value = littleEndianUInt32(data, position);
            position += Integer.BYTES;
            return value;
        }

        /// Reads one little-endian unsigned 16-bit integer.
        private int readUInt16(String description) throws IOException {
            requireRemaining(Short.BYTES, description);
            int value = littleEndianUInt16(data, position);
            position += Short.BYTES;
            return value;
        }

        /// Reads one little-endian unsigned 64-bit integer that fits in a signed `long`.
        private long readUInt64(String description) throws IOException {
            requireRemaining(Long.BYTES, description);
            long value = (long) Byte.toUnsignedInt(data[position])
                    | (long) Byte.toUnsignedInt(data[position + 1]) << 8
                    | (long) Byte.toUnsignedInt(data[position + 2]) << 16
                    | (long) Byte.toUnsignedInt(data[position + 3]) << 24
                    | (long) Byte.toUnsignedInt(data[position + 4]) << 32
                    | (long) Byte.toUnsignedInt(data[position + 5]) << 40
                    | (long) Byte.toUnsignedInt(data[position + 6]) << 48
                    | (long) Byte.toUnsignedInt(data[position + 7]) << 56;
            position += Long.BYTES;
            if (value < 0) {
                throw new IOException(description + " is too large");
            }
            return value;
        }

        /// Reads one UTF-8 string of the requested byte length.
        private String readString(int length, String description) throws IOException {
            if (length < 0) {
                throw new IOException(description + " length must not be negative");
            }
            requireRemaining(length, description);
            String value = new String(data, position, length, StandardCharsets.UTF_8);
            position += length;
            return value;
        }

        /// Reads raw bytes of the requested length.
        private byte @Unmodifiable [] readBytes(int length, String description) throws IOException {
            if (length < 0) {
                throw new IOException(description + " length must not be negative");
            }
            requireRemaining(length, description);
            byte[] value = new byte[length];
            System.arraycopy(data, position, value, 0, length);
            position += length;
            return value;
        }

        /// Reads one unsigned byte.
        private int readUnsignedByte(String description) throws IOException {
            requireRemaining(1, description);
            return Byte.toUnsignedInt(data[position++]);
        }

        /// Requires the requested number of bytes to remain.
        private void requireRemaining(int byteCount, String description) throws IOException {
            if (byteCount > remaining()) {
                throw new EOFException("Unexpected end of " + description);
            }
        }
    }

    /// Stores metadata decoded from file extra records.
    @NotNullByDefault
    private static final class ExtraMetadata {
        /// The RAR5 file encryption metadata, or `null` when the entry is not encrypted.
        private @Nullable Rar5EncryptionInfo encryptionInfo;

        /// The symbolic link target, or `null` when absent.
        private @Nullable String linkName;

        /// The RAR redirection type.
        private int redirectionType = RarArkivoEntryAttributes.NO_REDIRECTION_TYPE;

        /// The RAR redirection flags.
        private long redirectionFlags;

        /// The RAR redirection target, or `null` when absent.
        private @Nullable String redirectionTarget;

        /// The Unix owner user name, or `null` when absent.
        private @Nullable String userName;

        /// The Unix owner group name, or `null` when absent.
        private @Nullable String groupName;

        /// The numeric Unix owner user identifier.
        private long userId = RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE;

        /// The numeric Unix owner group identifier.
        private long groupId = RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE;

        /// The BLAKE2sp hash, or `null` when absent.
        private byte @Nullable @Unmodifiable [] blake2spHash;

        /// The high precision last modified time, or `null` when absent.
        private @Nullable FileTime lastModifiedTime;

        /// The high precision creation time, or `null` when absent.
        private @Nullable FileTime creationTime;

        /// The high precision last access time, or `null` when absent.
        private @Nullable FileTime lastAccessTime;

        /// Creates empty extra metadata.
        private ExtraMetadata() {
        }
    }

    /// Exposes the raw packed bytes of one logical RAR entry across physical volumes.
    @NotNullByDefault
    private final class PackedEntryInputStream extends InputStream {
        /// A reusable buffer for single-byte reads.
        private final byte[] singleByte = new byte[1];

        /// Whether this packed stream can still be read.
        private boolean entryOpen = true;

        /// Reads one packed byte.
        @Override
        public int read() throws IOException {
            int count = read(singleByte, 0, 1);
            return count < 0 ? -1 : Byte.toUnsignedInt(singleByte[0]);
        }

        /// Reads packed bytes without crossing the active physical data area.
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            ensureEntryOpen();
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            while (currentPackedRemaining == 0L) {
                RarEntryAttributes partAttributes = currentPartAttributes;
                if (partAttributes == null || !partAttributes.continuesInNextVolume()) {
                    return -1;
                }
                if (!moveToNextContinuationPartIfPresent(true)) {
                    return -1;
                }
            }

            int requested = (int) Math.min(length, currentPackedRemaining);
            int count = source.read(buffer, offset, requested);
            if (count < 0) {
                throw new EOFException("Unexpected end of RAR entry packed data");
            }
            currentPackedRemaining -= count;
            return count;
        }

        /// Returns the packed bytes remaining in the current physical data area.
        @Override
        public int available() throws IOException {
            ensureEntryOpen();
            return (int) Math.min(currentPackedRemaining, Integer.MAX_VALUE);
        }

        /// Releases this view without closing the shared archive source.
        @Override
        public void close() {
            entryOpen = false;
            Arrays.fill(singleByte, (byte) 0);
        }

        /// Requires this packed stream and its owner to remain open.
        private void ensureEntryOpen() throws IOException {
            ensureOpen();
            if (!entryOpen) {
                throw new IOException("RAR packed entry stream is closed");
            }
        }
    }

    /// Exposes every AES-decrypted packed block, including final compression padding.
    @NotNullByDefault
    private final class EncryptedPackedInputStream extends InputStream {
        /// The raw ciphertext stream spanning every physical continuation part.
        private final PackedEntryInputStream packedInput = new PackedEntryInputStream();

        /// The stateful AES-CBC decryptor.
        private final RarCbcDecryptor decryptor;

        /// One ciphertext block read from the archive.
        private final byte[] ciphertext = new byte[RarCbcDecryptor.BLOCK_SIZE];

        /// One decrypted packed block.
        private final byte[] plaintext = new byte[RarCbcDecryptor.BLOCK_SIZE];

        /// A reusable buffer for single-byte reads.
        private final byte[] singleByte = new byte[1];

        /// The current position in the decrypted packed block.
        private int blockPosition;

        /// The exclusive limit in the decrypted packed block.
        private int blockLimit;

        /// Whether this packed stream retains usable decryption state.
        private boolean entryOpen = true;

        /// Creates one encrypted packed stream.
        private EncryptedPackedInputStream(RarCbcDecryptor decryptor) {
            this.decryptor = Objects.requireNonNull(decryptor, "decryptor");
        }

        /// Reads one decrypted packed byte.
        @Override
        public int read() throws IOException {
            int count = read(singleByte, 0, 1);
            return count < 0 ? -1 : Byte.toUnsignedInt(singleByte[0]);
        }

        /// Reads decrypted packed bytes without exposing the archive source directly.
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            ensureEntryOpen();
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            int total = 0;
            while (total < length) {
                if (blockPosition == blockLimit && !fillPlaintextBlock()) {
                    break;
                }
                int count = Math.min(length - total, blockLimit - blockPosition);
                System.arraycopy(plaintext, blockPosition, buffer, offset + total, count);
                blockPosition += count;
                total += count;
            }
            return total == 0 ? -1 : total;
        }

        /// Returns decrypted packed bytes already available in memory.
        @Override
        public int available() throws IOException {
            ensureEntryOpen();
            return blockLimit - blockPosition;
        }

        /// Clears retained plaintext and CBC state without closing the archive source.
        @Override
        public void close() {
            if (!entryOpen) {
                return;
            }
            entryOpen = false;
            packedInput.close();
            decryptor.clear();
            Arrays.fill(ciphertext, (byte) 0);
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(singleByte, (byte) 0);
        }

        /// Decrypts the next complete packed block and returns whether one was available.
        private boolean fillPlaintextBlock() throws IOException {
            int offset = 0;
            while (offset < ciphertext.length) {
                int count = packedInput.read(ciphertext, offset, ciphertext.length - offset);
                if (count < 0) {
                    if (offset == 0) {
                        return false;
                    }
                    throw new EOFException("Unexpected end of encrypted RAR packed data");
                }
                offset += count;
            }
            decryptor.decryptBlock(ciphertext, plaintext);
            blockPosition = 0;
            blockLimit = plaintext.length;
            return true;
        }

        /// Requires this packed stream and its owner to remain open.
        private void ensureEntryOpen() throws IOException {
            ensureOpen();
            if (!entryOpen) {
                throw new IOException("Encrypted RAR packed entry stream is closed");
            }
        }
    }

    /// Exposes packed bytes decrypted by a legacy RAR stream or block cipher.
    @NotNullByDefault
    private final class LegacyEncryptedPackedInputStream extends InputStream {
        /// The raw ciphertext stream spanning every physical continuation part.
        private final PackedEntryInputStream packedInput = new PackedEntryInputStream();

        /// The stateful legacy cipher.
        private final RarLegacyCrypto.Decryptor decryptor;

        /// The cipher input alignment.
        private final int blockSize;

        /// One mutable RAR 2.x ciphertext and plaintext block.
        private final byte[] block;

        /// A reusable buffer for single-byte reads.
        private final byte[] singleByte = new byte[1];

        /// The current position in a decrypted RAR 2.x block.
        private int blockPosition;

        /// The exclusive limit in a decrypted RAR 2.x block.
        private int blockLimit;

        /// Whether this packed stream retains usable decryption state.
        private boolean entryOpen = true;

        /// Creates one legacy encrypted packed stream.
        private LegacyEncryptedPackedInputStream(RarLegacyCrypto.Decryptor decryptor) {
            this.decryptor = Objects.requireNonNull(decryptor, "decryptor");
            blockSize = decryptor.blockSize();
            block = new byte[blockSize];
        }

        /// Reads one decrypted packed byte.
        @Override
        public int read() throws IOException {
            int count = read(singleByte, 0, 1);
            return count < 0 ? -1 : Byte.toUnsignedInt(singleByte[0]);
        }

        /// Reads and decrypts packed bytes while retaining cipher state across volume boundaries.
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            ensureEntryOpen();
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            if (blockSize == 1) {
                int count = packedInput.read(buffer, offset, length);
                if (count > 0) {
                    decryptor.decrypt(buffer, offset, count);
                }
                return count;
            }

            int total = 0;
            while (total < length) {
                if (blockPosition == blockLimit && !fillBlock()) {
                    break;
                }
                int count = Math.min(length - total, blockLimit - blockPosition);
                System.arraycopy(block, blockPosition, buffer, offset + total, count);
                blockPosition += count;
                total += count;
            }
            return total == 0 ? -1 : total;
        }

        /// Returns decrypted bytes already buffered or immediately readable without crossing a block boundary.
        @Override
        public int available() throws IOException {
            ensureEntryOpen();
            return blockSize == 1 ? packedInput.available() : blockLimit - blockPosition;
        }

        /// Clears all legacy cipher state without closing the shared archive source.
        @Override
        public void close() {
            if (!entryOpen) {
                return;
            }
            entryOpen = false;
            packedInput.close();
            decryptor.close();
            Arrays.fill(block, (byte) 0);
            Arrays.fill(singleByte, (byte) 0);
        }

        /// Decrypts the next complete RAR 2.x block and returns whether one was available.
        private boolean fillBlock() throws IOException {
            int offset = 0;
            while (offset < block.length) {
                int count = packedInput.read(block, offset, block.length - offset);
                if (count < 0) {
                    if (offset == 0) {
                        return false;
                    }
                    throw new EOFException("Unexpected end of legacy RAR encrypted packed data");
                }
                offset += count;
            }
            decryptor.decrypt(block, 0, block.length);
            blockPosition = 0;
            blockLimit = block.length;
            return true;
        }

        /// Requires this stream and its owning archive reader to remain open.
        private void ensureEntryOpen() throws IOException {
            ensureOpen();
            if (!entryOpen) {
                throw new IOException("Legacy encrypted RAR packed entry stream is closed");
            }
        }
    }
    /// Defines one format-specific compressed-entry operation used by the shared streaming worker.
    @NotNullByDefault
    private interface DecompressingReadableByteChannel {
        /// Decompresses one packed entry and returns its unsigned output CRC32.
        long decode(InputStream input, OutputStream output, long unpackedSize) throws IOException;

        /// Invalidates solid history after decompression or packed-input failure.
        void invalidateHistory();

        /// Returns whether decompressed output can carry a RAR5 BLAKE2sp hash.
        boolean usesBlake2sp();

        /// Returns the format name used in diagnostic messages.
        String formatName();
    }

    /// Adapts one RAR4 decoder session to the shared streaming worker.
    @NotNullByDefault
    private final class Rar4DecompressingReadableByteChannel implements DecompressingReadableByteChannel {
        /// The stateful RAR4 decoder session.
        private final Rar4Decoder.Session session;

        /// The decompression selectors for this entry.
        private final Rar4CompressionInfo compressionInfo;

        /// Creates one RAR4 compressed-entry operation.
        private Rar4DecompressingReadableByteChannel(
                Rar4Decoder.Session session,
                Rar4CompressionInfo compressionInfo
        ) {
            this.session = Objects.requireNonNull(session, "session");
            this.compressionInfo = Objects.requireNonNull(compressionInfo, "compressionInfo");
        }

        /// Decompresses one RAR4 entry and returns its output CRC32.
        @Override
        public long decode(InputStream input, OutputStream output, long unpackedSize) throws IOException {
            return session.decode(
                    input,
                    output,
                    compressionInfo.extractionVersion(),
                    unpackedSize,
                    compressionInfo.solid()
            ).crc32();
        }

        /// Invalidates the RAR4 dictionary after a failed operation.
        @Override
        public void invalidateHistory() {
            rar4DecoderHistoryInvalid = true;
            session.invalidateHistory();
        }

        /// Returns that RAR4 does not define BLAKE2sp file hashes.
        @Override
        public boolean usesBlake2sp() {
            return false;
        }

        /// Returns the RAR4 format name.
        @Override
        public String formatName() {
            return "RAR4";
        }
    }

    /// Adapts one RAR5 decoder session to the shared streaming worker.
    @NotNullByDefault
    private final class Rar5DecompressingReadableByteChannel implements DecompressingReadableByteChannel {
        /// The stateful RAR5 decoder session.
        private final Rar5Decoder.Session session;

        /// The decompression selectors for this entry.
        private final Rar5CompressionInfo compressionInfo;

        /// Creates one RAR5 compressed-entry operation.
        private Rar5DecompressingReadableByteChannel(
                Rar5Decoder.Session session,
                Rar5CompressionInfo compressionInfo
        ) {
            this.session = Objects.requireNonNull(session, "session");
            this.compressionInfo = Objects.requireNonNull(compressionInfo, "compressionInfo");
        }

        /// Decompresses one RAR5 entry and returns its output CRC32.
        @Override
        public long decode(InputStream input, OutputStream output, long unpackedSize) throws IOException {
            return session.decode(
                    input,
                    output,
                    compressionInfo.dictionaryPower(),
                    compressionInfo.dictionaryFraction(),
                    compressionInfo.version7(),
                    compressionInfo.solid(),
                    unpackedSize
            ).crc32();
        }

        /// Invalidates the RAR5 dictionary after a failed operation.
        @Override
        public void invalidateHistory() {
            rar5DecoderHistoryInvalid = true;
            session.invalidateHistory();
        }

        /// Returns that RAR5 can define BLAKE2sp file hashes.
        @Override
        public boolean usesBlake2sp() {
            return true;
        }

        /// Returns the RAR5 format name.
        @Override
        public String formatName() {
            return "RAR5";
        }
    }

    /// Updates a BLAKE2sp state with bytes successfully written to another output stream.
    @NotNullByDefault
    private static final class Blake2spOutputStream extends OutputStream {
        /// The decompressed-output destination.
        private final OutputStream output;

        /// The hash state receiving the same decompressed bytes.
        private final RarBlake2sp blake2sp;

        /// Creates one BLAKE2sp output wrapper.
        private Blake2spOutputStream(OutputStream output, RarBlake2sp blake2sp) {
            this.output = Objects.requireNonNull(output, "output");
            this.blake2sp = Objects.requireNonNull(blake2sp, "blake2sp");
        }

        /// Writes and hashes one decompressed byte.
        @Override
        public void write(int value) throws IOException {
            output.write(value);
            blake2sp.update(value);
        }

        /// Writes and hashes one decompressed byte range.
        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            output.write(buffer, offset, length);
            blake2sp.update(buffer, offset, length);
        }

        /// Flushes the decompressed-output destination.
        @Override
        public void flush() throws IOException {
            output.flush();
        }
    }

    /// Streams one format-specific decompression worker through a bounded pipe.
    @NotNullByDefault
    private final class DecodedInputStream extends InputStream {
        /// The packed input consumed by the decoder worker.
        private final InputStream packedInput;

        /// The format-specific decompression operation.
        private final DecompressingReadableByteChannel decoder;

        /// The public attributes used for size and checksum validation.
        private final RarEntryAttributes attributes;

        /// A private copy of the password-derived checksum key used by the worker.
        private final byte @Nullable [] checksumKey;

        /// Whether this RAR5 output can have a BLAKE2sp hash on its current or final physical part.
        private final boolean blake2spActive;

        /// The bounded consumer side of the decompressed-byte pipe.
        private final PipedInputStream decodedInput;

        /// The producer side owned by the decoder worker.
        private final PipedOutputStream decodedOutput;

        /// The daemon worker performing synchronous decompression.
        private final Thread worker;

        /// A reusable buffer used while draining skipped output.
        private final byte[] discard = new byte[8192];

        /// A reusable buffer for single-byte reads.
        private final byte[] singleByte = new byte[1];

        /// The decoder failure published before the producer pipe is closed.
        private volatile @Nullable IOException failure;

        /// Whether consumers may continue reading this entry stream.
        private boolean entryOpen = true;

        /// Creates and starts one bounded decompression worker.
        private DecodedInputStream(
                InputStream packedInput,
                DecompressingReadableByteChannel decoder,
                RarEntryAttributes attributes
        ) throws IOException {
            this.packedInput = Objects.requireNonNull(packedInput, "packedInput");
            this.decoder = Objects.requireNonNull(decoder, "decoder");
            this.attributes = Objects.requireNonNull(attributes, "attributes");
            byte @Nullable @Unmodifiable [] initialBlake2sp = attributes.blake2spHash();
            blake2spActive = decoder.usesBlake2sp()
                    && (initialBlake2sp != null || attributes.continuesInNextVolume());
            decodedInput = new PipedInputStream(RAR_DECODE_PIPE_SIZE);
            decodedOutput = new PipedOutputStream(decodedInput);
            byte @Nullable [] activeHashKey = currentHashKey;
            checksumKey = activeHashKey != null ? activeHashKey.clone() : null;
            worker = new Thread(this::decode, "arkivo-rar-decoder");
            worker.setDaemon(true);
            worker.start();
        }

        /// Reads one decompressed byte.
        @Override
        public int read() throws IOException {
            int count = read(singleByte, 0, 1);
            return count < 0 ? -1 : Byte.toUnsignedInt(singleByte[0]);
        }

        /// Reads decompressed bytes and reports worker failures at logical EOF.
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            ensureEntryOpen();
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }

            int count;
            try {
                count = decodedInput.read(buffer, offset, length);
            } catch (IOException exception) {
                IOException workerFailure = failure;
                if (workerFailure != null) {
                    if (workerFailure != exception) {
                        workerFailure.addSuppressed(exception);
                    }
                    throw reportedFailure(workerFailure);
                }
                throw exception;
            }
            if (count < 0) {
                awaitWorker();
                IOException workerFailure = failure;
                if (workerFailure != null) {
                    throw reportedFailure(workerFailure);
                }
            }
            return count;
        }

        /// Returns decompressed bytes currently buffered by the bounded pipe.
        @Override
        public int available() throws IOException {
            ensureEntryOpen();
            return decodedInput.available();
        }

        /// Drains and validates the remaining decompressed output before closing.
        @Override
        public void close() throws IOException {
            if (!entryOpen) {
                return;
            }
            skipCurrentEntryBody();
        }

        /// Drains every remaining decompressed byte and observes worker completion.
        private void drain() throws IOException {
            int count;
            do {
                count = read(discard, 0, discard.length);
            } while (count >= 0);
        }

        /// Performs decompression and publishes validation failures to the consumer.
        private void decode() {
            @Nullable IOException problem = null;
            try {
                @Nullable RarBlake2sp blake2sp = blake2spActive ? new RarBlake2sp() : null;
                OutputStream output = blake2sp != null
                        ? new Blake2spOutputStream(decodedOutput, blake2sp)
                        : decodedOutput;
                long actualCrc32 = decoder.decode(packedInput, output, attributes.unpackedSize());
                drainPackedInput();
                byte @Nullable [] hashKey = checksumKey;
                if (hashKey != null) {
                    actualCrc32 = Rar5Crypto.keyedCrc32(actualCrc32, hashKey);
                }
                long expectedCrc32 = completedEntryCrc32();
                if (expectedCrc32 != RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE
                        && actualCrc32 != expectedCrc32) {
                    throw new IOException("Invalid RAR entry CRC32");
                }
                if (blake2sp != null) {
                    byte @Nullable @Unmodifiable [] expectedBlake2sp = completedEntryBlake2spHash();
                    if (expectedBlake2sp != null) {
                        verifyBlake2sp(blake2sp, expectedBlake2sp, hashKey);
                    }
                }
            } catch (Throwable exception) {
                decoder.invalidateHistory();
                problem = decoderFailure(exception);
            } finally {
                Rar5Crypto.clear(checksumKey);
                try {
                    packedInput.close();
                } catch (IOException exception) {
                    if (problem == null) {
                        problem = exception;
                    } else {
                        problem.addSuppressed(exception);
                    }
                }
                failure = problem;
                try {
                    decodedOutput.close();
                } catch (IOException exception) {
                    IOException published = failure;
                    if (published == null) {
                        failure = exception;
                    } else {
                        published.addSuppressed(exception);
                    }
                }
            }
        }

        /// Consumes packed read-ahead and encryption padding after the decoder reaches the declared output size.
        private void drainPackedInput() throws IOException {
            while (packedInput.read(discard, 0, discard.length) >= 0) {
                // Keep the archive source aligned at the next block header.
            }
        }

        /// Returns the CRC32 from the final physical part after all packed continuations have been consumed.
        private long completedEntryCrc32() {
            RarEntryAttributes completedPart = currentPartAttributes;
            return completedPart != null ? completedPart.dataCrc32() : attributes.dataCrc32();
        }

        /// Returns the BLAKE2sp hash from the final physical part after all continuations are consumed.
        private byte @Nullable @Unmodifiable [] completedEntryBlake2spHash() {
            RarEntryAttributes completedPart = currentPartAttributes;
            return completedPart != null ? completedPart.blake2spHash() : attributes.blake2spHash();
        }

        /// Converts an arbitrary worker failure to the streaming API's checked error type.
        private IOException decoderFailure(Throwable exception) {
            if (exception instanceof IOException ioException) {
                return ioException;
            }
            return new IOException(
                    "Could not decompress " + decoder.formatName() + " entry: " + attributes.path(),
                    exception
            );
        }

        /// Returns a fresh checked exception so repeated EOF observations can be suppressed safely.
        private IOException reportedFailure(IOException workerFailure) {
            return new IOException(workerFailure.getMessage(), workerFailure);
        }

        /// Waits for worker termination while preserving interrupt status.
        private void awaitWorker() throws IOException {
            boolean interrupted = false;
            while (worker.isAlive()) {
                try {
                    worker.join();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for RAR decompression");
            }
        }

        /// Cancels the worker and clears buffered decompressed bytes.
        private void release() {
            if (!entryOpen) {
                return;
            }
            entryOpen = false;
            try {
                decodedInput.close();
            } catch (IOException ignored) {
                // PipedInputStream has no external resource to recover after a close failure.
            }
            try {
                packedInput.close();
            } catch (IOException ignored) {
                // The shared archive source is closed separately by its owner.
            }
            worker.interrupt();
            Arrays.fill(discard, (byte) 0);
            Arrays.fill(singleByte, (byte) 0);
        }

        /// Requires both this stream and its owning archive reader to remain open.
        private void ensureEntryOpen() throws IOException {
            ensureOpen();
            if (!entryOpen) {
                throw new IOException("RAR decompressed entry stream is closed");
            }
        }
    }
    /// Exposes one decrypted stored body while withholding format-specific alignment padding.
    @NotNullByDefault
    private final class EncryptedEntryInputStream extends InputStream {
        /// The decrypted packed stream containing logical data followed by optional alignment padding.
        private final InputStream decryptedInput;

        /// A reusable buffer used while draining skipped data and alignment padding.
        private final byte[] discard = new byte[8192];

        /// A reusable buffer for single-byte reads.
        private final byte[] singleByte = new byte[1];

        /// The number of logical plaintext bytes not yet returned.
        private long logicalRemaining;

        /// The number of decrypted alignment-padding bytes not yet consumed.
        private long paddingRemaining;

        /// Whether this entry stream still owns usable decryption state.
        private boolean entryOpen = true;

        /// Creates an encrypted stored-entry stream over an initialized packed decryptor.
        private EncryptedEntryInputStream(InputStream decryptedInput, long logicalSize, int blockSize) {
            this.decryptedInput = Objects.requireNonNull(decryptedInput, "decryptedInput");
            if (logicalSize < 0L) {
                throw new IllegalArgumentException("logicalSize must not be negative");
            }
            if (blockSize <= 0) {
                throw new IllegalArgumentException("blockSize must be positive");
            }
            this.logicalRemaining = logicalSize;
            long remainder = logicalSize % blockSize;
            paddingRemaining = remainder == 0L ? 0L : blockSize - remainder;
        }

        /// Reads one decrypted logical byte.
        @Override
        public int read() throws IOException {
            int count = read(singleByte, 0, 1);
            return count < 0 ? -1 : Byte.toUnsignedInt(singleByte[0]);
        }

        /// Reads decrypted logical bytes without exposing alignment padding.
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            ensureEntryOpen();
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            if (logicalRemaining == 0L) {
                verifyEncryptedBodyComplete();
                return -1;
            }

            int requested = (int) Math.min(length, logicalRemaining);
            int count = decryptedInput.read(buffer, offset, requested);
            if (count < 0) {
                throw new EOFException("Unexpected end of encrypted RAR entry data");
            }
            logicalRemaining -= count;
            if (currentCrcActive) {
                dataCrc32.update(buffer, offset, count);
            }
            if (currentBlake2spActive) {
                dataBlake2sp.update(buffer, offset, count);
            }
            if (logicalRemaining == 0L) {
                verifyEncryptedBodyComplete();
            }
            return count;
        }

        /// Skips decrypted logical bytes while preserving integrity validation.
        @Override
        public long skip(long count) throws IOException {
            ensureEntryOpen();
            if (count <= 0L || logicalRemaining == 0L) {
                return 0L;
            }
            long target = Math.min(count, logicalRemaining);
            long skipped = 0L;
            while (skipped < target) {
                int read = read(discard, 0, (int) Math.min(discard.length, target - skipped));
                if (read < 0) {
                    break;
                }
                skipped += read;
            }
            return skipped;
        }

        /// Returns logical bytes already available from the decrypted packed stream.
        @Override
        public int available() throws IOException {
            ensureEntryOpen();
            return (int) Math.min(logicalRemaining, decryptedInput.available());
        }

        /// Closes this entry stream after draining and authenticating its remaining body.
        @Override
        public void close() throws IOException {
            if (!entryOpen) {
                return;
            }
            skipCurrentEntryBody();
        }

        /// Drains every remaining logical byte so integrity checks are completed before advancing.
        private void drain() throws IOException {
            int read;
            do {
                read = read(discard, 0, discard.length);
            } while (read >= 0);
        }

        /// Consumes alignment padding, requires packed EOF, and verifies the logical body.
        private void verifyEncryptedBodyComplete() throws IOException {
            while (paddingRemaining > 0L) {
                int count = decryptedInput.read(
                        discard,
                        0,
                        (int) Math.min(discard.length, paddingRemaining)
                );
                if (count < 0) {
                    throw new EOFException("Unexpected end of encrypted RAR entry padding");
                }
                paddingRemaining -= count;
            }
            if (decryptedInput.read() >= 0) {
                throw new IOException("RAR encrypted stored entry has unexpected trailing ciphertext");
            }
            verifyCurrentIntegrityIfComplete();
        }

        /// Closes the packed decryptor and clears retained temporary data.
        private void release() {
            if (!entryOpen) {
                return;
            }
            entryOpen = false;
            try {
                decryptedInput.close();
            } catch (IOException ignored) {
                // The shared archive source is closed separately by its owner.
            }
            Arrays.fill(discard, (byte) 0);
            Arrays.fill(singleByte, (byte) 0);
        }

        /// Requires both the reader and this entry stream to remain open.
        private void ensureEntryOpen() throws IOException {
            ensureOpen();
            if (!entryOpen) {
                throw new IOException("RAR encrypted entry stream is closed");
            }
        }
    }

    /// Reads bytes from the current stored entry body.
    @NotNullByDefault
    private final class EntryInputStream extends InputStream {
        /// Reads one byte from the current entry body.
        @Override
        public int read() throws IOException {
            ensureOpen();
            if (!ensureCurrentEntryDataAvailable()) {
                return -1;
            }
            int value = source.read();
            if (value < 0) {
                throw new EOFException("Unexpected end of RAR entry data");
            }
            currentPackedRemaining--;
            if (currentCrcActive) {
                dataCrc32.update(value);
            }
            if (currentBlake2spActive) {
                dataBlake2sp.update(value);
            }
            if (currentPackedRemaining == 0L) {
                verifyCurrentIntegrityIfComplete();
            }
            return value;
        }

        /// Reads bytes from the current entry body.
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            ensureOpen();
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            if (!ensureCurrentEntryDataAvailable()) {
                return -1;
            }
            int read = source.read(buffer, offset, (int) Math.min(length, currentPackedRemaining));
            if (read < 0) {
                throw new EOFException("Unexpected end of RAR entry data");
            }
            currentPackedRemaining -= read;
            if (currentCrcActive) {
                dataCrc32.update(buffer, offset, read);
            }
            if (currentBlake2spActive) {
                dataBlake2sp.update(buffer, offset, read);
            }
            if (currentPackedRemaining == 0L) {
                verifyCurrentIntegrityIfComplete();
            }
            return read;
        }

        /// Skips bytes from the current entry body.
        @Override
        public long skip(long count) throws IOException {
            ensureOpen();
            if (count <= 0 || !ensureCurrentEntryDataAvailable()) {
                return 0L;
            }
            long toSkip = Math.min(count, currentPackedRemaining);
            if (currentCrcActive || currentBlake2spActive) {
                consumeStoredDataWithIntegrity(toSkip);
                if (currentPackedRemaining == 0L) {
                    verifyCurrentIntegrityIfComplete();
                }
                return toSkip;
            }
            long skipped = source.skip(toSkip);
            currentPackedRemaining -= skipped;
            return skipped;
        }

        /// Returns the approximate available current entry body bytes.
        @Override
        public int available() throws IOException {
            ensureOpen();
            return Math.min(source.available(), (int) Math.min(Integer.MAX_VALUE, currentPackedRemaining));
        }

        /// Closes this entry stream after draining the entry body.
        @Override
        public void close() throws IOException {
            skipCurrentEntryBody();
        }
    }
}
