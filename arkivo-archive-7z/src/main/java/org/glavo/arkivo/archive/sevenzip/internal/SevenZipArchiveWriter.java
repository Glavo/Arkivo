// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.codec.bzip2.internal.BZip2OutputStream;
import org.glavo.arkivo.codec.deflate64.internal.Deflate64OutputStream;
import org.glavo.arkivo.codec.bcj.BCJTransforms;
import org.glavo.arkivo.codec.transform.TransformingOutputStream;
import org.glavo.arkivo.codec.transform.ByteTransform;
import org.glavo.arkivo.codec.delta.DeltaTransform;
import org.glavo.arkivo.codec.lzma.internal.Lzma2OutputStream;
import org.glavo.arkivo.codec.lzma.internal.LzmaOutputStream;
import org.glavo.arkivo.codec.lzma.internal.LzmaProperties;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.sevenzip.SevenZipCompression;
import org.glavo.arkivo.archive.sevenzip.SevenZipFilter;
import org.glavo.arkivo.archive.sevenzip.SevenZipFilterMethod;
import org.glavo.arkivo.archive.sevenzip.SevenZipFilterChain;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.attribute.FileTime;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/// Writes 7z folders, decoded substreams, and file metadata directly to a seekable archive channel.
///
/// Consecutive non-empty files with equal coder settings may share one solid folder up to the configured file-count
/// limit. Empty files and directories are represented only by file properties. The writer keeps entry and folder
/// metadata in memory but streams every packed body directly to the destination channel.
@NotNullByDefault
final class SevenZipArchiveWriter implements AutoCloseable {
    /// The fixed 7z signature bytes.
    private static final byte @Unmodifiable [] SIGNATURE =
            new byte[]{'7', 'z', (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c};

    /// The 7z format major version emitted by this writer.
    private static final int FORMAT_MAJOR_VERSION = 0;

    /// The 7z format minor version emitted by this writer.
    private static final int FORMAT_MINOR_VERSION = 4;

    /// The end property ID.
    private static final int NID_END = 0x00;

    /// The plain header property ID.
    private static final int NID_HEADER = 0x01;

    /// The main streams information property ID.
    private static final int NID_MAIN_STREAMS_INFO = 0x04;

    /// The files information property ID.
    private static final int NID_FILES_INFO = 0x05;

    /// The pack information property ID.
    private static final int NID_PACK_INFO = 0x06;

    /// The unpack information property ID.
    private static final int NID_UNPACK_INFO = 0x07;

    /// The substreams information property ID.
    private static final int NID_SUBSTREAMS_INFO = 0x08;

    /// The number-of-unpack-streams property ID.
    private static final int NID_NUM_UNPACK_STREAM = 0x0d;

    /// The packed size property ID.
    private static final int NID_SIZE = 0x09;

    /// The CRC-32 property ID.
    private static final int NID_CRC = 0x0a;

    /// The folder property ID.
    private static final int NID_FOLDER = 0x0b;

    /// The coder unpack size property ID.
    private static final int NID_CODERS_UNPACK_SIZE = 0x0c;

    /// The empty stream property ID.
    private static final int NID_EMPTY_STREAM = 0x0e;

    /// The empty file property ID.
    private static final int NID_EMPTY_FILE = 0x0f;

    /// The UTF-16LE file name property ID.
    private static final int NID_NAME = 0x11;

    /// The creation time property ID.
    private static final int NID_CREATION_TIME = 0x12;

    /// The access time property ID.
    private static final int NID_ACCESS_TIME = 0x13;

    /// The modification time property ID.
    private static final int NID_MODIFICATION_TIME = 0x14;

    /// The Windows attributes property ID.
    private static final int NID_WINDOWS_ATTRIBUTES = 0x15;

    /// The Copy coder method ID.
    private static final byte @Unmodifiable [] COPY_METHOD_ID = new byte[]{0x00};

    /// The LZMA coder method ID.
    private static final byte @Unmodifiable [] LZMA_METHOD_ID = new byte[]{0x03, 0x01, 0x01};

    /// The LZMA2 coder method ID.
    private static final byte @Unmodifiable [] LZMA2_METHOD_ID = new byte[]{0x21};

    /// The BZip2 coder method ID.
    private static final byte @Unmodifiable [] BZIP2_METHOD_ID = new byte[]{0x04, 0x02, 0x02};

    /// The raw Deflate coder method ID.
    private static final byte @Unmodifiable [] DEFLATE_METHOD_ID = new byte[]{0x04, 0x01, 0x08};

    /// The Deflate64 coder method ID.
    private static final byte @Unmodifiable [] DEFLATE64_METHOD_ID = new byte[]{0x04, 0x01, 0x09};

    /// The Delta filter method ID.
    private static final byte @Unmodifiable [] DELTA_METHOD_ID = new byte[]{0x03};

    /// The x86 BCJ filter method ID.
    private static final byte @Unmodifiable [] BCJ_X86_METHOD_ID = new byte[]{0x03, 0x03, 0x01, 0x03};

    /// The PowerPC BCJ filter method ID.
    private static final byte @Unmodifiable [] BCJ_PPC_METHOD_ID = new byte[]{0x03, 0x03, 0x02, 0x05};

    /// The IA-64 BCJ filter method ID.
    private static final byte @Unmodifiable [] BCJ_IA64_METHOD_ID = new byte[]{0x03, 0x03, 0x04, 0x01};

    /// The ARM BCJ filter method ID.
    private static final byte @Unmodifiable [] BCJ_ARM_METHOD_ID = new byte[]{0x03, 0x03, 0x05, 0x01};

    /// The ARM-Thumb BCJ filter method ID.
    private static final byte @Unmodifiable [] BCJ_ARM_THUMB_METHOD_ID = new byte[]{0x03, 0x03, 0x07, 0x01};

    /// The SPARC BCJ filter method ID.
    private static final byte @Unmodifiable [] BCJ_SPARC_METHOD_ID = new byte[]{0x03, 0x03, 0x08, 0x05};

    /// The ARM64 BCJ filter method ID.
    private static final byte @Unmodifiable [] BCJ_ARM64_METHOD_ID = new byte[]{0x0a};

    /// The RISC-V BCJ filter method ID.
    private static final byte @Unmodifiable [] BCJ_RISCV_METHOD_ID = new byte[]{0x0b};

    /// The 7z AES-256/SHA-256 coder method ID.
    private static final byte @Unmodifiable [] AES_METHOD_ID =
            new byte[]{0x06, (byte) 0xf1, 0x07, 0x01};

    /// The AES key-derivation cycle power used for entry encryption.
    private static final int AES_CYCLE_POWER = 19;

    /// The AES block and initialization-vector size.
    private static final int AES_BLOCK_SIZE = 16;

    /// The Windows FILETIME epoch offset in 100-nanosecond ticks.
    private static final long WINDOWS_EPOCH_OFFSET_TICKS = 116_444_736_000_000_000L;

    /// The number of Windows FILETIME ticks in one second.
    private static final long WINDOWS_TICKS_PER_SECOND = 10_000_000L;

    /// The largest number of consecutive zero-byte channel writes tolerated.
    private static final int MAX_ZERO_PROGRESS_ATTEMPTS = 1024;

    /// The destination archive channel owned by this writer.
    private final SeekableByteChannel channel;

    /// The default compression used when an entry has no override.
    private final SevenZipCompression defaultCompression;

    /// The default preprocessing filters in application order.
    private final SevenZipFilterChain defaultFilters;

    /// The maximum number of non-empty files encoded into one solid folder.
    private final int solidFileCount;

    /// The derived entry-encryption key, or `null` when data encryption is disabled.
    private final byte @Nullable [] encryptionKey;

    /// Metadata for every completed archive entry.
    private final List<Entry> entries = new ArrayList<>();

    /// Metadata for every completed non-empty folder.
    private final List<Folder> folders = new ArrayList<>();

    /// The entry currently accepting body bytes, or `null` between entries.
    private @Nullable PendingEntry currentEntry;

    /// The CRC-32 calculator for the current entry.
    private final CRC32 currentEntryCrc32 = new CRC32();

    /// The decoded byte count for the current entry.
    private long currentEntrySize;

    /// The coder pipeline currently accepting solid substreams.
    private @Nullable FolderEncoder currentFolderEncoder;

    /// Whether the next header and signature header have been written.
    private boolean finished;

    /// Whether the owned destination channel has closed successfully.
    private boolean channelClosed;

    /// Whether the encryption key has been cleared.
    private boolean keyCleared;

    /// Opens a native writer and reserves the fixed signature header.
    SevenZipArchiveWriter(
            SeekableByteChannel channel,
            byte @Nullable [] password,
            SevenZipCompression defaultCompression,
            SevenZipFilterChain defaultFilters,
            int solidFileCount
    ) throws IOException {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.defaultCompression = Objects.requireNonNull(defaultCompression, "defaultCompression");
        this.defaultFilters = Objects.requireNonNull(defaultFilters, "defaultFilters");
        if (solidFileCount <= 0) {
            throw new IllegalArgumentException("solidFileCount must be positive");
        }
        this.solidFileCount = solidFileCount;
        this.encryptionKey = password != null
                ? SevenZipAesCrypto.deriveKey(AES_CYCLE_POWER, new byte[0], password)
                : null;
        try {
            channel.position(0L);
            channel.truncate(0L);
            writeFully(ByteBuffer.allocate(SevenZipSignatureHeader.SIZE));
        } catch (IOException | RuntimeException | Error exception) {
            clearKey();
            throw exception;
        }
    }


    /// Begins one archive entry.
    void putArchiveEntry(String name, boolean directory, SevenZipEntryWriteMetadata metadata) throws IOException {
        ensureWritable();
        if (currentEntry != null) {
            throw new IOException("A 7z archive entry is already open");
        }
        currentEntry = new PendingEntry(
                Objects.requireNonNull(name, "name"),
                directory,
                Objects.requireNonNull(metadata, "metadata"),
                metadata.resolvedCompression(defaultCompression),
                metadata.resolvedFilters(defaultFilters)
        );
        currentEntryCrc32.reset();
        currentEntrySize = 0L;
    }


    /// Writes bytes to the current file entry.
    void write(byte[] buffer, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        ensureWritable();
        PendingEntry entry = requireCurrentEntry();
        if (entry.directory()) {
            throw new IOException("7z directory entries cannot contain data");
        }
        if (length == 0) {
            return;
        }

        FolderEncoder encoder = currentFolderEncoder;
        if (encoder == null || !encoder.accepts(entry, solidFileCount)) {
            finishCurrentFolder();
            encoder = openFolderEncoder(entry);
            currentFolderEncoder = encoder;
        }
        encoder.write(buffer, offset, length);
        currentEntryCrc32.update(buffer, offset, length);
        try {
            currentEntrySize = Math.addExact(currentEntrySize, length);
        } catch (ArithmeticException exception) {
            throw new IOException("7z entry size is too large", exception);
        }
    }


    /// Completes the current entry and records its substream metadata when it contains data.
    void closeArchiveEntry() throws IOException {
        ensureWritable();
        PendingEntry entry = requireCurrentEntry();
        if (currentEntrySize == 0L) {
            entries.add(Entry.empty(entry));
        } else {
            FolderEncoder encoder = Objects.requireNonNull(
                    currentFolderEncoder,
                    "currentFolderEncoder"
            );
            encoder.completeSubstream(currentEntrySize, currentEntryCrc32.getValue());
            entries.add(Entry.stream(entry, currentEntrySize, currentEntryCrc32.getValue()));
        }
        currentEntry = null;
        currentEntryCrc32.reset();
        currentEntrySize = 0L;
    }


    /// Finalizes the archive and closes the owned channel.
    @Override
    public void close() throws IOException {
        @Nullable Throwable failure = null;
        if (!finished) {
            try {
                if (currentEntry != null) {
                    throw new IOException("Cannot close a 7z writer while an entry remains open");
                }
                finishArchive();
                finished = true;
            } catch (IOException | RuntimeException | Error exception) {
                failure = exception;
            } finally {
                clearKey();
            }
        } else {
            clearKey();
        }
        if (!channelClosed) {
            try {
                channel.close();
                channelClosed = true;
            } catch (IOException | RuntimeException | Error exception) {
                failure = appendFailure(failure, exception);
            }
        }
        throwFailure(failure);
    }


    /// Closes and records the current solid folder when one is open.
    private void finishCurrentFolder() throws IOException {
        FolderEncoder encoder = currentFolderEncoder;
        if (encoder == null) {
            return;
        }
        encoder.close();
        folders.add(encoder.completedFolder());
        currentFolderEncoder = null;
    }


    /// Opens the configured coder chain for the first non-empty substream of a folder.
    private FolderEncoder openFolderEncoder(PendingEntry entry) throws IOException {
        PackedOutputStream packedOutput = new PackedOutputStream(channel);
        @Nullable AesOutputStream aesOutput = null;
        @Nullable CountingOutputStream encryptedInput = null;
        OutputStream packedTarget = packedOutput;
        if (encryptionKey != null) {
            aesOutput = new AesOutputStream(packedOutput, encryptionKey);
            encryptedInput = new CountingOutputStream(aesOutput);
            packedTarget = encryptedInput;
        }

        CompressionOutput compression = openCompression(entry.compression(), packedTarget);
        OutputStream output = compression.output();
        List<SevenZipFilter> configuredFilters = entry.filters().filters();
        ArrayList<FilterDescriptor> filters = new ArrayList<>(configuredFilters.size());
        for (int index = configuredFilters.size() - 1; index >= 0; index--) {
            FilterDescriptor filter = filterDescriptor(configuredFilters.get(index));
            filters.add(filter);
            output = new TransformingOutputStream(output, filter.transform());
        }
        return new FolderEncoder(
                output,
                packedOutput,
                encryptedInput,
                aesOutput,
                compression.descriptor(),
                List.copyOf(filters),
                entry.compression(),
                entry.filters()
        );
    }

    /// Opens one configured compression encoder and returns its 7z method descriptor.
    private static CompressionOutput openCompression(
            SevenZipCompression compression,
            OutputStream output
    ) throws IOException {
        return switch (compression.method()) {
            case COPY -> new CompressionOutput(
                    output,
                    new MethodDescriptor(COPY_METHOD_ID, new byte[0])
            );
            case LZMA -> openLzma(compression.parameter(), output);
            case LZMA2 -> openLzma2(compression.parameter(), output);
            case BZIP2 -> new CompressionOutput(
                    new BZip2OutputStream(output, compression.parameter()),
                    new MethodDescriptor(BZIP2_METHOD_ID, new byte[0])
            );
            case DEFLATE -> new CompressionOutput(
                    new RawDeflateOutputStream(output, compression.parameter()),
                    new MethodDescriptor(DEFLATE_METHOD_ID, new byte[0])
            );
            case DEFLATE64 -> new CompressionOutput(
                    new Deflate64OutputStream(output, compression.parameter()),
                    new MethodDescriptor(DEFLATE64_METHOD_ID, new byte[0])
            );
        };
    }


    /// Opens a raw LZMA encoder with explicit 7z coder properties.
    private static CompressionOutput openLzma(int dictionarySize, OutputStream output) throws IOException {
        LzmaProperties lzmaProperties = LzmaProperties.defaults(dictionarySize);
        byte[] properties = new byte[5];
        properties[0] = (byte) lzmaProperties.propertyByte();
        ByteBuffer.wrap(properties, 1, Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(dictionarySize);
        return new CompressionOutput(
                new LzmaOutputStream(output, lzmaProperties, false),
                new MethodDescriptor(LZMA_METHOD_ID, properties)
        );
    }


    /// Opens a raw LZMA2 encoder with its compact dictionary property.
    private static CompressionOutput openLzma2(int dictionarySize, OutputStream output) throws IOException {
        return new CompressionOutput(
                new Lzma2OutputStream(output, dictionarySize),
                new MethodDescriptor(LZMA2_METHOD_ID, new byte[]{lzma2Property(dictionarySize)})
        );
    }


    /// Returns the smallest LZMA2 dictionary property that covers the configured size.
    private static byte lzma2Property(int dictionarySize) throws IOException {
        for (int property = 0; property <= 40; property++) {
            long represented = property == 40
                    ? 0xffff_ffffL
                    : (long) (2 | (property & 1)) << ((property >>> 1) + 11);
            if (represented >= dictionarySize) {
                return (byte) property;
            }
        }
        throw new IOException("Unsupported 7z LZMA2 dictionary size: " + dictionarySize);
    }


    /// Returns the native encoder transform and method metadata for one preprocessing filter.
    private static FilterDescriptor filterDescriptor(SevenZipFilter filter) {
        if (filter.method() == SevenZipFilterMethod.DELTA) {
            int distance = Math.toIntExact(filter.parameter());
            return new FilterDescriptor(
                    new DeltaTransform(true, distance),
                    new MethodDescriptor(DELTA_METHOD_ID, new byte[]{(byte) (distance - 1)})
            );
        }

        int startOffset = (int) filter.parameter();
        byte[] properties = bcjProperties(filter.parameter());
        return switch (filter.method()) {
            case DELTA -> throw new AssertionError("Delta filter was handled before BCJ dispatch");
            case BCJ_X86 -> new FilterDescriptor(
                    BCJTransforms.x86(true, startOffset),
                    new MethodDescriptor(BCJ_X86_METHOD_ID, properties)
            );
            case BCJ_PPC -> new FilterDescriptor(
                    BCJTransforms.powerPc(true, startOffset),
                    new MethodDescriptor(BCJ_PPC_METHOD_ID, properties)
            );
            case BCJ_IA64 -> new FilterDescriptor(
                    BCJTransforms.ia64(true, startOffset),
                    new MethodDescriptor(BCJ_IA64_METHOD_ID, properties)
            );
            case BCJ_ARM -> new FilterDescriptor(
                    BCJTransforms.arm(true, startOffset),
                    new MethodDescriptor(BCJ_ARM_METHOD_ID, properties)
            );
            case BCJ_ARM_THUMB -> new FilterDescriptor(
                    BCJTransforms.armThumb(true, startOffset),
                    new MethodDescriptor(BCJ_ARM_THUMB_METHOD_ID, properties)
            );
            case BCJ_SPARC -> new FilterDescriptor(
                    BCJTransforms.sparc(true, startOffset),
                    new MethodDescriptor(BCJ_SPARC_METHOD_ID, properties)
            );
            case BCJ_ARM64 -> new FilterDescriptor(
                    BCJTransforms.arm64(true, startOffset),
                    new MethodDescriptor(BCJ_ARM64_METHOD_ID, properties)
            );
            case BCJ_RISCV -> new FilterDescriptor(
                    BCJTransforms.riscV(true, startOffset),
                    new MethodDescriptor(BCJ_RISCV_METHOD_ID, properties)
            );
        };
    }


    /// Serializes an optional unsigned 32-bit BCJ start offset.
    static byte[] bcjProperties(long startOffset) {
        if (startOffset < 0 || startOffset > SevenZipFilter.MAX_BCJ_START_OFFSET) {
            throw new IllegalArgumentException("startOffset must be an unsigned 32-bit value");
        }
        if (startOffset == 0) {
            return new byte[0];
        }
        return ByteBuffer.allocate(Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt((int) startOffset)
                .array();
    }


    /// Writes the next header and then replaces the reserved signature header.
    private void finishArchive() throws IOException {
        finishCurrentFolder();
        byte[] nextHeader = nextHeader();
        long nextHeaderOffset = channel.position() - SevenZipSignatureHeader.SIZE;
        writeFully(ByteBuffer.wrap(nextHeader));
        channel.truncate(channel.position());
        byte[] signatureHeader = signatureHeader(nextHeaderOffset, nextHeader);
        channel.position(0L);
        writeFully(ByteBuffer.wrap(signatureHeader));
    }


    /// Serializes the complete plain next header.
    private byte[] nextHeader() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(NID_HEADER);
        if (!folders.isEmpty()) {
            output.write(NID_MAIN_STREAMS_INFO);
            writeMainStreamsInfo(output, folders);
        }
        if (!entries.isEmpty()) {
            output.write(NID_FILES_INFO);
            writeFilesInfo(output);
        }
        output.write(NID_END);
        return output.toByteArray();
    }


    /// Writes pack, folder, and substream metadata for every non-empty folder.
    private static void writeMainStreamsInfo(ByteArrayOutputStream output, List<Folder> folders) {
        output.write(NID_PACK_INFO);
        writeUint64(output, 0L);
        writeUint64(output, folders.size());
        output.write(NID_SIZE);
        for (Folder folder : folders) {
            writeUint64(output, folder.packedSize());
        }
        output.write(NID_CRC);
        output.write(1);
        for (Folder folder : folders) {
            writeIntLittleEndian(output, folder.packedCrc32());
        }
        output.write(NID_END);

        output.write(NID_UNPACK_INFO);
        output.write(NID_FOLDER);
        writeUint64(output, folders.size());
        output.write(0);
        for (Folder folder : folders) {
            writeFolder(output, folder.coders());
        }
        output.write(NID_CODERS_UNPACK_SIZE);
        for (Folder folder : folders) {
            for (Coder coder : folder.coders()) {
                writeUint64(output, coder.unpackSize());
            }
        }
        output.write(NID_CRC);
        output.write(1);
        for (Folder folder : folders) {
            writeIntLittleEndian(output, folder.crc32());
        }
        output.write(NID_END);

        output.write(NID_SUBSTREAMS_INFO);
        boolean hasSolidFolder = folders.stream().anyMatch(folder -> folder.substreams().size() != 1);
        if (hasSolidFolder) {
            output.write(NID_NUM_UNPACK_STREAM);
            for (Folder folder : folders) {
                writeUint64(output, folder.substreams().size());
            }

            output.write(NID_SIZE);
            for (Folder folder : folders) {
                List<Substream> substreams = folder.substreams();
                for (int index = 0; index + 1 < substreams.size(); index++) {
                    writeUint64(output, substreams.get(index).size());
                }
            }

            output.write(NID_CRC);
            output.write(1);
            for (Folder folder : folders) {
                if (folder.substreams().size() != 1) {
                    for (Substream substream : folder.substreams()) {
                        writeIntLittleEndian(output, substream.crc32());
                    }
                }
            }
        }
        output.write(NID_END);
        output.write(NID_END);
    }

    /// Writes one linear folder definition in packed-to-decoded coder order.
    private static void writeFolder(ByteArrayOutputStream output, List<Coder> coders) {
        writeUint64(output, coders.size());
        for (Coder coder : coders) {
            int flags = coder.methodId().length;
            if (coder.properties().length != 0) {
                flags |= 0x20;
            }
            output.write(flags);
            output.writeBytes(coder.methodId());
            if (coder.properties().length != 0) {
                writeUint64(output, coder.properties().length);
                output.writeBytes(coder.properties());
            }
        }
        for (int coderIndex = 1; coderIndex < coders.size(); coderIndex++) {
            writeUint64(output, coderIndex);
            writeUint64(output, coderIndex - 1L);
        }
    }


    /// Writes names, empty-stream flags, timestamps, and Windows attributes.
    private void writeFilesInfo(ByteArrayOutputStream output) {
        writeUint64(output, entries.size());
        writeNames(output);

        boolean[] emptyStreams = new boolean[entries.size()];
        int emptyStreamCount = 0;
        for (int index = 0; index < entries.size(); index++) {
            emptyStreams[index] = !entries.get(index).hasStream();
            if (emptyStreams[index]) {
                emptyStreamCount++;
            }
        }
        if (emptyStreamCount != 0) {
            writeBooleanProperty(output, NID_EMPTY_STREAM, emptyStreams);
            boolean[] emptyFiles = new boolean[emptyStreamCount];
            int emptyIndex = 0;
            boolean anyEmptyFile = false;
            for (int index = 0; index < entries.size(); index++) {
                if (emptyStreams[index]) {
                    emptyFiles[emptyIndex] = !entries.get(index).directory();
                    anyEmptyFile |= emptyFiles[emptyIndex];
                    emptyIndex++;
                }
            }
            if (anyEmptyFile) {
                writeBooleanProperty(output, NID_EMPTY_FILE, emptyFiles);
            }
        }

        writeTimeProperty(output, NID_CREATION_TIME, entries.stream().map(Entry::creationTime).toList());
        writeTimeProperty(output, NID_ACCESS_TIME, entries.stream().map(Entry::lastAccessTime).toList());
        writeTimeProperty(output, NID_MODIFICATION_TIME, entries.stream().map(Entry::lastModifiedTime).toList());
        writeWindowsAttributesProperty(output);
        output.write(NID_END);
    }


    /// Writes all entry names as one internal UTF-16LE property.
    private void writeNames(ByteArrayOutputStream output) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(0);
        for (Entry entry : entries) {
            for (int index = 0; index < entry.name().length(); index++) {
                char value = entry.name().charAt(index);
                data.write(value);
                data.write(value >>> 8);
            }
            data.write(0);
            data.write(0);
        }
        writeProperty(output, NID_NAME, data.toByteArray());
    }


    /// Writes one boolean-vector property.
    private static void writeBooleanProperty(ByteArrayOutputStream output, int property, boolean[] values) {
        writeProperty(output, property, booleanVector(values));
    }


    /// Writes a nullable file-time property when at least one entry defines it.
    private static void writeTimeProperty(
            ByteArrayOutputStream output,
            int property,
            List<@Nullable FileTime> values
    ) {
        boolean[] defined = new boolean[values.size()];
        boolean anyDefined = false;
        for (int index = 0; index < values.size(); index++) {
            defined[index] = values.get(index) != null;
            anyDefined |= defined[index];
        }
        if (!anyDefined) {
            return;
        }

        ByteArrayOutputStream data = new ByteArrayOutputStream();
        writeDefinedVector(data, defined);
        data.write(0);
        for (FileTime value : values) {
            if (value != null) {
                writeLongLittleEndian(data, windowsTicks(value));
            }
        }
        writeProperty(output, property, data.toByteArray());
    }


    /// Writes defined Windows attributes for archive entries.
    private void writeWindowsAttributesProperty(ByteArrayOutputStream output) {
        boolean[] defined = new boolean[entries.size()];
        boolean anyDefined = false;
        for (int index = 0; index < entries.size(); index++) {
            defined[index] =
                    entries.get(index).windowsAttributes() != SevenZipArkivoEntryAttributes.UNKNOWN_WINDOWS_ATTRIBUTES;
            anyDefined |= defined[index];
        }
        if (!anyDefined) {
            return;
        }

        ByteArrayOutputStream data = new ByteArrayOutputStream();
        writeDefinedVector(data, defined);
        data.write(0);
        for (int index = 0; index < entries.size(); index++) {
            if (defined[index]) {
                writeIntLittleEndian(data, entries.get(index).windowsAttributes());
            }
        }
        writeProperty(output, NID_WINDOWS_ATTRIBUTES, data.toByteArray());
    }


    /// Writes an all-defined marker or an explicit definition bit vector.
    private static void writeDefinedVector(ByteArrayOutputStream output, boolean[] defined) {
        boolean allDefined = true;
        for (boolean value : defined) {
            allDefined &= value;
        }
        output.write(allDefined ? 1 : 0);
        if (!allDefined) {
            output.writeBytes(booleanVector(defined));
        }
    }


    /// Serializes booleans from the most significant bit of each byte.
    private static byte[] booleanVector(boolean[] values) {
        byte[] result = new byte[(values.length + 7) / 8];
        for (int index = 0; index < values.length; index++) {
            if (values[index]) {
                result[index >>> 3] |= (byte) (0x80 >>> (index & 7));
            }
        }
        return result;
    }


    /// Writes one sized property.
    private static void writeProperty(ByteArrayOutputStream output, int property, byte[] data) {
        output.write(property);
        writeUint64(output, data.length);
        output.writeBytes(data);
    }


    /// Builds the fixed signature header for a completed next header.
    private static byte[] signatureHeader(long nextHeaderOffset, byte[] nextHeader) {
        ByteBuffer buffer = ByteBuffer.allocate(SevenZipSignatureHeader.SIZE).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(SIGNATURE);
        buffer.put((byte) FORMAT_MAJOR_VERSION);
        buffer.put((byte) FORMAT_MINOR_VERSION);
        buffer.putInt(0);
        buffer.putLong(nextHeaderOffset);
        buffer.putLong(nextHeader.length);
        buffer.putInt((int) crc32(nextHeader));
        CRC32 startHeaderCrc = new CRC32();
        startHeaderCrc.update(buffer.array(), 12, 20);
        buffer.putInt(8, (int) startHeaderCrc.getValue());
        return buffer.array();
    }


    /// Writes one non-negative 7z variable-length unsigned integer.
    private static void writeUint64(ByteArrayOutputStream output, long value) {
        if (value < 0L) {
            throw new IllegalArgumentException("7z unsigned integer must not be negative");
        }
        int firstByte = 0;
        int mask = 0x80;
        int additionalByteCount;
        for (additionalByteCount = 0; additionalByteCount < 8; additionalByteCount++) {
            if (value < 1L << 7 * (additionalByteCount + 1)) {
                firstByte |= (int) (value >>> 8 * additionalByteCount);
                break;
            }
            firstByte |= mask;
            mask >>>= 1;
        }
        output.write(firstByte);
        for (; additionalByteCount > 0; additionalByteCount--) {
            output.write((int) value);
            value >>>= 8;
        }
    }


    /// Writes a little-endian 32-bit value.
    private static void writeIntLittleEndian(ByteArrayOutputStream output, long value) {
        output.write((int) value);
        output.write((int) (value >>> 8));
        output.write((int) (value >>> 16));
        output.write((int) (value >>> 24));
    }


    /// Writes a little-endian 64-bit value.
    private static void writeLongLittleEndian(ByteArrayOutputStream output, long value) {
        writeIntLittleEndian(output, value);
        writeIntLittleEndian(output, value >>> 32);
    }


    /// Converts a Java file time to Windows FILETIME ticks.
    private static long windowsTicks(FileTime time) {
        Instant instant = time.toInstant();
        return WINDOWS_EPOCH_OFFSET_TICKS
                + instant.getEpochSecond() * WINDOWS_TICKS_PER_SECOND
                + instant.getNano() / 100L;
    }


    /// Returns the unsigned CRC-32 of one byte array.
    private static long crc32(byte[] bytes) {
        CRC32 crc32 = new CRC32();
        crc32.update(bytes);
        return crc32.getValue();
    }


    /// Writes an entire buffer to the archive channel.
    private void writeFully(ByteBuffer source) throws IOException {
        int zeroProgressCount = 0;
        while (source.hasRemaining()) {
            if (channel.write(source) == 0) {
                zeroProgressCount++;
                if (zeroProgressCount >= MAX_ZERO_PROGRESS_ATTEMPTS) {
                    throw new IOException("7z archive write made no progress");
                }
                Thread.onSpinWait();
            } else {
                zeroProgressCount = 0;
            }
        }
    }


    /// Returns the current entry or fails when no entry is open.
    private PendingEntry requireCurrentEntry() throws IOException {
        PendingEntry entry = currentEntry;
        if (entry == null) {
            throw new IOException("No 7z archive entry is open");
        }
        return entry;
    }


    /// Requires this writer to remain available for entry output.
    private void ensureWritable() throws ClosedChannelException {
        if (finished || channelClosed) {
            throw new ClosedChannelException();
        }
    }


    /// Clears the derived encryption key once.
    private void clearKey() {
        if (keyCleared) {
            return;
        }
        if (encryptionKey != null) {
            Arrays.fill(encryptionKey, (byte) 0);
        }
        keyCleared = true;
    }


    /// Adds a secondary failure as suppressed when a primary failure already exists.
    private static Throwable appendFailure(@Nullable Throwable failure, Throwable exception) {
        if (failure == null) {
            return exception;
        }
        if (failure != exception) {
            failure.addSuppressed(exception);
        }
        return failure;
    }


    /// Throws an accumulated failure with its original category.
    private static void throwFailure(@Nullable Throwable failure) throws IOException {
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error exception) {
            throw exception;
        }
    }


