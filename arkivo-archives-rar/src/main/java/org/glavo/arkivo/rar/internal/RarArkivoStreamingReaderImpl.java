// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar.internal;

import org.glavo.arkivo.ArkivoPasswordProvider;
import org.glavo.arkivo.rar.RarArkivoEntryAttributes;
import org.glavo.arkivo.rar.RarArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributes;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
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

    /// RAR4 file header flag indicating that data continues from the previous volume.
    private static final long RAR4_FILE_FLAG_CONTINUE_PREVIOUS = 0x0001L;

    /// RAR4 file header flag indicating that data continues in the next volume.
    private static final long RAR4_FILE_FLAG_CONTINUE_NEXT = 0x0002L;

    /// RAR4 file header flag indicating that entry data is encrypted.
    private static final long RAR4_FILE_FLAG_PASSWORD = 0x0004L;

    /// RAR4 file header flag indicating that high size fields are present.
    private static final long RAR4_FILE_FLAG_LARGE = 0x0100L;

    /// RAR4 file header flag indicating that the name field includes Unicode metadata.
    private static final long RAR4_FILE_FLAG_UNICODE = 0x0200L;

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

    /// The RAR4 dictionary bits mask in the method byte.
    private static final int RAR4_DICTIONARY_MASK = 0xe0;

    /// The RAR4 method-byte marker for directory entries.
    private static final int RAR4_FILE_IS_DIRECTORY = 0xe0;

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

    /// The fallback timestamp used when a RAR entry omits all time metadata.
    private static final FileTime MISSING_TIME = FileTime.fromMillis(0L);

    /// The backing archive input stream.
    private final InputStream source;

    /// The password provider for RAR5 encrypted headers and entries, or `null` when none is configured.
    private final @Nullable ArkivoPasswordProvider passwordProvider;

    /// The CRC32 calculator reused for header validation.
    private final CRC32 headerCrc32 = new CRC32();

    /// The CRC32 calculator reused for stored file data validation.
    private final CRC32 dataCrc32 = new CRC32();

    /// The current entry attributes, or `null` when no entry is active.
    private @Nullable RarEntryAttributes currentAttributes;

    /// The current physical file-part attributes, or `null` when no entry part is active.
    private @Nullable RarEntryAttributes currentPartAttributes;

    /// The RAR5 encryption metadata for the current physical file part, or `null` when it is not encrypted.
    private @Nullable Rar5EncryptionInfo currentPartEncryption;

    /// The remaining packed bytes in the current entry data area.
    private long currentPackedRemaining;

    /// Whether the current entry body has already been opened.
    private boolean currentBodyOpened;

    /// Whether the current stored body is being checked against a CRC32 value.
    private boolean currentCrcActive;

    /// Whether the current stored body CRC32 has already been verified.
    private boolean currentCrcVerified;

    /// The expected unsigned CRC32 value for the current stored body.
    private long currentExpectedCrc32 = RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE;

    /// The password-derived key for a tweaked current CRC32, or `null` for a plain checksum.
    private byte @Nullable [] currentHashKey;

    /// The active encrypted entry stream, or `null` before an encrypted body is opened.
    private @Nullable EncryptedEntryInputStream currentEncryptedInput;

    /// The active RAR5 AES key for encrypted archive headers, or `null` before an encryption header.
    private byte @Nullable [] archiveHeaderKey;

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
        this(source, null);
    }

    /// Creates a streaming RAR reader with an optional password provider.
    public RarArkivoStreamingReaderImpl(InputStream source, @Nullable ArkivoPasswordProvider passwordProvider) {
        this.source = Objects.requireNonNull(source, "source");
        this.passwordProvider = passwordProvider;
    }

    /// Advances to the next RAR file entry and returns whether an entry is available.
    @Override
    public boolean next() throws IOException {
        ensureOpen();
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

        while (true) {
            @Nullable BlockHeader block = readBlockHeader();
            if (block == null) {
                finished = true;
                return false;
            }

            switch (block.type()) {
                case HEADER_TYPE_MAIN -> {
                    skipBlockData(block);
                }
                case HEADER_TYPE_FILE -> {
                    ParsedFileHeader parsedFile = parseFileHeader(block);
                    currentAttributes = parsedFile.attributes();
                    currentPartAttributes = parsedFile.attributes();
                    currentPartEncryption = parsedFile.encryptionInfo();
                    currentPackedRemaining = block.dataSize();
                    currentBodyOpened = false;
                    prepareCurrentCrc();
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

    /// Opens a readable channel for the current stored file entry.
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
            return Channels.newChannel(InputStream.nullInputStream());
        }
        if (attributes.continuesFromPreviousVolume()) {
            throw new IOException("RAR entry starts in a previous volume");
        }
        if (attributes.compressionMethod() != 0) {
            throw new IOException("Unsupported RAR compression method: " + attributes.compressionMethod());
        }
        if (attributes.isEncrypted()) {
            if (attributes.continuesInNextVolume()) {
                throw new IOException("Encrypted multi-volume RAR entries are not supported");
            }
            return openEncryptedChannel(attributes);
        }
        currentBodyOpened = true;
        return Channels.newChannel(new EntryInputStream());
    }

    /// Opens and authenticates one RAR5 AES-encrypted stored entry body.
    private ReadableByteChannel openEncryptedChannel(RarEntryAttributes attributes) throws IOException {
        Rar5EncryptionInfo encryptionInfo = currentPartEncryption;
        if (encryptionInfo == null) {
            throw new IOException("Encrypted RAR4 entries are not supported");
        }
        if (attributes.unpackedSize() == RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE) {
            throw new IOException("Encrypted RAR5 stored entry has an unknown unpacked size");
        }
        ArkivoPasswordProvider provider = passwordProvider;
        if (provider == null) {
            throw new IOException("RAR5 password provider is required for encrypted entry: " + attributes.path());
        }

        byte @Nullable [] password = provider.passwordForArchive();
        if (password == null) {
            throw new IOException("RAR5 password provider did not supply an archive password");
        }
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
                EncryptedEntryInputStream encryptedInput = new EncryptedEntryInputStream(
                        Rar5Crypto.decryptor(aesKey, encryptionInfo.initializationVector()),
                        attributes.unpackedSize()
                );
                currentEncryptedInput = encryptedInput;
                currentHashKey = encryptionInfo.tweakedChecksums() ? keys.hashKey() : null;
                currentBodyOpened = true;
                currentCrcActive = attributes.dataCrc32() != RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE;
                currentCrcVerified = false;
                currentExpectedCrc32 = attributes.dataCrc32();
                if (currentCrcActive) {
                    dataCrc32.reset();
                }
                return Channels.newChannel(encryptedInput);
            } finally {
                Rar5Crypto.clear(aesKey);
            }
        } finally {
            Rar5Crypto.clear(password);
        }
    }

    /// Closes this streaming reader.
    @Override
    public void close() throws IOException {
        if (!open && sourceClosed) {
            return;
        }
        open = false;
        currentAttributes = null;
        currentPartAttributes = null;
        currentPartEncryption = null;
        currentPackedRemaining = 0L;
        clearCurrentEncryptionState();
        Rar5Crypto.clear(archiveHeaderKey);
        archiveHeaderKey = null;
        source.close();
        sourceClosed = true;
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
                rarVersion = RAR_VERSION_5;
                signatureRead = true;
                return;
            }
            if (endsWith(window, count, RAR4_SIGNATURE)) {
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
            Rar5Crypto.clear(archiveHeaderKey);
            archiveHeaderKey = null;
        }
        if (rarVersion == RAR_VERSION_4) {
            return readRar4BlockHeader();
        }
        return readRar5BlockHeader();
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
            throw new IOException("Encrypted RAR4 headers are not supported");
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
        byte[] prefix = new byte[7];
        int first = source.read();
        if (first < 0) {
            return null;
        }
        prefix[0] = (byte) first;
        readFully(prefix, 1, prefix.length - 1, "Unexpected end of RAR4 header");

        int headerSize = littleEndianUInt16(prefix, 5);
        if (headerSize < prefix.length) {
            throw new IOException("RAR4 header is too small");
        }
        if (headerSize > MAX_HEADER_SIZE) {
            throw new IOException("RAR4 header is too large");
        }

        byte[] headerData = new byte[headerSize - Short.BYTES];
        System.arraycopy(prefix, Short.BYTES, headerData, 0, prefix.length - Short.BYTES);
        readFully(
                headerData,
                prefix.length - Short.BYTES,
                headerData.length - (prefix.length - Short.BYTES),
                "Unexpected end of RAR4 header"
        );

        headerCrc32.reset();
        headerCrc32.update(headerData, 0, headerData.length);
        if ((headerCrc32.getValue() & 0xffffL) != littleEndianUInt16(prefix, 0)) {
            throw new IOException("Invalid RAR4 header CRC16");
        }

        int rawType = Byte.toUnsignedInt(headerData[0]);
        long flags = littleEndianUInt16(headerData, 1);
        int type = rar4HeaderType(rawType);
        if (type == HEADER_TYPE_MAIN && (flags & RAR4_MAIN_FLAG_PASSWORD) != 0) {
            throw new IOException("Encrypted RAR headers are not supported");
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
        return new BlockHeader(RAR_VERSION_4, type, flags, dataSize, headerData, 5, headerData.length);
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

    /// Parses a RAR file header block.
    private static ParsedFileHeader parseFileHeader(BlockHeader block) throws IOException {
        if (block.version() == RAR_VERSION_4) {
            return new ParsedFileHeader(parseRar4FileHeader(block), null);
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
                && !symbolicLink
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
                encryptionInfo
        );
    }

    /// Returns the AES-block-aligned physical size for an encrypted stored file.
    private static long alignedEncryptedSize(long unpackedSize) throws IOException {
        long remainder = unpackedSize % Rar5Crypto.BLOCK_SIZE;
        if (remainder == 0L) {
            return unpackedSize;
        }
        long padding = Rar5Crypto.BLOCK_SIZE - remainder;
        if (unpackedSize > Long.MAX_VALUE - padding) {
            throw new IOException("RAR encrypted file size is too large");
        }
        return unpackedSize + padding;
    }

    /// Parses a RAR4 file header block.
    private static RarEntryAttributes parseRar4FileHeader(BlockHeader block) throws IOException {
        HeaderReader reader = new HeaderReader(block.headerData(), block.fieldsOffset(), block.extraOffset());
        long lowPackedSize = reader.readUInt32("RAR4 packed size");
        long lowUnpackedSize = reader.readUInt32("RAR4 unpacked size");
        int rawHostOs = reader.readUnsignedByte("RAR4 host OS");
        long dataCrc32 = reader.readUInt32("RAR4 data CRC32");
        FileTime lastModifiedTime = fileTimeFromDosTime((int) reader.readUInt32("RAR4 modification time"));
        reader.readUnsignedByte("RAR4 extraction version");
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

        String path = validatePath(decodeRar4Name(
                reader.readBytes(nameLength, "RAR4 entry name"),
                (block.flags() & RAR4_FILE_FLAG_UNICODE) != 0
        ));
        int hostOs = rar4HostOs(rawHostOs);
        int compressionMethod = rar4CompressionMethod(rawMethod);
        boolean directory = (rawMethod & RAR4_DICTIONARY_MASK) == RAR4_FILE_IS_DIRECTORY
                || rar4FileAttributesMarkDirectory(rawHostOs, fileAttributes);
        boolean encrypted = (block.flags() & RAR4_FILE_FLAG_PASSWORD) != 0;
        boolean splitFilePart =
                (block.flags() & (RAR4_FILE_FLAG_CONTINUE_PREVIOUS | RAR4_FILE_FLAG_CONTINUE_NEXT)) != 0;
        if (!directory && compressionMethod == 0 && !splitFilePart && packedSize != unpackedSize) {
            throw new IOException("RAR4 stored file packed and unpacked sizes differ");
        }

        return new RarEntryAttributes(
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
        );
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
        if (rawMethod == RAR4_METHOD_STORE || (rawMethod & RAR4_DICTIONARY_MASK) == RAR4_FILE_IS_DIRECTORY) {
            return 0;
        }
        if (rawMethod >= RAR4_METHOD_FASTEST && rawMethod <= RAR4_METHOD_BEST) {
            return rawMethod - RAR4_METHOD_STORE;
        }
        return rawMethod;
    }

    /// Returns whether RAR4 host-specific attributes mark a directory entry.
    private static boolean rar4FileAttributesMarkDirectory(int rawHostOs, long fileAttributes) {
        return switch (rawHostOs) {
            case RAR4_HOST_OS_MSDOS, RAR4_HOST_OS_OS2, RAR4_HOST_OS_WINDOWS -> (fileAttributes & 0x10L) != 0;
            case RAR4_HOST_OS_UNIX -> (fileAttributes & 0170000L) == 0040000L;
            default -> false;
        };
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
        if ((flags & FILE_TIME_MODIFIED) != 0) {
            metadata.lastModifiedTime = readFileTimeValue(reader, recordEnd, unixTime, unixNanos, "modification time");
        }
        if ((flags & FILE_TIME_CREATED) != 0) {
            metadata.creationTime = readFileTimeValue(reader, recordEnd, unixTime, unixNanos, "creation time");
        }
        if ((flags & FILE_TIME_ACCESSED) != 0) {
            metadata.lastAccessTime = readFileTimeValue(reader, recordEnd, unixTime, unixNanos, "last access time");
        }
    }

    /// Reads one RAR file time value.
    private static FileTime readFileTimeValue(
            HeaderReader reader,
            int recordEnd,
            boolean unixTime,
            boolean unixNanos,
            String description
    ) throws IOException {
        if (unixTime) {
            long seconds = reader.readUInt32("RAR " + description);
            long nanos = 0L;
            if (unixNanos) {
                if (Integer.BYTES > recordEnd - reader.position()) {
                    throw new EOFException("Unexpected end of RAR " + description + " nanoseconds");
                }
                nanos = reader.readUInt32("RAR " + description + " nanoseconds");
                if (nanos > 999_999_999L) {
                    throw new IOException("RAR " + description + " nanoseconds are out of range");
                }
            }
            return fileTimeFromEpochSecond(seconds, nanos, description);
        }
        long windowsFileTime = reader.readUInt64("RAR " + description);
        return fileTimeFromWindowsFileTime(windowsFileTime, description);
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

    /// Prepares CRC32 validation state for the current entry.
    private void prepareCurrentCrc() {
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
    }

    /// Skips any unread bytes from the current entry body.
    private void skipCurrentEntryBody() throws IOException {
        boolean requireReadableContinuation = currentBodyOpened
                && currentAttributes != null
                && !currentAttributes.isEncrypted()
                && currentAttributes.compressionMethod() == 0
                && !currentAttributes.continuesFromPreviousVolume();
        try {
            EncryptedEntryInputStream encryptedInput = currentEncryptedInput;
            if (encryptedInput != null) {
                encryptedInput.drain();
            } else {
                while (true) {
                    if (currentPackedRemaining > 0) {
                        if (currentCrcActive) {
                            skipStoredDataWithCrc(currentPackedRemaining);
                        } else {
                            skipFully(currentPackedRemaining);
                            currentPackedRemaining = 0L;
                        }
                    }
                    if (!moveToNextContinuationPartIfPresent(requireReadableContinuation)) {
                        break;
                    }
                }
            }
            verifyCurrentCrcIfComplete();
        } finally {
            clearCurrentEncryptionState();
            currentBodyOpened = false;
            currentCrcActive = false;
            currentCrcVerified = false;
            currentExpectedCrc32 = RarArkivoEntryAttributes.UNKNOWN_NUMERIC_VALUE;
        }
    }

    /// Clears password-derived state retained while an encrypted entry body is active.
    private void clearCurrentEncryptionState() {
        Rar5Crypto.clear(currentHashKey);
        currentHashKey = null;
        EncryptedEntryInputStream encryptedInput = currentEncryptedInput;
        if (encryptedInput != null) {
            encryptedInput.release();
        }
        currentEncryptedInput = null;
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
            if (block.type() == HEADER_TYPE_MAIN || block.type() == HEADER_TYPE_SERVICE || block.type() == HEADER_TYPE_END) {
                skipBlockData(block);
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
        validateContinuationPart(previousPartAttributes, nextPartAttributes, requireReadableData);
        currentPartAttributes = nextPartAttributes;
        currentPartEncryption = parsedFile.encryptionInfo();
        currentPackedRemaining = block.dataSize();
        return true;
    }

    /// Validates that a physical file part belongs to the current logical entry.
    private void validateContinuationPart(
            RarEntryAttributes previousPartAttributes,
            RarEntryAttributes nextPartAttributes,
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
        if (requireReadableData) {
            if (nextPartAttributes.isEncrypted()) {
                throw new IOException("Encrypted RAR entries are not supported");
            }
            if (!nextPartAttributes.isRegularFile()) {
                throw new IOException("RAR multi-volume entry continuation is not a regular file");
            }
            if (nextPartAttributes.compressionMethod() != 0) {
                throw new IOException("Unsupported RAR compression method: " + nextPartAttributes.compressionMethod());
            }
        }
    }

    /// Ensures that the current logical entry has physical data available to read.
    private boolean ensureCurrentEntryDataAvailable() throws IOException {
        while (currentPackedRemaining == 0L) {
            if (!moveToNextContinuationPartIfPresent(true)) {
                verifyCurrentCrcIfComplete();
                return false;
            }
        }
        return true;
    }

    /// Skips all data attached to a non-file block.
    private void skipBlockData(BlockHeader block) throws IOException {
        skipFully(block.dataSize());
    }

    /// Skips stored file bytes while updating the current CRC32.
    private void skipStoredDataWithCrc(long count) throws IOException {
        byte[] discard = new byte[8192];
        long remaining = count;
        while (remaining > 0) {
            int read = source.read(discard, 0, (int) Math.min(discard.length, remaining));
            if (read < 0) {
                throw new EOFException("Unexpected end of RAR entry data");
            }
            dataCrc32.update(discard, 0, read);
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

    /// Verifies the current stored body CRC32 when all packed bytes have been consumed.
    private void verifyCurrentCrcIfComplete() throws IOException {
        RarEntryAttributes partAttributes = currentPartAttributes;
        if (currentCrcActive
                && !currentCrcVerified
                && currentPackedRemaining == 0L
                && (partAttributes == null || !partAttributes.continuesInNextVolume())) {
            long actual = dataCrc32.getValue();
            byte[] hashKey = currentHashKey;
            if (hashKey != null) {
                actual = Rar5Crypto.keyedCrc32(actual, hashKey);
            }
            if (actual != currentExpectedCrc32) {
                throw new IOException("Invalid RAR entry CRC32");
            }
            currentCrcVerified = true;
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
        return (long) Byte.toUnsignedInt(data[offset])
                | (long) Byte.toUnsignedInt(data[offset + 1]) << 8
                | (long) Byte.toUnsignedInt(data[offset + 2]) << 16
                | (long) Byte.toUnsignedInt(data[offset + 3]) << 24;
    }

    /// Reads a little-endian unsigned 16-bit integer from a byte array.
    private static int littleEndianUInt16(byte[] data, int offset) {
        return Byte.toUnsignedInt(data[offset])
                | Byte.toUnsignedInt(data[offset + 1]) << 8;
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

    /// Stores one parsed file header together with internal encryption metadata.
    ///
    /// @param attributes the public entry attributes
    /// @param encryptionInfo the RAR5 encryption metadata, or `null` for an unencrypted or RAR4 entry
    @NotNullByDefault
    private record ParsedFileHeader(
            RarEntryAttributes attributes,
            @Nullable Rar5EncryptionInfo encryptionInfo
    ) {
        /// Creates one parsed file header result.
        private ParsedFileHeader {
            Objects.requireNonNull(attributes, "attributes");
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
    ) {
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

    /// Exposes one AES-decrypted stored body while withholding its final alignment padding.
    @NotNullByDefault
    private final class EncryptedEntryInputStream extends InputStream {
        /// The stateful AES-CBC decryptor.
        private final Rar5Crypto.CbcDecryptor decryptor;

        /// One ciphertext block read from the archive.
        private final byte[] ciphertext = new byte[Rar5Crypto.BLOCK_SIZE];

        /// One decrypted plaintext block.
        private final byte[] plaintext = new byte[Rar5Crypto.BLOCK_SIZE];

        /// A reusable buffer used while draining skipped logical data.
        private final byte[] discard = new byte[8192];

        /// A reusable buffer for single-byte reads.
        private final byte[] singleByte = new byte[1];

        /// The number of logical plaintext bytes not yet returned.
        private long logicalRemaining;

        /// The current plaintext block read position.
        private int blockPosition;

        /// The exclusive current plaintext block limit.
        private int blockLimit;

        /// Whether this entry stream still owns usable decryption state.
        private boolean entryOpen = true;

        /// Creates an encrypted stored-entry stream.
        private EncryptedEntryInputStream(Rar5Crypto.CbcDecryptor decryptor, long logicalSize) {
            this.decryptor = Objects.requireNonNull(decryptor, "decryptor");
            if (logicalSize < 0L) {
                throw new IllegalArgumentException("logicalSize must not be negative");
            }
            this.logicalRemaining = logicalSize;
        }

        /// Reads one decrypted logical byte.
        @Override
        public int read() throws IOException {
            int count = read(singleByte, 0, 1);
            return count < 0 ? -1 : Byte.toUnsignedInt(singleByte[0]);
        }

        /// Reads decrypted logical bytes without exposing AES alignment padding.
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

            int total = 0;
            while (total < length && logicalRemaining > 0L) {
                if (blockPosition == blockLimit) {
                    fillPlaintextBlock();
                }
                int count = (int) Math.min(
                        Math.min(length - total, blockLimit - blockPosition),
                        logicalRemaining
                );
                System.arraycopy(plaintext, blockPosition, buffer, offset + total, count);
                blockPosition += count;
                logicalRemaining -= count;
                total += count;
                if (currentCrcActive) {
                    dataCrc32.update(buffer, offset + total - count, count);
                }
            }
            if (logicalRemaining == 0L) {
                verifyEncryptedBodyComplete();
            }
            return total;
        }

        /// Skips decrypted logical bytes while preserving CRC validation.
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

        /// Returns logical bytes already available in the current decrypted block.
        @Override
        public int available() throws IOException {
            ensureEntryOpen();
            return (int) Math.min(logicalRemaining, blockLimit - blockPosition);
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

        /// Reads and decrypts one complete AES block from the physical entry body.
        private void fillPlaintextBlock() throws IOException {
            if (currentPackedRemaining < Rar5Crypto.BLOCK_SIZE) {
                throw new EOFException("Unexpected end of encrypted RAR5 entry data");
            }
            readFully(
                    ciphertext,
                    0,
                    ciphertext.length,
                    "Unexpected end of encrypted RAR5 entry data"
            );
            currentPackedRemaining -= ciphertext.length;
            decryptor.decryptBlock(ciphertext, plaintext);
            blockPosition = 0;
            blockLimit = (int) Math.min(Rar5Crypto.BLOCK_SIZE, logicalRemaining);
        }

        /// Requires all ciphertext bytes to be consumed and verifies the logical CRC32.
        private void verifyEncryptedBodyComplete() throws IOException {
            if (currentPackedRemaining != 0L) {
                throw new IOException("RAR5 encrypted stored entry has unexpected trailing ciphertext");
            }
            verifyCurrentCrcIfComplete();
        }

        /// Clears decrypted bytes and mutable CBC state retained by this entry stream.
        private void release() {
            if (!entryOpen) {
                return;
            }
            entryOpen = false;
            decryptor.clear();
            Arrays.fill(ciphertext, (byte) 0);
            Arrays.fill(plaintext, (byte) 0);
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
            if (currentPackedRemaining == 0L) {
                verifyCurrentCrcIfComplete();
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
            if (currentPackedRemaining == 0L) {
                verifyCurrentCrcIfComplete();
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
            if (currentCrcActive) {
                skipStoredDataWithCrc(toSkip);
                if (currentPackedRemaining == 0L) {
                    verifyCurrentCrcIfComplete();
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
