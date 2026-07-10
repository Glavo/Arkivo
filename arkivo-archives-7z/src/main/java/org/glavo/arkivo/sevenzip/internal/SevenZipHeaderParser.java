// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.glavo.arkivo.ArkivoPasswordProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

/// Parses unencoded 7z metadata headers.
@NotNullByDefault
public final class SevenZipHeaderParser {
    /// The `kEnd` property ID.
    private static final int K_END = 0x00;

    /// The `kHeader` property ID.
    private static final int K_HEADER = 0x01;

    /// The `kArchiveProperties` property ID.
    private static final int K_ARCHIVE_PROPERTIES = 0x02;

    /// The `kAdditionalStreamsInfo` property ID.
    private static final int K_ADDITIONAL_STREAMS_INFO = 0x03;

    /// The `kMainStreamsInfo` property ID.
    private static final int K_MAIN_STREAMS_INFO = 0x04;

    /// The `kFilesInfo` property ID.
    private static final int K_FILES_INFO = 0x05;

    /// The `kPackInfo` property ID.
    private static final int K_PACK_INFO = 0x06;

    /// The `kUnPackInfo` property ID.
    private static final int K_UNPACK_INFO = 0x07;

    /// The `kSubStreamsInfo` property ID.
    private static final int K_SUBSTREAMS_INFO = 0x08;

    /// The `kSize` property ID.
    private static final int K_SIZE = 0x09;

    /// The `kCRC` property ID.
    private static final int K_CRC = 0x0a;

    /// The `kFolder` property ID.
    private static final int K_FOLDER = 0x0b;

    /// The `kCodersUnPackSize` property ID.
    private static final int K_CODERS_UNPACK_SIZE = 0x0c;

    /// The `kNumUnPackStream` property ID.
    private static final int K_NUM_UNPACK_STREAM = 0x0d;

    /// The `kEmptyStream` property ID.
    private static final int K_EMPTY_STREAM = 0x0e;

    /// The `kEmptyFile` property ID.
    private static final int K_EMPTY_FILE = 0x0f;

    /// The `kAnti` property ID.
    private static final int K_ANTI = 0x10;

    /// The `kName` property ID.
    private static final int K_NAME = 0x11;

    /// The `kCreationTime` property ID.
    private static final int K_CREATION_TIME = 0x12;

    /// The `kLastAccessTime` property ID.
    private static final int K_LAST_ACCESS_TIME = 0x13;

    /// The `kLastWriteTime` property ID.
    private static final int K_LAST_WRITE_TIME = 0x14;

    /// The `kWinAttributes` property ID.
    private static final int K_WIN_ATTRIBUTES = 0x15;

    /// The `kEncodedHeader` property ID.
    private static final int K_ENCODED_HEADER = 0x17;

    /// The `kDummy` property ID.
    private static final int K_DUMMY = 0x19;

    /// The number of 100-nanosecond ticks between 1601-01-01T00:00:00Z and 1970-01-01T00:00:00Z.
    private static final long WINDOWS_EPOCH_OFFSET_TICKS = 116_444_736_000_000_000L;

    /// The number of Windows FILETIME ticks in one second.
    private static final long WINDOWS_TICKS_PER_SECOND = 10_000_000L;

    /// Creates no instances.
    private SevenZipHeaderParser() {
    }

    /// Parses entries from a validated 7z next header.
    public static List<SevenZipEntryMetadata> parseEntries(byte[] header) throws IOException {
        return parseEntries(header, null, null);
    }

    /// Parses entries from a validated 7z next header, reading packed encoded header streams when needed.
    static List<SevenZipEntryMetadata> parseEntries(
            byte[] header,
            @Nullable PackedStreamOpener packedStreamOpener
    ) throws IOException {
        return parseEntries(header, packedStreamOpener, null);
    }

    /// Parses entries from a validated 7z next header, reading packed encoded header streams when needed.
    static List<SevenZipEntryMetadata> parseEntries(
            byte[] header,
            @Nullable PackedStreamOpener packedStreamOpener,
            @Nullable ArkivoPasswordProvider passwordProvider
    ) throws IOException {
        Objects.requireNonNull(header, "header");
        if (header.length == 0) {
            return List.of();
        }

        HeaderInput input = new HeaderInput(header);
        int type = input.readId();
        if (type == K_END) {
            input.requireFullyConsumed();
            return List.of();
        }
        if (type == K_ENCODED_HEADER) {
            return parseEncodedHeader(input, packedStreamOpener, passwordProvider);
        }
        if (type != K_HEADER) {
            throw new IOException("Unexpected 7z next header type: " + type);
        }

        ArrayList<SevenZipEntryMetadata> entries = new ArrayList<>();
        ExternalData externalData = ExternalData.EMPTY;
        StreamsInfo streamsInfo = StreamsInfo.EMPTY;
        while (true) {
            int property = input.readId();
            if (property == K_END) {
                input.requireFullyConsumed();
                return List.copyOf(entries);
            }
            switch (property) {
                case K_ARCHIVE_PROPERTIES -> skipArchiveProperties(input);
                case K_ADDITIONAL_STREAMS_INFO -> externalData =
                        new ExternalData(readStreamsInfo(input), packedStreamOpener, passwordProvider);
                case K_MAIN_STREAMS_INFO -> streamsInfo = readStreamsInfo(input, externalData);
                case K_FILES_INFO -> entries.addAll(readFilesInfo(input, streamsInfo, externalData));
                case K_DUMMY -> skipDummyData(input);
                default -> throw new UnsupportedOperationException(
                        "Unsupported 7z header property: 0x" + Integer.toHexString(property)
                );
            }
        }
    }

    /// Decodes a compressed 7z header and parses the resulting plain header.
    private static List<SevenZipEntryMetadata> parseEncodedHeader(
            HeaderInput input,
            @Nullable PackedStreamOpener packedStreamOpener,
            @Nullable ArkivoPasswordProvider passwordProvider
    ) throws IOException {
        if (packedStreamOpener == null) {
            throw new UnsupportedOperationException("7z encoded headers require archive stream access");
        }

        StreamsInfo streamsInfo = readStreamsInfo(input);
        input.requireFullyConsumed();

        ByteArrayOutputStream decodedHeader = new ByteArrayOutputStream();
        for (int index = 0; index < streamsInfo.streamCount(); index++) {
            decodedHeader.writeBytes(decodeEncodedHeaderStream(
                    streamsInfo,
                    index,
                    packedStreamOpener,
                    passwordProvider
            ));
        }
        return parseEntries(decodedHeader.toByteArray(), packedStreamOpener, passwordProvider);
    }