    /// Stores one pending entry and its resolved output settings.
    ///
    /// @param name        the archive-relative entry name
    /// @param directory   whether the entry is a directory
    /// @param metadata    the persistent entry metadata
    /// @param compression the resolved compression setting
    /// @param filters     the resolved preprocessing filters in application order
    @NotNullByDefault
    private record PendingEntry(
            String name,
            boolean directory,
            SevenZipEntryWriteMetadata metadata,
            SevenZipCompression compression,
            SevenZipFilterChain filters
    ) {
        /// Validates one pending entry.
        private PendingEntry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(compression, "compression");
            Objects.requireNonNull(filters, "filters");
        }
    }


    /// Stores one completed file-system entry and its decoded metadata.
    ///
    /// @param name              the archive-relative entry name
    /// @param directory         whether the entry is a directory
    /// @param size              the decoded entry size
    /// @param crc32             the decoded entry CRC-32
    /// @param lastModifiedTime  the modification time, or `null`
    /// @param lastAccessTime    the access time, or `null`
    /// @param creationTime      the creation time, or `null`
    /// @param windowsAttributes the Windows attributes, or the unknown sentinel
    @NotNullByDefault
    private record Entry(
            String name,
            boolean directory,
            long size,
            long crc32,
            @Nullable FileTime lastModifiedTime,
            @Nullable FileTime lastAccessTime,
            @Nullable FileTime creationTime,
            int windowsAttributes
    ) {
        /// Creates one entry without a packed substream.
        private static Entry empty(PendingEntry entry) {
            return create(entry, 0L, 0L);
        }


        /// Creates one non-empty entry backed by a folder substream.
        private static Entry stream(PendingEntry entry, long size, long crc32) {
            if (size <= 0L) {
                throw new IllegalArgumentException("stream entry size must be positive");
            }
            return create(entry, size, crc32);
        }


        /// Creates one entry from pending metadata.
        private static Entry create(PendingEntry entry, long size, long crc32) {
            SevenZipEntryWriteMetadata metadata = entry.metadata();
            return new Entry(
                    entry.name(),
                    entry.directory(),
                    size,
                    crc32,
                    metadata.lastModifiedTime(),
                    metadata.lastAccessTime(),
                    metadata.creationTime(),
                    metadata.windowsAttributes()
            );
        }


        /// Returns whether this entry owns one decoded folder substream.
        private boolean hasStream() {
            return size != 0L;
        }
    }


    /// Stores one decoded file substream within a folder.
    ///
    /// @param size  the decoded substream size
    /// @param crc32 the decoded substream CRC-32
    @NotNullByDefault
    private record Substream(long size, long crc32) {
        /// Validates one decoded substream.
        private Substream {
            if (size <= 0L) {
                throw new IllegalArgumentException("substream size must be positive");
            }
        }
    }


    /// Stores one completed packed stream, coder chain, and its decoded substreams.
    ///
    /// @param size        the total decoded folder size
    /// @param crc32       the CRC-32 of the concatenated decoded folder bytes
    /// @param packedSize  the packed stream size
    /// @param packedCrc32 the packed stream CRC-32
    /// @param coders      the packed-to-decoded coder sequence
    /// @param substreams  the decoded file substreams in archive order
    @NotNullByDefault
    private record Folder(
            long size,
            long crc32,
            long packedSize,
            long packedCrc32,
            @Unmodifiable List<Coder> coders,
            @Unmodifiable List<Substream> substreams
    ) {
        /// Creates immutable validated folder metadata.
        private Folder {
            if (size <= 0L) {
                throw new IllegalArgumentException("folder size must be positive");
            }
            if (packedSize < 0L) {
                throw new IllegalArgumentException("packedSize must be non-negative");
            }
            coders = List.copyOf(coders);
            substreams = List.copyOf(substreams);
            if (coders.isEmpty()) {
                throw new IllegalArgumentException("folder must contain at least one coder");
            }
            if (substreams.isEmpty()) {
                throw new IllegalArgumentException("folder must contain at least one substream");
            }
            long decodedSize = 0L;
            for (Substream substream : substreams) {
                decodedSize = Math.addExact(decodedSize, substream.size());
            }
            if (decodedSize != size) {
                throw new IllegalArgumentException("folder size does not match its substreams");
            }
            if (coders.get(coders.size() - 1).unpackSize() != size) {
                throw new IllegalArgumentException("final coder size does not match the folder size");
            }
        }
    }

    /// Stores one decoder-facing coder descriptor.
    ///
    /// @param methodId   the 7z method identifier
    /// @param properties the serialized coder properties
    /// @param unpackSize the coder output size
    @NotNullByDefault
    private record Coder(
            byte @Unmodifiable [] methodId,
            byte @Unmodifiable [] properties,
            long unpackSize
    ) {
        /// Creates one immutable coder descriptor.
        private Coder {
            methodId = methodId.clone();
            properties = properties.clone();
            if (unpackSize < 0L) {
                throw new IllegalArgumentException("unpackSize must be non-negative");
            }
        }


        /// Returns a defensive method-ID copy.
        @Override
        public byte[] methodId() {
            return methodId.clone();
        }


        /// Returns a defensive properties copy.
        @Override
        public byte[] properties() {
            return properties.clone();
        }
    }


    /// Stores method identity and properties before the unpack size is known.
    ///
    /// @param methodId   the 7z method identifier
    /// @param properties the serialized coder properties
    @NotNullByDefault
    private record MethodDescriptor(
            byte @Unmodifiable [] methodId,
            byte @Unmodifiable [] properties
    ) {
        /// Creates one immutable method descriptor.
        private MethodDescriptor {
            methodId = methodId.clone();
            properties = properties.clone();
        }


        /// Creates a sized coder descriptor.
        private Coder coder(long unpackSize) {
            return new Coder(methodId, properties, unpackSize);
        }
    }


    /// Stores an opened compression stream and its method descriptor.
    ///
    /// @param output     the compression output stream
    /// @param descriptor the 7z compression method descriptor
    @NotNullByDefault
    private record CompressionOutput(OutputStream output, MethodDescriptor descriptor) {
        /// Validates the opened compression output.
        private CompressionOutput {
            Objects.requireNonNull(output, "output");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }


    /// Stores one native filter transform and its 7z method descriptor.
    ///
    /// @param transform  the stateful native filter transform
    /// @param descriptor the 7z filter method descriptor
    @NotNullByDefault
    private record FilterDescriptor(ByteTransform transform, MethodDescriptor descriptor) {
        /// Validates one filter descriptor.
        private FilterDescriptor {
            Objects.requireNonNull(transform, "transform");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }


    /// Streams consecutive file substreams through one filter, compression, and optional AES pipeline.
    @NotNullByDefault
    private static final class FolderEncoder {
        /// The outermost stream that accepts decoded folder bytes.
        private final OutputStream output;

        /// The packed stream counter and CRC calculator.
        private final PackedOutputStream packedOutput;

        /// The counter before AES padding, or `null` for unencrypted output.
        private final @Nullable CountingOutputStream encryptedInput;

        /// The AES output and coder properties, or `null` for unencrypted output.
        private final @Nullable AesOutputStream aesOutput;

        /// The compression method descriptor.
        private final MethodDescriptor compression;

        /// The preprocessing filter descriptors in packed-to-decoded coder order.
        private final @Unmodifiable List<FilterDescriptor> filters;

        /// The resolved compression setting shared by this folder.
        private final SevenZipCompression compressionSetting;

        /// The resolved filter chain shared by this folder.
        private final SevenZipFilterChain filterSetting;

        /// Metadata for completed decoded substreams.
        private final List<Substream> substreams = new ArrayList<>();

        /// The CRC-32 of all concatenated decoded substreams.
        private final CRC32 crc32 = new CRC32();

        /// The total decoded folder size.
        private long size;

        /// Whether the encoder chain has closed successfully.
        private boolean closed;

        /// Creates one active folder encoder.
        private FolderEncoder(
                OutputStream output,
                PackedOutputStream packedOutput,
                @Nullable CountingOutputStream encryptedInput,
                @Nullable AesOutputStream aesOutput,
                MethodDescriptor compression,
                List<FilterDescriptor> filters,
                SevenZipCompression compressionSetting,
                SevenZipFilterChain filterSetting
        ) {
            this.output = Objects.requireNonNull(output, "output");
            this.packedOutput = Objects.requireNonNull(packedOutput, "packedOutput");
            this.encryptedInput = encryptedInput;
            this.aesOutput = aesOutput;
            this.compression = Objects.requireNonNull(compression, "compression");
            this.filters = List.copyOf(filters);
            this.compressionSetting = Objects.requireNonNull(compressionSetting, "compressionSetting");
            this.filterSetting = Objects.requireNonNull(filterSetting, "filterSetting");
        }


        /// Returns whether another entry can share this folder.
        private boolean accepts(PendingEntry entry, int maximumSubstreams) {
            return !closed
                    && substreams.size() < maximumSubstreams
                    && compressionSetting.equals(entry.compression())
                    && filterSetting.equals(entry.filters());
        }


        /// Writes decoded folder bytes and updates the aggregate digest.
        private void write(byte[] buffer, int offset, int length) throws IOException {
            output.write(buffer, offset, length);
            crc32.update(buffer, offset, length);
            try {
                size = Math.addExact(size, length);
            } catch (ArithmeticException exception) {
                throw new IOException("7z folder size is too large", exception);
            }
        }


        /// Records one completed non-empty file substream.
        private void completeSubstream(long substreamSize, long substreamCrc32) {
            if (closed) {
                throw new IllegalStateException("7z folder encoder is closed");
            }
            substreams.add(new Substream(substreamSize, substreamCrc32));
        }


        /// Finishes every encoder in the pipeline.
        private void close() throws IOException {
            if (closed) {
                return;
            }
            if (substreams.isEmpty()) {
                throw new IOException("Cannot close a 7z folder without substreams");
            }
            output.close();
            closed = true;
        }


        /// Creates completed folder metadata after the pipeline has closed.
        private Folder completedFolder() {
            if (!closed) {
                throw new IllegalStateException("7z folder encoder is not closed");
            }
            ArrayList<Coder> coders = new ArrayList<>(2 + filters.size());
            if (aesOutput != null) {
                coders.add(new MethodDescriptor(AES_METHOD_ID, aesOutput.properties())
                        .coder(Objects.requireNonNull(encryptedInput, "encryptedInput").count()));
            }
            coders.add(compression.coder(size));
            for (FilterDescriptor filter : filters) {
                coders.add(filter.descriptor().coder(size));
            }
            return new Folder(
                    size,
                    crc32.getValue(),
                    packedOutput.count(),
                    packedOutput.crc32(),
                    List.copyOf(coders),
                    List.copyOf(substreams)
            );
        }
    }

    /// Writes packed bytes to the archive channel while counting bytes and CRC-32.
    @NotNullByDefault
    private static final class PackedOutputStream extends OutputStream {
        /// The shared archive channel.
        private final SeekableByteChannel channel;

        /// The packed stream CRC-32.
        private final CRC32 crc32 = new CRC32();

        /// The number of packed bytes written.
        private long count;

        /// Whether this logical stream has closed.
        private boolean closed;

        /// Creates a packed output over the current channel position.
        private PackedOutputStream(SeekableByteChannel channel) {
            this.channel = Objects.requireNonNull(channel, "channel");
        }


        /// Writes one packed byte.
        @Override
        public void write(int value) throws IOException {
            byte[] single = {(byte) value};
            write(single, 0, 1);
        }


        /// Writes packed bytes.
        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (closed) {
                throw new ClosedChannelException();
            }
            ByteBuffer source = ByteBuffer.wrap(buffer, offset, length);
            int zeroProgressCount = 0;
            while (source.hasRemaining()) {
                int before = source.position();
                int written = channel.write(source);
                if (written == 0) {
                    zeroProgressCount++;
                    if (zeroProgressCount >= MAX_ZERO_PROGRESS_ATTEMPTS) {
                        throw new IOException("7z packed stream write made no progress");
                    }
                    Thread.onSpinWait();
                } else {
                    zeroProgressCount = 0;
                    crc32.update(buffer, before, written);
                    try {
                        count = Math.addExact(count, written);
                    } catch (ArithmeticException exception) {
                        throw new IOException("7z packed stream is too large", exception);
                    }
                }
            }
        }


        /// Closes this logical stream without closing the shared archive channel.
        @Override
        public void close() {
            closed = true;
        }


        /// Returns the packed byte count.
        private long count() {
            return count;
        }


        /// Returns the packed CRC-32.
        private long crc32() {
            return crc32.getValue();
        }
    }


    /// Counts bytes entering another output stream.
    @NotNullByDefault
    private static final class CountingOutputStream extends OutputStream {
        /// The downstream output.
        private final OutputStream output;

        /// The number of bytes accepted.
        private long count;

        /// Creates a counting output.
        private CountingOutputStream(OutputStream output) {
            this.output = Objects.requireNonNull(output, "output");
        }


        /// Writes one byte and increments the count.
        @Override
        public void write(int value) throws IOException {
            output.write(value);
            increment(1);
        }


        /// Writes bytes and increments the count.
        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            output.write(buffer, offset, length);
            increment(length);
        }


        /// Flushes the downstream output.
        @Override
        public void flush() throws IOException {
            output.flush();
        }


        /// Closes the downstream output.
        @Override
        public void close() throws IOException {
            output.close();
        }


        /// Adds a non-negative byte count.
        private void increment(int amount) throws IOException {
            try {
                count = Math.addExact(count, amount);
            } catch (ArithmeticException exception) {
                throw new IOException("7z coder output is too large", exception);
            }
        }


        /// Returns the accepted byte count.
        private long count() {
            return count;
        }
    }


    /// Encrypts bytes with 7z AES and appends zero padding to a complete AES block.
    @NotNullByDefault
    private static final class AesOutputStream extends OutputStream {
        /// The encrypted packed output.
        private final OutputStream output;

        /// The initialized AES cipher.
        private final Cipher cipher;

        /// The serialized 7z AES coder properties.
        private final byte @Unmodifiable [] properties;

        /// The number of unpadded input bytes.
        private long inputSize;

        /// Whether this output has closed.
        private boolean closed;

        /// Creates an AES output with a fresh initialization vector.
        private AesOutputStream(OutputStream output, byte @Unmodifiable [] key) throws IOException {
            this.output = Objects.requireNonNull(output, "output");
            byte[] initializationVector = new byte[AES_BLOCK_SIZE];
            new SecureRandom().nextBytes(initializationVector);
            this.properties = aesProperties(initializationVector);
            try {
                cipher = Cipher.getInstance("AES/CBC/NoPadding");
                cipher.init(
                        Cipher.ENCRYPT_MODE,
                        new SecretKeySpec(key, "AES"),
                        new IvParameterSpec(initializationVector)
                );
            } catch (GeneralSecurityException exception) {
                throw new IOException("Failed to initialize 7z AES encryption", exception);
            } finally {
                Arrays.fill(initializationVector, (byte) 0);
            }
        }


        /// Writes one unencrypted byte.
        @Override
        public void write(int value) throws IOException {
            byte[] single = {(byte) value};
            write(single, 0, 1);
        }


        /// Encrypts input bytes.
        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            ensureOpen();
            writeCipherOutput(cipher.update(buffer, offset, length));
            try {
                inputSize = Math.addExact(inputSize, length);
            } catch (ArithmeticException exception) {
                throw new IOException("7z encrypted coder input is too large", exception);
            }
        }


        /// Finishes AES zero padding and closes the packed output.
        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            @Nullable Throwable failure = null;
            byte[] padding = new byte[(int) ((AES_BLOCK_SIZE - inputSize % AES_BLOCK_SIZE) % AES_BLOCK_SIZE)];
            try {
                if (padding.length != 0) {
                    writeCipherOutput(cipher.update(padding));
                }
                writeCipherOutput(cipher.doFinal());
            } catch (GeneralSecurityException | IOException | RuntimeException | Error exception) {
                failure = exception;
            } finally {
                Arrays.fill(padding, (byte) 0);
                closed = true;
            }
            try {
                output.close();
            } catch (IOException | RuntimeException | Error exception) {
                failure = appendFailure(failure, exception);
            }
            throwFailure(failure);
        }


        /// Returns a defensive coder-properties copy.
        private byte[] properties() {
            return properties.clone();
        }


        /// Writes non-null cipher output.
        private void writeCipherOutput(byte @Nullable [] bytes) throws IOException {
            if (bytes != null && bytes.length != 0) {
                output.write(bytes);
            }
        }


        /// Requires this logical output to remain open.
        private void ensureOpen() throws ClosedChannelException {
            if (closed) {
                throw new ClosedChannelException();
            }
        }


        /// Builds 7z AES properties for a full initialization vector and no salt.
        private static byte[] aesProperties(byte @Unmodifiable [] initializationVector) {
            byte[] result = new byte[2 + initializationVector.length];
            result[0] = (byte) (AES_CYCLE_POWER | 1 << 6);
            result[1] = (byte) (initializationVector.length - 1);
            System.arraycopy(initializationVector, 0, result, 2, initializationVector.length);
            return result;
        }
    }


    /// Finishes a raw Deflate stream, releases its native state, and closes its downstream output.
    @NotNullByDefault
    private static final class RawDeflateOutputStream extends OutputStream {
        /// The native Deflate state.
        private final Deflater deflater;

        /// The raw Deflate output stream.
        private final DeflaterOutputStream output;

        /// Whether this stream has closed.
        private boolean closed;

        /// Creates a raw Deflate encoder.
        private RawDeflateOutputStream(OutputStream output, int level) {
            this.deflater = new Deflater(level, true);
            this.output = new DeflaterOutputStream(output, deflater);
        }


        /// Writes one decoded byte.
        @Override
        public void write(int value) throws IOException {
            output.write(value);
        }


        /// Writes decoded bytes.
        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            output.write(buffer, offset, length);
        }


        /// Flushes the Deflate output.
        @Override
        public void flush() throws IOException {
            output.flush();
        }


        /// Finishes Deflate, releases native state, and closes the downstream stream.
        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            try {
                output.close();
            } finally {
                deflater.end();
                closed = true;
            }
        }
    }
}