    /// Decodes one encoded header substream.
    private static byte[] decodeEncodedHeaderStream(
            StreamsInfo streamsInfo,
            int index,
            PackedStreamOpener packedStreamOpener,
            @Nullable ArkivoPasswordProvider passwordProvider
    ) throws IOException {
        long unpackSize = streamsInfo.unpackSize(index);
        long decodedOffset = streamsInfo.decodedOffset(index);
        if (unpackSize > Integer.MAX_VALUE) {
            throw new IOException("7z encoded header is too large to index");
        }

        long packedSize = streamsInfo.packedSize(index);
        InputStream rawPacked = packedStreamOpener.open(streamsInfo.dataOffset(index), packedSize);
        long packedCrc32 = streamsInfo.packedCrc32(index);
        InputStream packed = packedCrc32 != SevenZipEntryMetadata.UNKNOWN_CRC32
                ? new SevenZipBoundedInputStream(
                        rawPacked,
                        packedSize,
                        packedCrc32,
                        "7z packed stream data does not match CRC-32"
                )
                : rawPacked;
        InputStream decoded = packed;
        Throwable failure = null;
        try {
            boolean skipDecodedOffset;
            SevenZipFolderMethod method = streamsInfo.method(index);
            if (method.isCopyOnly()) {
                skipDecodedOffset = false;
            } else {
                long decodedLimit = checkedAdd(decodedOffset, unpackSize, "7z encoded header size is too large");
                decoded = SevenZipLZMADecoder.openFolder(packed, method, decodedLimit, passwordProvider);
                skipDecodedOffset = true;
            }

            if (skipDecodedOffset && decodedOffset > 0) {
                decoded.skipNBytes(decodedOffset);
            }
            byte[] bytes = decoded.readNBytes((int) unpackSize);
            if (bytes.length != unpackSize) {
                throw new EOFException("Unexpected end of 7z encoded header");
            }
            validateCrc32(bytes, streamsInfo.crc32(index), "7z encoded header stream");
            return bytes;
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                decoded.close();
            } catch (IOException | RuntimeException | Error exception) {
                if (failure != null) {
                    failure.addSuppressed(exception);
                } else {
                    throw exception;
                }
            }
        }
    }

    /// Reads a `StreamsInfo` block for unpacked Copy streams.
    private static StreamsInfo readStreamsInfo(HeaderInput input) throws IOException {
        return readStreamsInfo(input, ExternalData.EMPTY);
    }

    /// Reads a `StreamsInfo` block for unpacked Copy streams.
    private static StreamsInfo readStreamsInfo(HeaderInput input, ExternalData externalData) throws IOException {
        PackInfo packInfo = PackInfo.EMPTY;
        FolderInfo[] folders = new FolderInfo[0];
        SubStreamsInfo subStreamsInfo = null;
        boolean unpackInfoSeen = false;
        while (true) {
            int property = input.readId();
            switch (property) {
                case K_END -> {
                    return streamsInfo(packInfo, folders, subStreamsInfo);
                }
                case K_PACK_INFO -> packInfo = readPackInfo(input);
                case K_UNPACK_INFO -> {
                    if (subStreamsInfo != null) {
                        throw new IOException("7z folders appeared after substreams");
                    }
                    folders = readUnPackInfo(input, externalData);
                    unpackInfoSeen = true;
                }
                case K_SUBSTREAMS_INFO -> {
                    if (!unpackInfoSeen && packInfo.packSizes.length != 0) {
                        throw new IOException("7z substreams appeared before folders");
                    }
                    subStreamsInfo = readSubStreamsInfo(input, folders);
                }
                case K_DUMMY -> skipDummyData(input);
                default -> throw new UnsupportedOperationException(
                        "Unsupported 7z streams info property: 0x" + Integer.toHexString(property)
                );
            }
        }
    }

    /// Creates stream information after validating `PackInfo` and `UnPackInfo` cardinality.
    private static StreamsInfo streamsInfo(
            PackInfo packInfo,
            FolderInfo[] folders,
            @Nullable SubStreamsInfo subStreamsInfo
    ) throws IOException {
        if (packInfo.packSizes.length != folders.length) {
            throw new IOException("7z pack stream count does not match folder count");
        }
        return new StreamsInfo(packInfo.packPosition, packInfo.packSizes, packInfo.packCrc32s, folders, subStreamsInfo);
    }

    /// Reads `SubStreamsInfo` for folders.
    private static SubStreamsInfo readSubStreamsInfo(HeaderInput input, FolderInfo[] folders) throws IOException {
        int[] counts = new int[folders.length];
        Arrays.fill(counts, 1);
        long[][] sizes = null;
        long[][] crc32s = null;
        boolean countsLocked = false;
        while (true) {
            int property = input.readId();
            switch (property) {
                case K_END -> {
                    return buildSubStreamsInfo(folders, counts, sizes, crc32s);
                }
                case K_NUM_UNPACK_STREAM -> {
                    if (countsLocked) {
                        throw new IOException("7z substream counts appeared after dependent substream metadata");
                    }
                    for (int index = 0; index < counts.length; index++) {
                        counts[index] = input.readIntNumber("substream count");
                        if (counts[index] < 0) {
                            throw new IOException("7z substream count is negative");
                        }
                    }
                }
                case K_SIZE -> {
                    countsLocked = true;
                    sizes = readSubStreamSizes(input, folders, counts);
                }
                case K_CRC -> {
                    countsLocked = true;
                    crc32s = readSubStreamCrc32s(input, folders, counts);
                }
                case K_DUMMY -> skipDummyData(input);
                default -> throw new UnsupportedOperationException(
                        "Unsupported 7z substreams info property: 0x" + Integer.toHexString(property)
                );
            }
        }
    }

    /// Reads substream sizes.
    private static long[][] readSubStreamSizes(
            HeaderInput input,
            FolderInfo[] folders,
            int[] counts
    ) throws IOException {
        long[][] sizes = new long[folders.length][];
        for (int folderIndex = 0; folderIndex < folders.length; folderIndex++) {
            int count = counts[folderIndex];
            sizes[folderIndex] = new long[count];
            if (count == 0) {
                continue;
            }
            long sum = 0;
            for (int index = 0; index < count - 1; index++) {
                long size = input.readNumber();
                if (size < 0) {
                    throw new IOException("7z substream size is too large");
                }
                sizes[folderIndex][index] = size;
                sum = checkedAdd(sum, size, "7z substream sizes are too large");
            }
            long lastSize = folders[folderIndex].unpackSize() - sum;
            if (lastSize < 0) {
                throw new IOException("7z substream sizes exceed folder size");
            }
            sizes[folderIndex][count - 1] = lastSize;
        }
        return sizes;
    }

    /// Reads substream CRC-32 values.
    private static long[][] readSubStreamCrc32s(
            HeaderInput input,
            FolderInfo[] folders,
            int[] counts
    ) throws IOException {
        long[] flat = readDigests(input, subStreamDigestCount(folders, counts));
        long[][] crc32s = new long[folders.length][];
        int streamIndex = 0;
        for (int folderIndex = 0; folderIndex < folders.length; folderIndex++) {
            int count = counts[folderIndex];
            crc32s[folderIndex] = new long[count];
            long folderCrc32 = folders[folderIndex].crc32();
            if (count == 1 && folderCrc32 != SevenZipEntryMetadata.UNKNOWN_CRC32) {
                crc32s[folderIndex][0] = folderCrc32;
                continue;
            }
            for (int subStreamIndex = 0; subStreamIndex < count; subStreamIndex++) {
                crc32s[folderIndex][subStreamIndex] = flat[streamIndex++];
            }
        }
        return crc32s;
    }

    /// Returns the number of CRC-32 digest slots stored in `SubStreamsInfo`.
    private static int subStreamDigestCount(FolderInfo[] folders, int[] counts) throws IOException {
        int result = 0;
        for (int folderIndex = 0; folderIndex < folders.length; folderIndex++) {
            int count = counts[folderIndex];
            if (count < 0) {
                throw new IOException("7z substream count is negative");
            }
            if (count == 1 && folders[folderIndex].crc32() != SevenZipEntryMetadata.UNKNOWN_CRC32) {
                continue;
            }
            try {
                result = Math.addExact(result, count);
            } catch (ArithmeticException exception) {
                throw new IOException("7z substream count is too large", exception);
            }
        }
        return result;
    }

    /// Builds substream metadata from optional `SubStreamsInfo`.
    private static SubStreamsInfo buildSubStreamsInfo(
            FolderInfo[] folders,
            int[] counts,
            long[][] sizes,
            @Nullable long[][] crc32s
    ) throws IOException {
        long[][] resolvedSizes = sizes;
        if (resolvedSizes == null) {
            resolvedSizes = new long[folders.length][];
            for (int folderIndex = 0; folderIndex < folders.length; folderIndex++) {
                if (counts[folderIndex] == 0) {
                    resolvedSizes[folderIndex] = new long[0];
                    continue;
                }
                if (counts[folderIndex] != 1) {
                    throw new IOException("7z substream sizes are missing");
                }
                resolvedSizes[folderIndex] = new long[]{folders[folderIndex].unpackSize()};
            }
        }
        ArrayList<SubStreamInfo> streams = new ArrayList<>();
        for (int folderIndex = 0; folderIndex < folders.length; folderIndex++) {
            long decodedOffset = 0;
            for (int subStreamIndex = 0; subStreamIndex < resolvedSizes[folderIndex].length; subStreamIndex++) {
                long size = resolvedSizes[folderIndex][subStreamIndex];
                long crc32 = crc32s != null
                        ? crc32s[folderIndex][subStreamIndex]
                        : resolvedSizes[folderIndex].length == 1
                        ? folders[folderIndex].crc32()
                        : SevenZipEntryMetadata.UNKNOWN_CRC32;
                streams.add(new SubStreamInfo(folderIndex, decodedOffset, size, crc32));
                decodedOffset = checkedAdd(decodedOffset, size, "7z folder unpack size is too large");
            }
        }
        return new SubStreamsInfo(streams.toArray(SubStreamInfo[]::new));
    }

    /// Reads a `PackInfo` block.
    private static PackInfo readPackInfo(HeaderInput input) throws IOException {
        long packPosition = input.readNumber();
        int streamCount = input.readIntNumber("pack stream count");
        long[] packSizes = new long[streamCount];
        long[] packCrc32s = unknownCrc32s(streamCount);
        boolean sizesRead = false;
        while (true) {
            int property = input.readId();
            switch (property) {
                case K_END -> {
                    if (streamCount > 0 && !sizesRead) {
                        throw new IOException("7z pack sizes are missing");
                    }
                    return new PackInfo(packPosition, packSizes, packCrc32s);
                }
                case K_SIZE -> {
                    sizesRead = true;
                    for (int index = 0; index < streamCount; index++) {
                        packSizes[index] = input.readNumber();
                    }
                }
                case K_CRC -> packCrc32s = readDigests(input, streamCount);
                case K_DUMMY -> skipDummyData(input);
                default -> throw new UnsupportedOperationException(
                        "Unsupported 7z pack info property: 0x" + Integer.toHexString(property)
                );
            }
        }
    }

    /// Reads an `UnPackInfo` block and returns one folder per unpack stream.
    private static FolderInfo[] readUnPackInfo(HeaderInput input, ExternalData externalData) throws IOException {
        FolderInfo[] folders = new FolderInfo[0];
        while (true) {
            int property = input.readId();
            switch (property) {
                case K_END -> {
                    if (folders.length == 0) {
                        return folders;
                    }
                    throw new IOException("7z unpack sizes are missing");
                }
                case K_FOLDER -> folders = readFolders(input, externalData);
                case K_CODERS_UNPACK_SIZE -> {
                    if (folders.length == 0) {
                        throw new IOException("7z unpack sizes appeared before folders");
                    }
                    for (FolderInfo folder : folders) {
                        folder.setUnpackSizes(input);
                    }
                    requireUnPackInfoEnd(input, folders);
                    return folders;
                }
                case K_CRC -> {
                    if (folders.length == 0) {
                        throw new IOException("7z folder CRC appeared before folders");
                    }
                    readFolderCrc32s(input, folders);
                }
                case K_DUMMY -> skipDummyData(input);
                default -> throw new UnsupportedOperationException(
                        "Unsupported 7z unpack info property: 0x" + Integer.toHexString(property)
                );
            }
        }
    }

    /// Requires the remaining `UnPackInfo` block to contain only supported optional data and `kEnd`.
    private static void requireUnPackInfoEnd(HeaderInput input, FolderInfo[] folders) throws IOException {
        while (true) {
            int property = input.readId();
            switch (property) {
                case K_END -> {
                    return;
                }
                case K_CRC -> readFolderCrc32s(input, folders);
                case K_DUMMY -> skipDummyData(input);
                default -> throw new UnsupportedOperationException(
                        "Unsupported 7z unpack info property after sizes: 0x" + Integer.toHexString(property)
                );
            }
        }
    }

    /// Skips a sized `kDummy` property payload.
    private static void skipDummyData(HeaderInput input) throws IOException {
        input.skipBytes(input.readIntNumber("dummy property size"));
    }

    /// Skips an `ArchiveProperties` block.
    private static void skipArchiveProperties(HeaderInput input) throws IOException {
        while (true) {
            int property = input.readId();
            if (property == K_END) {
                return;
            }
            input.skipBytes(input.readIntNumber("archive property size"));
        }
    }

    /// Reads folders and requires each folder to use supported linear coder bindings.
    private static FolderInfo[] readFolders(HeaderInput input, ExternalData externalData) throws IOException {
        int folderCount = input.readIntNumber("folder count");
        int external = input.readUnsignedByte();
        if (external != 0) {
            int dataIndex = input.readIntNumber("folder external data index");
            HeaderInput folderInput = externalData.input(dataIndex, "folder definitions");
            FolderInfo[] folders = readInlineFolders(folderInput, folderCount);
            folderInput.requireFullyConsumed();
            return folders;
        }
        return readInlineFolders(input, folderCount);
    }

    /// Reads and stores folder CRC-32 values.
    private static void readFolderCrc32s(HeaderInput input, FolderInfo[] folders) throws IOException {
        long[] crc32s = readDigests(input, folders.length);
        for (int index = 0; index < folders.length; index++) {
            folders[index].setCrc32(crc32s[index]);
        }
    }

    /// Reads inline folder definitions.
    private static FolderInfo[] readInlineFolders(HeaderInput input, int folderCount) throws IOException {
        FolderInfo[] folders = new FolderInfo[folderCount];
        for (int index = 0; index < folderCount; index++) {
            folders[index] = readFolder(input);
        }
        return folders;
    }

    /// Reads one folder and requires it to use a supported linear coder pipeline.
    private static FolderInfo readFolder(HeaderInput input) throws IOException {
        int coderCount = input.readIntNumber("coder count");
        if (coderCount <= 0) {
            throw new IOException("7z folder has no coders");
        }

        CoderInfo[] coders = new CoderInfo[coderCount];
        int totalInputStreams = 0;
        int totalOutputStreams = 0;
        for (int coderIndex = 0; coderIndex < coderCount; coderIndex++) {
            int flags = input.readUnsignedByte();
            if ((flags & 0xc0) != 0) {
                throw new UnsupportedOperationException("Unsupported 7z coder flags: 0x" + Integer.toHexString(flags));
            }

            int methodIdSize = flags & 0x0f;
            boolean complex = (flags & 0x10) != 0;
            boolean attributes = (flags & 0x20) != 0;
            byte[] methodId = input.readBytes(methodIdSize);
            long inputStreamCount = 1;
            long outputStreamCount = 1;
            if (complex) {
                inputStreamCount = input.readNumber();
                outputStreamCount = input.readNumber();
            }
            byte[] properties = new byte[0];
            if (attributes) {
                properties = input.readBytes(input.readIntNumber("coder properties size"));
            }

            if (inputStreamCount != 1 || outputStreamCount != 1) {
                throw new UnsupportedOperationException("Unsupported 7z multi-stream coder");
            }
            if (!SevenZipLZMADecoder.isSupported(methodId)) {
                throw new UnsupportedOperationException("Unsupported 7z coder method: " + Arrays.toString(methodId));
            }
            coders[coderIndex] = new CoderInfo(methodId, properties, totalInputStreams, totalOutputStreams);
            totalInputStreams++;
            totalOutputStreams++;
        }

        int bindPairCount = totalOutputStreams - 1;
        int[] nextCoderByOutput = new int[coderCount];
        boolean[] inputIsBound = new boolean[coderCount];
        Arrays.fill(nextCoderByOutput, -1);
        for (int index = 0; index < bindPairCount; index++) {
            int inputStreamIndex = input.readIntNumber("bind pair input stream index");
            int outputStreamIndex = input.readIntNumber("bind pair output stream index");
            int inputCoder = coderByInputStream(coders, inputStreamIndex);
            int outputCoder = coderByOutputStream(coders, outputStreamIndex);
            if (inputIsBound[inputCoder]) {
                throw new IOException("7z folder has duplicate bound input stream");
            }
            if (nextCoderByOutput[outputCoder] >= 0) {
                throw new IOException("7z folder has duplicate bound output stream");
            }
            inputIsBound[inputCoder] = true;
            nextCoderByOutput[outputCoder] = inputCoder;
        }

        int packedStreamCount = totalInputStreams - bindPairCount;
        if (packedStreamCount != 1) {
            throw new UnsupportedOperationException("Unsupported 7z folder with multiple packed streams");
        }
        return new FolderInfo(coders, coderPipelineOrder(nextCoderByOutput, inputIsBound));
    }

    /// Returns the coder that owns the given input stream index.
    private static int coderByInputStream(CoderInfo[] coders, int streamIndex) throws IOException {
        for (int index = 0; index < coders.length; index++) {
            if (coders[index].inputStreamIndex() == streamIndex) {
                return index;
            }
        }
        throw new IOException("7z folder bind pair references a missing input stream");
    }

    /// Returns the coder that owns the given output stream index.
    private static int coderByOutputStream(CoderInfo[] coders, int streamIndex) throws IOException {
        for (int index = 0; index < coders.length; index++) {
            if (coders[index].outputStreamIndex() == streamIndex) {
                return index;
            }
        }
        throw new IOException("7z folder bind pair references a missing output stream");
    }

    /// Returns coder indexes ordered from packed input to final output.
    private static int[] coderPipelineOrder(int[] nextCoderByOutput, boolean[] inputIsBound) throws IOException {
        int sourceCoder = -1;
        for (int index = 0; index < inputIsBound.length; index++) {
            if (!inputIsBound[index]) {
                if (sourceCoder >= 0) {
                    throw new IOException("7z folder has multiple packed input coders");
                }
                sourceCoder = index;
            }
        }
        if (sourceCoder < 0) {
            throw new IOException("7z folder has no packed input coder");
        }

        int[] order = new int[nextCoderByOutput.length];
        boolean[] visited = new boolean[nextCoderByOutput.length];
        int count = 0;
        int current = sourceCoder;
        while (current >= 0) {
            if (visited[current]) {
                throw new IOException("7z folder coder bindings contain a cycle");
            }
            visited[current] = true;
            order[count++] = current;
            current = nextCoderByOutput[current];
        }
        if (count != order.length) {
            throw new IOException("7z folder coder bindings are not linear");
        }
        return order;
    }

    /// Reads a `FilesInfo` block.
    private static List<SevenZipEntryMetadata> readFilesInfo(
            HeaderInput input,
            StreamsInfo streamsInfo,
            ExternalData externalData
    ) throws IOException {
        int fileCount = input.readIntNumber("file count");
        String[] names = new String[fileCount];
        boolean[] emptyStreams = new boolean[fileCount];
        boolean[] emptyFiles = new boolean[fileCount];
        boolean[] antiFiles = new boolean[fileCount];
        FileTime[] creationTimes = new FileTime[fileCount];
        FileTime[] lastAccessTimes = new FileTime[fileCount];
        FileTime[] lastModifiedTimes = new FileTime[fileCount];
        int[] windowsAttributes = new int[fileCount];
        Arrays.fill(windowsAttributes, -1);

        while (true) {
            int property = input.readId();
            if (property == K_END) {
                return buildEntries(
                        names,
                        emptyStreams,
                        emptyFiles,
                        antiFiles,
                        creationTimes,
                        lastAccessTimes,
                        lastModifiedTimes,
                        windowsAttributes,
                        streamsInfo
                );
            }

            int size = input.readIntNumber("file property size");
            int end = input.checkedEnd(size);
            switch (property) {
                case K_EMPTY_STREAM -> readBooleanVector(input, emptyStreams);
                case K_EMPTY_FILE -> readEmptyFileVector(input, emptyStreams, emptyFiles);
                case K_ANTI -> readAntiFileVector(input, emptyStreams, antiFiles);
                case K_NAME -> readNames(input, end, names, externalData);
                case K_CREATION_TIME -> readFileTimes(input, end, creationTimes, externalData);
                case K_LAST_ACCESS_TIME -> readFileTimes(input, end, lastAccessTimes, externalData);
                case K_LAST_WRITE_TIME -> readFileTimes(input, end, lastModifiedTimes, externalData);
                case K_WIN_ATTRIBUTES -> readWindowsAttributes(input, end, windowsAttributes, externalData);
                default -> input.position(end);
            }
            input.requirePosition(end);
        }
    }

    /// Builds entry metadata from parsed `FilesInfo` properties.
    private static List<SevenZipEntryMetadata> buildEntries(
            String[] names,
            boolean[] emptyStreams,
            boolean[] emptyFiles,
            boolean[] antiFiles,
            FileTime[] creationTimes,
            FileTime[] lastAccessTimes,
            FileTime[] lastModifiedTimes,
            int[] windowsAttributes,
            StreamsInfo streamsInfo
    ) throws IOException {
        ArrayList<SevenZipEntryMetadata> entries = new ArrayList<>(names.length);
        int streamIndex = 0;
        for (int index = 0; index < names.length; index++) {
            String name = names[index];
            if (name == null || name.isEmpty()) {
                throw new IOException("7z file entry is missing a name");
            }
            if (antiFiles[index]) {
                continue;
            }
            if (emptyStreams[index]) {
                boolean directory = !emptyFiles[index];
                entries.add(new SevenZipEntryMetadata(
                        name,
                        directory,
                        0L,
                        SevenZipEntryMetadata.NO_DATA_OFFSET,
                        0L,
                        0L,
                        new byte[0],
                        new byte[0],
                        creationTimes[index],
                        lastAccessTimes[index],
                        lastModifiedTimes[index],
                        windowsAttributes[index]
                ));
            } else {
                entries.add(new SevenZipEntryMetadata(
                        name,
                        false,
                        streamsInfo.unpackSize(streamIndex),
                        streamsInfo.dataOffset(streamIndex),
                        streamsInfo.decodedOffset(streamIndex),
                        streamsInfo.packedSize(streamIndex),
                        streamsInfo.packedCrc32(streamIndex),
                        streamsInfo.crc32(streamIndex),
                        streamsInfo.method(streamIndex),
                        creationTimes[index],
                        lastAccessTimes[index],
                        lastModifiedTimes[index],
                        windowsAttributes[index]
                ));
                streamIndex++;
            }
        }
        if (streamIndex != streamsInfo.streamCount()) {
            throw new IOException("7z stream count does not match file entries");
        }
        return List.copyOf(entries);
    }

    /// Reads a boolean vector whose length matches the target array.
    private static void readBooleanVector(HeaderInput input, boolean[] target) throws IOException {
        for (int index = 0; index < target.length; ) {
            int bits = input.readUnsignedByte();
            for (int bit = 0; bit < 8 && index < target.length; bit++) {
                target[index++] = (bits & (0x80 >>> bit)) != 0;
            }
        }
    }

    /// Reads the `kEmptyFile` vector and maps it back to file indices.
    private static void readEmptyFileVector(
            HeaderInput input,
            boolean[] emptyStreams,
            boolean[] emptyFiles
    ) throws IOException {
        readEmptyStreamMappedVector(input, emptyStreams, emptyFiles);
    }

    /// Reads the `kAnti` vector and maps it back to file indices.
    private static void readAntiFileVector(
            HeaderInput input,
            boolean[] emptyStreams,
            boolean[] antiFiles
    ) throws IOException {
        readEmptyStreamMappedVector(input, emptyStreams, antiFiles);
    }

    /// Reads a vector for empty-stream entries and maps it back to file indices.
    private static void readEmptyStreamMappedVector(
            HeaderInput input,
            boolean[] emptyStreams,
            boolean[] target
    ) throws IOException {
        boolean[] values = new boolean[countTrue(emptyStreams)];
        readBooleanVector(input, values);

        int valueIndex = 0;
        for (int index = 0; index < emptyStreams.length; index++) {
            if (emptyStreams[index]) {
                target[index] = values[valueIndex++];
            }
        }
    }

    /// Reads UTF-16LE null-terminated names.
    private static void readNames(
            HeaderInput input,
            int end,
            String[] names,
            ExternalData externalData
    ) throws IOException {
        int external = input.readUnsignedByte();
        HeaderInput nameInput;
        int nameEnd;
        if (external == 0) {
            nameInput = input;
            nameEnd = end;
        } else {
            int dataIndex = input.readIntNumber("file names data index");
            input.requirePosition(end);
            nameInput = externalData.input(dataIndex, "file names");
            nameEnd = nameInput.bytes().length;
        }

        for (int index = 0; index < names.length; index++) {
            int start = nameInput.position();
            while (true) {
                if (nameInput.position() + 1 >= nameEnd) {
                    throw new IOException("Unterminated 7z file name");
                }
                int low = nameInput.readUnsignedByte();
                int high = nameInput.readUnsignedByte();
                if (low == 0 && high == 0) {
                    int byteLength = nameInput.position() - start - 2;
                    names[index] = new String(nameInput.bytes(), start, byteLength, StandardCharsets.UTF_16LE);
                    break;
                }
            }
        }
        nameInput.requirePosition(nameEnd);
    }

    /// Reads a per-file time property.
    private static void readFileTimes(
            HeaderInput input,
            int end,
            FileTime[] target,
            ExternalData externalData
    ) throws IOException {
        boolean[] defined = readDefinedVector(input, target.length);
        int external = input.readUnsignedByte();
        HeaderInput valueInput;
        if (external == 0) {
            valueInput = input;
        } else {
            int dataIndex = input.readIntNumber("file times data index");
            input.requirePosition(end);
            valueInput = externalData.input(dataIndex, "file times");
        }
        for (int index = 0; index < target.length; index++) {
            if (defined[index]) {
                target[index] = fileTimeFromWindowsTicks(valueInput.readLongLE());
            }
        }
        if (external != 0) {
            valueInput.requireFullyConsumed();
        }
    }

    /// Reads per-file Windows attributes.
    private static void readWindowsAttributes(
            HeaderInput input,
            int end,
            int[] target,
            ExternalData externalData
    ) throws IOException {
        boolean[] defined = readDefinedVector(input, target.length);
        int external = input.readUnsignedByte();
        HeaderInput valueInput;
        if (external == 0) {
            valueInput = input;
        } else {
            int dataIndex = input.readIntNumber("Windows attributes data index");
            input.requirePosition(end);
            valueInput = externalData.input(dataIndex, "Windows attributes");
        }
        for (int index = 0; index < target.length; index++) {
            if (defined[index]) {
                target[index] = valueInput.readIntLE();
            }
        }
        if (external != 0) {
            valueInput.requireFullyConsumed();
        }
    }

    /// Reads a defined vector.
    private static boolean[] readDefinedVector(HeaderInput input, int count) throws IOException {
        boolean[] defined = new boolean[count];
        int allAreDefined = input.readUnsignedByte();
        if (allAreDefined != 0) {
            Arrays.fill(defined, true);
        } else {
            readBooleanVector(input, defined);
        }
        return defined;
    }

    /// Converts Windows FILETIME ticks into Java file time.
    private static FileTime fileTimeFromWindowsTicks(long windowsTicks) {
        long seconds;
        long nanos;
        if (Long.compareUnsigned(windowsTicks, WINDOWS_EPOCH_OFFSET_TICKS) >= 0) {
            long unixTicks = windowsTicks - WINDOWS_EPOCH_OFFSET_TICKS;
            seconds = Long.divideUnsigned(unixTicks, WINDOWS_TICKS_PER_SECOND);
            nanos = Long.remainderUnsigned(unixTicks, WINDOWS_TICKS_PER_SECOND) * 100L;
        } else {
            long unixTicks = windowsTicks - WINDOWS_EPOCH_OFFSET_TICKS;
            seconds = Math.floorDiv(unixTicks, WINDOWS_TICKS_PER_SECOND);
            nanos = Math.floorMod(unixTicks, WINDOWS_TICKS_PER_SECOND) * 100L;
        }
        return FileTime.from(Instant.ofEpochSecond(seconds, nanos));
    }

    /// Returns the number of `true` values in the given vector.
    private static int countTrue(boolean[] values) {
        int count = 0;
        for (boolean value : values) {
            if (value) {
                count++;
            }
        }
        return count;
    }

    /// Returns the sum of the given non-negative values.
    private static int sum(int[] values) throws IOException {
        int result = 0;
        for (int value : values) {
            if (value < 0) {
                throw new IOException("7z substream count is negative");
            }
            try {
                result = Math.addExact(result, value);
            } catch (ArithmeticException exception) {
                throw new IOException("7z substream count is too large", exception);
            }
        }
        return result;
    }

    /// Returns the checked sum of two non-negative `long` values.
    private static long checkedAdd(long left, long right, String message) throws IOException {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IOException(message, exception);
        }
    }

    /// Validates the CRC-32 of decoded bytes when an expected digest is present.
    private static void validateCrc32(byte[] bytes, long expectedCrc32, String description) throws IOException {
        if (expectedCrc32 == SevenZipEntryMetadata.UNKNOWN_CRC32) {
            return;
        }
        CRC32 crc32 = new CRC32();
        crc32.update(bytes);
        if (crc32.getValue() != expectedCrc32) {
            throw new IOException(description + " does not match CRC-32");
        }
    }

    /// Skips CRC digest definitions.
    private static void skipDigests(HeaderInput input, int count) throws IOException {
        readDigests(input, count);
    }

    /// Creates an array of unknown CRC-32 values.
    private static long[] unknownCrc32s(int count) {
        long[] result = new long[count];
        Arrays.fill(result, SevenZipEntryMetadata.UNKNOWN_CRC32);
        return result;
    }

    /// Reads CRC digest definitions.
    private static long[] readDigests(HeaderInput input, int count) throws IOException {
        long[] digests = unknownCrc32s(count);
        boolean[] defined = new boolean[count];
        int allAreDefined = input.readUnsignedByte();
        if (allAreDefined != 0) {
            Arrays.fill(defined, true);
        } else {
            readBooleanVector(input, defined);
        }
        for (int index = 0; index < defined.length; index++) {
            if (defined[index]) {
                digests[index] = Integer.toUnsignedLong(input.readIntLE());
            }
        }
        return digests;
    }

    /// Stores parsed pack stream information.
    @NotNullByDefault
    private static final class PackInfo {
        /// The empty pack information value.
        private static final PackInfo EMPTY = new PackInfo(0L, new long[0], new long[0]);

        /// The first pack stream position relative to the first byte after the signature header.
        private final long packPosition;

        /// The pack stream sizes.
        private final long[] packSizes;

        /// The expected packed stream CRC-32 values.
        private final long[] packCrc32s;

        /// Creates pack stream information.
        private PackInfo(long packPosition, long[] packSizes, long[] packCrc32s) {
            if (packSizes.length != packCrc32s.length) {
                throw new IllegalArgumentException("packSizes and packCrc32s must have the same length");
            }
            this.packPosition = packPosition;
            this.packSizes = packSizes.clone();
            this.packCrc32s = packCrc32s.clone();
        }
    }

    /// Stores parsed stream information.
    @NotNullByDefault
    private static final class StreamsInfo {
        /// The empty streams information value.
        private static final StreamsInfo EMPTY = new StreamsInfo(0L, new long[0], new long[0], new FolderInfo[0], null);

        /// The first pack stream position relative to the first byte after the signature header.
        private final long packPosition;

        /// The pack stream sizes.
        private final long[] packSizes;

        /// The expected packed stream CRC-32 values.
        private final long[] packCrc32s;

        /// The parsed folders.
        private final FolderInfo[] folders;

        /// The flattened substreams addressable by file entries.
        private final SubStreamsInfo subStreamsInfo;

        /// Creates stream information.
        private StreamsInfo(
                long packPosition,
                long[] packSizes,
                long[] packCrc32s,
                FolderInfo[] folders,
                @Nullable SubStreamsInfo subStreamsInfo
        ) {
            if (packSizes.length != folders.length) {
                throw new IllegalArgumentException("packSizes and folders must have the same length");
            }
            if (packCrc32s.length != folders.length) {
                throw new IllegalArgumentException("packCrc32s and folders must have the same length");
            }
            this.packPosition = packPosition;
            this.packSizes = packSizes.clone();
            this.packCrc32s = packCrc32s.clone();
            this.folders = folders.clone();
            this.subStreamsInfo = subStreamsInfo != null ? subStreamsInfo : SubStreamsInfo.fromFolders(folders);
        }

        /// Returns the number of unpack streams.
        private int streamCount() {
            return subStreamsInfo.size();
        }

        /// Returns the unpack size for a stream.
        private long unpackSize(int index) throws IOException {
            requireStreamIndex(index);
            return subStreamsInfo.stream(index).size();
        }

        /// Returns the decoded offset inside the folder output for a stream.
        private long decodedOffset(int index) throws IOException {
            requireStreamIndex(index);
            return subStreamsInfo.stream(index).decodedOffset();
        }

        /// Returns the packed size for a stream.
        private long packedSize(int index) throws IOException {
            requireStreamIndex(index);
            SubStreamInfo stream = subStreamsInfo.stream(index);
            FolderInfo folder = folders[stream.folderIndex()];
            return folder.isCopyOnly() ? stream.size() : packSizes[stream.folderIndex()];
        }

        /// Returns the expected packed CRC-32 for a fully addressable packed stream.
        private long packedCrc32(int index) throws IOException {
            requireStreamIndex(index);
            SubStreamInfo stream = subStreamsInfo.stream(index);
            if (!streamCoversFolder(stream)) {
                return SevenZipEntryMetadata.UNKNOWN_CRC32;
            }
            return packCrc32s[stream.folderIndex()];
        }

        /// Returns the folder method for a stream.
        private SevenZipFolderMethod method(int index) throws IOException {
            requireStreamIndex(index);
            return folders[subStreamsInfo.stream(index).folderIndex()].method();
        }

        /// Returns the expected unpacked CRC-32 for a stream, or `UNKNOWN_CRC32` when not present.
        private long crc32(int index) throws IOException {
            requireStreamIndex(index);
            return subStreamsInfo.stream(index).crc32();
        }

        /// Returns the absolute archive data offset for a stream.
        private long dataOffset(int index) throws IOException {
            requireStreamIndex(index);
            SubStreamInfo stream = subStreamsInfo.stream(index);
            long offset = checkedAdd(
                    SevenZipSignatureHeader.SIZE,
                    packPosition,
                    "7z packed stream offset is too large"
            );
            for (int i = 0; i < stream.folderIndex(); i++) {
                offset = checkedAdd(offset, packSizes[i], "7z packed stream offset is too large");
            }
            if (folders[stream.folderIndex()].isCopyOnly()) {
                offset = checkedAdd(offset, stream.decodedOffset(), "7z packed stream offset is too large");
            }
            return offset;
        }

        /// Returns whether the given stream covers the entire decoded folder output.
        private boolean streamCoversFolder(SubStreamInfo stream) throws IOException {
            return stream.decodedOffset() == 0L && stream.size() == folders[stream.folderIndex()].unpackSize();
        }

        /// Requires a stream index to be valid and supported.
        private void requireStreamIndex(int index) throws IOException {
            if (index < 0 || index >= subStreamsInfo.size()) {
                throw new IOException("7z file entry references a missing stream");
            }
        }
    }

    /// Stores flattened 7z substream information.
    @NotNullByDefault
    private static final class SubStreamsInfo {
        /// The flattened substreams.
        private final SubStreamInfo[] streams;

        /// Creates substreams information.
        private SubStreamsInfo(SubStreamInfo[] streams) {
            this.streams = streams.clone();
        }

        /// Creates default substream information with one stream per folder.
        private static SubStreamsInfo fromFolders(FolderInfo[] folders) {
            SubStreamInfo[] streams = new SubStreamInfo[folders.length];
            for (int index = 0; index < folders.length; index++) {
                try {
                    streams[index] = new SubStreamInfo(index, 0L, folders[index].unpackSize(), folders[index].crc32());
                } catch (IOException exception) {
                    throw new IllegalStateException("folder unpack size should already be validated", exception);
                }
            }
            return new SubStreamsInfo(streams);
        }

        /// Returns the number of flattened substreams.
        private int size() {
            return streams.length;
        }

        /// Returns one flattened substream.
        private SubStreamInfo stream(int index) {
            return streams[index];
        }
    }

    /// Provides external file property data streams from `kAdditionalStreamsInfo`.
    @NotNullByDefault
    private static final class ExternalData {
        /// The empty external data value.
        private static final ExternalData EMPTY = new ExternalData(StreamsInfo.EMPTY, null, null);

        /// The additional streams metadata.
        private final StreamsInfo streamsInfo;

        /// The archive stream opener used to read external data.
        private final @Nullable PackedStreamOpener packedStreamOpener;

        /// The password provider used to decode encrypted external data streams.
        private final @Nullable ArkivoPasswordProvider passwordProvider;

        /// Creates an external data source.
        private ExternalData(
                StreamsInfo streamsInfo,
                @Nullable PackedStreamOpener packedStreamOpener,
                @Nullable ArkivoPasswordProvider passwordProvider
        ) {
            this.streamsInfo = Objects.requireNonNull(streamsInfo, "streamsInfo");
            this.packedStreamOpener = packedStreamOpener;
            this.passwordProvider = passwordProvider;
        }

        /// Returns the external data stream at the given index.
        private HeaderInput input(int index, String description) throws IOException {
            if (index < 0 || index >= streamsInfo.streamCount()) {
                throw new IOException("7z external " + description + " reference a missing stream");
            }
            if (packedStreamOpener == null) {
                throw new UnsupportedOperationException("7z external " + description + " require archive stream access");
            }
            return new HeaderInput(decodeEncodedHeaderStream(streamsInfo, index, packedStreamOpener, passwordProvider));
        }
    }

    /// Stores one file-addressable output stream inside a 7z folder.
    @NotNullByDefault
    private static final class SubStreamInfo {
        /// The folder index that contains this stream.
        private final int folderIndex;

        /// The decoded offset inside the folder output.
        private final long decodedOffset;

        /// The decoded stream size.
        private final long size;

        /// The expected uncompressed stream CRC-32, or `UNKNOWN_CRC32` when not present.
        private final long crc32;

        /// Creates substream information.
        private SubStreamInfo(int folderIndex, long decodedOffset, long size, long crc32) {
            if (folderIndex < 0) {
                throw new IllegalArgumentException("folderIndex must be non-negative");
            }
            if (decodedOffset < 0) {
                throw new IllegalArgumentException("decodedOffset must be non-negative");
            }
            if (size < 0) {
                throw new IllegalArgumentException("size must be non-negative");
            }
            if (crc32 < SevenZipEntryMetadata.UNKNOWN_CRC32 || crc32 > 0xffff_ffffL) {
                throw new IllegalArgumentException("crc32 must be an unsigned 32-bit value or UNKNOWN_CRC32");
            }
            this.folderIndex = folderIndex;
            this.decodedOffset = decodedOffset;
            this.size = size;
            this.crc32 = crc32;
        }

        /// Returns the folder index that contains this stream.
        private int folderIndex() {
            return folderIndex;
        }

        /// Returns the decoded offset inside the folder output.
        private long decodedOffset() {
            return decodedOffset;
        }

        /// Returns the decoded stream size.
        private long size() {
            return size;
        }

        /// Returns the expected uncompressed stream CRC-32, or `UNKNOWN_CRC32` when not present.
        private long crc32() {
            return crc32;
        }
    }

    /// Stores one 7z coder inside a folder.
    @NotNullByDefault
    private static final class CoderInfo {
        /// The method ID.
        private final byte @Unmodifiable [] methodId;

        /// The coder properties.
        private final byte @Unmodifiable [] properties;

        /// The folder input stream index owned by this coder.
        private final int inputStreamIndex;

        /// The folder output stream index owned by this coder.
        private final int outputStreamIndex;

        /// Creates coder information.
        private CoderInfo(byte[] methodId, byte[] properties, int inputStreamIndex, int outputStreamIndex) {
            if (inputStreamIndex < 0) {
                throw new IllegalArgumentException("inputStreamIndex must be non-negative");
            }
            if (outputStreamIndex < 0) {
                throw new IllegalArgumentException("outputStreamIndex must be non-negative");
            }
            this.methodId = Objects.requireNonNull(methodId, "methodId").clone();
            this.properties = Objects.requireNonNull(properties, "properties").clone();
            this.inputStreamIndex = inputStreamIndex;
            this.outputStreamIndex = outputStreamIndex;
        }

        /// Returns a copy of the method ID.
        private byte[] methodId() {
            return methodId.clone();
        }

        /// Returns a copy of the coder properties.
        private byte[] properties() {
            return properties.clone();
        }

        /// Returns the folder input stream index owned by this coder.
        private int inputStreamIndex() {
            return inputStreamIndex;
        }

        /// Returns the folder output stream index owned by this coder.
        private int outputStreamIndex() {
            return outputStreamIndex;
        }
    }

    /// Stores parsed linear folder information.
    @NotNullByDefault
    private static final class FolderInfo {
        /// The coders in folder declaration order.
        private final CoderInfo[] coders;

        /// The coder indexes ordered from packed input to final output.
        private final int @Unmodifiable [] coderOrder;

        /// The unpack size produced by each coder, indexed by coder declaration order.
        private long[] unpackSizes = new long[0];

        /// The expected final folder CRC-32, or `UNKNOWN_CRC32` when not present.
        private long crc32 = SevenZipEntryMetadata.UNKNOWN_CRC32;

        /// Creates folder information.
        private FolderInfo(CoderInfo[] coders, int[] coderOrder) {
            if (coders.length == 0) {
                throw new IllegalArgumentException("coders must not be empty");
            }
            if (coders.length != coderOrder.length) {
                throw new IllegalArgumentException("coders and coderOrder must have the same length");
            }
            this.coders = coders.clone();
            this.coderOrder = coderOrder.clone();

            boolean[] seen = new boolean[coders.length];
            for (int coderIndex : coderOrder) {
                if (coderIndex < 0 || coderIndex >= coders.length) {
                    throw new IllegalArgumentException("coderOrder contains an invalid coder index");
                }
                if (seen[coderIndex]) {
                    throw new IllegalArgumentException("coderOrder contains a duplicate coder index");
                }
                seen[coderIndex] = true;
            }
        }

        /// Reads and stores all coder unpack sizes for this folder.
        private void setUnpackSizes(HeaderInput input) throws IOException {
            long[] sizes = new long[coders.length];
            for (int index = 0; index < sizes.length; index++) {
                long size = input.readNumber();
                if (size < 0) {
                    throw new IOException("7z folder unpack size is too large");
                }
                sizes[index] = size;
            }
            this.unpackSizes = sizes;
        }

        /// Stores the expected final folder CRC-32.
        private void setCrc32(long crc32) {
            if (crc32 < SevenZipEntryMetadata.UNKNOWN_CRC32 || crc32 > 0xffff_ffffL) {
                throw new IllegalArgumentException("crc32 must be an unsigned 32-bit value or UNKNOWN_CRC32");
            }
            this.crc32 = crc32;
        }

        /// Returns the final folder unpack size.
        private long unpackSize() throws IOException {
            return method().finalUnpackSize();
        }

        /// Returns the expected final folder CRC-32, or `UNKNOWN_CRC32` when not present.
        private long crc32() {
            return crc32;
        }

        /// Returns the folder method in decoder pipeline order.
        private SevenZipFolderMethod method() throws IOException {
            requireUnpackSizes();
            byte[][] methodIds = new byte[coderOrder.length][];
            byte[][] properties = new byte[coderOrder.length][];
            long[] orderedUnpackSizes = new long[coderOrder.length];
            for (int orderIndex = 0; orderIndex < coderOrder.length; orderIndex++) {
                int coderIndex = coderOrder[orderIndex];
                CoderInfo coder = coders[coderIndex];
                methodIds[orderIndex] = coder.methodId();
                properties[orderIndex] = coder.properties();
                orderedUnpackSizes[orderIndex] = unpackSizes[coderIndex];
            }
            return new SevenZipFolderMethod(methodIds, properties, orderedUnpackSizes);
        }

        /// Returns whether every coder in this folder uses the 7z Copy method.
        private boolean isCopyOnly() {
            for (CoderInfo coder : coders) {
                if (!SevenZipLZMADecoder.isCopy(coder.methodId())) {
                    return false;
                }
            }
            return true;
        }

        /// Requires coder unpack sizes to have been read.
        private void requireUnpackSizes() throws IOException {
            if (unpackSizes.length != coders.length) {
                throw new IOException("7z folder unpack size is missing");
            }
        }
    }

    /// Reads 7z header bytes.
    @NotNullByDefault
    private static final class HeaderInput {
        /// The backing header bytes.
        private final byte[] bytes;

        /// The current read position.
        private int position;

        /// Creates a header input over the given bytes.
        private HeaderInput(byte[] bytes) {
            this.bytes = Objects.requireNonNull(bytes, "bytes");
        }

        /// Returns the backing header bytes.
        private byte[] bytes() {
            return bytes;
        }

        /// Returns the current read position.
        private int position() {
            return position;
        }

        /// Sets the current read position.
        private void position(int position) {
            if (position < this.position || position > bytes.length) {
                throw new IllegalArgumentException("position is out of range");
            }
            this.position = position;
        }

        /// Reads a property ID.
        private int readId() throws IOException {
            return readUnsignedByte();
        }

        /// Reads one unsigned byte.
        private int readUnsignedByte() throws IOException {
            if (position >= bytes.length) {
                throw new IOException("Unexpected end of 7z header");
            }
            return Byte.toUnsignedInt(bytes[position++]);
        }

        /// Reads a 7z variable-length unsigned integer.
        private long readNumber() throws IOException {
            int first = readUnsignedByte();
            int mask = 0x80;
            long value = 0;
            for (int index = 0; index < 8; index++) {
                if ((first & mask) == 0) {
                    long result = value | ((long) (first & (mask - 1)) << (8 * index));
                    if (result < 0) {
                        throw new IOException("7z number is too large");
                    }
                    return result;
                }
                value |= (long) readUnsignedByte() << (8 * index);
                mask >>>= 1;
            }
            if (value < 0) {
                throw new IOException("7z number is too large");
            }
            return value;
        }

        /// Reads a 7z variable-length integer that must fit in an `int`.
        private int readIntNumber(String description) throws IOException {
            long value = readNumber();
            if (value < 0 || value > Integer.MAX_VALUE) {
                throw new IOException("7z " + description + " is too large");
            }
            return (int) value;
        }

        /// Reads raw bytes.
        private byte[] readBytes(int size) throws IOException {
            if (size < 0) {
                throw new IllegalArgumentException("size must be non-negative");
            }
            int end = checkedEnd(size);
            byte[] result = Arrays.copyOfRange(bytes, position, end);
            position = end;
            return result;
        }

        /// Reads a little-endian `int`.
        private int readIntLE() throws IOException {
            int b0 = readUnsignedByte();
            int b1 = readUnsignedByte();
            int b2 = readUnsignedByte();
            int b3 = readUnsignedByte();
            return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
        }

        /// Reads a little-endian `long`.
        private long readLongLE() throws IOException {
            long low = Integer.toUnsignedLong(readIntLE());
            long high = Integer.toUnsignedLong(readIntLE());
            return low | (high << 32);
        }

        /// Skips raw bytes.
        private void skipBytes(int size) throws IOException {
            position(checkedEnd(size));
        }

        /// Returns the checked end position of a property payload.
        private int checkedEnd(int size) throws IOException {
            long end = (long) position + size;
            if (end > bytes.length) {
                throw new IOException("7z property exceeds header size");
            }
            return (int) end;
        }

        /// Requires this input to be fully consumed.
        private void requireFullyConsumed() throws IOException {
            if (position != bytes.length) {
                throw new IOException("Trailing bytes in 7z header: " + Arrays.toString(
                        Arrays.copyOfRange(bytes, position, bytes.length)
                ));
            }
        }

        /// Requires the current position to match the expected position.
        private void requirePosition(int expected) throws IOException {
            if (position != expected) {
                throw new IOException("7z property parser stopped at an unexpected position");
            }
        }
    }

    /// Opens packed 7z streams by absolute archive offset.
    @FunctionalInterface
    interface PackedStreamOpener {
        /// Opens a packed stream with the given absolute archive offset and byte size.
        InputStream open(long offset, long size) throws IOException;
    }
}
