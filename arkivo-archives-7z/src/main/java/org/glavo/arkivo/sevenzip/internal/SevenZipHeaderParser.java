// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/// Parses unencoded 7z metadata headers.
@NotNullByDefault
public final class SevenZipHeaderParser {
    /// The `kEnd` property ID.
    private static final int K_END = 0x00;

    /// The `kHeader` property ID.
    private static final int K_HEADER = 0x01;

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

    /// The `kEmptyStream` property ID.
    private static final int K_EMPTY_STREAM = 0x0e;

    /// The `kEmptyFile` property ID.
    private static final int K_EMPTY_FILE = 0x0f;

    /// The `kName` property ID.
    private static final int K_NAME = 0x11;

    /// The `kEncodedHeader` property ID.
    private static final int K_ENCODED_HEADER = 0x17;

    /// Creates no instances.
    private SevenZipHeaderParser() {
    }

    /// Parses entries from a validated 7z next header.
    public static List<SevenZipEntryMetadata> parseEntries(byte[] header) throws IOException {
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
            throw new UnsupportedOperationException("7z encoded headers are not implemented yet");
        }
        if (type != K_HEADER) {
            throw new IOException("Unexpected 7z next header type: " + type);
        }

        ArrayList<SevenZipEntryMetadata> entries = new ArrayList<>();
        StreamsInfo streamsInfo = StreamsInfo.EMPTY;
        while (true) {
            int property = input.readId();
            if (property == K_END) {
                input.requireFullyConsumed();
                return List.copyOf(entries);
            }
            switch (property) {
                case K_ADDITIONAL_STREAMS_INFO -> skipStreamsInfo(input);
                case K_MAIN_STREAMS_INFO -> streamsInfo = readStreamsInfo(input);
                case K_FILES_INFO -> entries.addAll(readFilesInfo(input, streamsInfo));
                default -> throw new UnsupportedOperationException(
                        "Unsupported 7z header property: 0x" + Integer.toHexString(property)
                );
            }
        }
    }

    /// Skips an empty streams info block.
    private static void skipStreamsInfo(HeaderInput input) throws IOException {
        while (true) {
            int property = input.readId();
            if (property == K_END) {
                return;
            }
            throw new UnsupportedOperationException(
                    "7z packed streams are not implemented yet: 0x" + Integer.toHexString(property)
            );
        }
    }

    /// Reads a `StreamsInfo` block for unpacked Copy streams.
    private static StreamsInfo readStreamsInfo(HeaderInput input) throws IOException {
        PackInfo packInfo = PackInfo.EMPTY;
        long[] unpackSizes = new long[0];
        while (true) {
            int property = input.readId();
            switch (property) {
                case K_END -> {
                    return new StreamsInfo(packInfo.packPosition, packInfo.packSizes, unpackSizes);
                }
                case K_PACK_INFO -> packInfo = readPackInfo(input);
                case K_UNPACK_INFO -> unpackSizes = readUnPackInfo(input);
                case K_SUBSTREAMS_INFO -> skipStreamsInfo(input);
                default -> throw new UnsupportedOperationException(
                        "Unsupported 7z streams info property: 0x" + Integer.toHexString(property)
                );
            }
        }
    }

    /// Reads a `PackInfo` block.
    private static PackInfo readPackInfo(HeaderInput input) throws IOException {
        long packPosition = input.readNumber();
        int streamCount = input.readIntNumber("pack stream count");
        long[] packSizes = new long[streamCount];
        while (true) {
            int property = input.readId();
            switch (property) {
                case K_END -> {
                    return new PackInfo(packPosition, packSizes);
                }
                case K_SIZE -> {
                    for (int index = 0; index < streamCount; index++) {
                        packSizes[index] = input.readNumber();
                    }
                }
                case K_CRC -> skipDigests(input, streamCount);
                default -> throw new UnsupportedOperationException(
                        "Unsupported 7z pack info property: 0x" + Integer.toHexString(property)
                );
            }
        }
    }

    /// Reads an `UnPackInfo` block and returns one unpack size per supported Copy folder.
    private static long[] readUnPackInfo(HeaderInput input) throws IOException {
        int folderCount = -1;
        while (true) {
            int property = input.readId();
            switch (property) {
                case K_END -> {
                    if (folderCount < 0) {
                        return new long[0];
                    }
                    throw new IOException("7z unpack sizes are missing");
                }
                case K_FOLDER -> folderCount = readCopyFolders(input);
                case K_CODERS_UNPACK_SIZE -> {
                    if (folderCount < 0) {
                        throw new IOException("7z unpack sizes appeared before folders");
                    }
                    long[] unpackSizes = new long[folderCount];
                    for (int index = 0; index < folderCount; index++) {
                        unpackSizes[index] = input.readNumber();
                    }
                    requireUnPackInfoEnd(input);
                    return unpackSizes;
                }
                case K_CRC -> {
                    if (folderCount < 0) {
                        throw new IOException("7z folder CRC appeared before folders");
                    }
                    skipDigests(input, folderCount);
                }
                default -> throw new UnsupportedOperationException(
                        "Unsupported 7z unpack info property: 0x" + Integer.toHexString(property)
                );
            }
        }
    }

    /// Requires the remaining `UnPackInfo` block to contain only supported optional data and `kEnd`.
    private static void requireUnPackInfoEnd(HeaderInput input) throws IOException {
        while (true) {
            int property = input.readId();
            switch (property) {
                case K_END -> {
                    return;
                }
                case K_CRC -> skipDigests(input, 1);
                default -> throw new UnsupportedOperationException(
                        "Unsupported 7z unpack info property after sizes: 0x" + Integer.toHexString(property)
                );
            }
        }
    }

    /// Reads folders and requires each folder to use a single Copy coder.
    private static int readCopyFolders(HeaderInput input) throws IOException {
        int folderCount = input.readIntNumber("folder count");
        int external = input.readUnsignedByte();
        if (external != 0) {
            throw new UnsupportedOperationException("7z external folder storage is not implemented yet");
        }
        for (int index = 0; index < folderCount; index++) {
            readCopyFolder(input);
        }
        return folderCount;
    }

    /// Reads one folder and requires it to use a single Copy coder.
    private static void readCopyFolder(HeaderInput input) throws IOException {
        int coderCount = input.readIntNumber("coder count");
        if (coderCount != 1) {
            throw new UnsupportedOperationException("7z multi-coder folders are not implemented yet");
        }

        int flags = input.readUnsignedByte();
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
        if (attributes) {
            input.skipBytes(input.readIntNumber("coder properties size"));
        }

        if (inputStreamCount != 1 || outputStreamCount != 1 || methodId.length != 1 || methodId[0] != 0) {
            throw new UnsupportedOperationException("Only 7z Copy folders are implemented yet");
        }
    }

    /// Reads a `FilesInfo` block.
    private static List<SevenZipEntryMetadata> readFilesInfo(
            HeaderInput input,
            StreamsInfo streamsInfo
    ) throws IOException {
        int fileCount = input.readIntNumber("file count");
        String[] names = new String[fileCount];
        boolean[] emptyStreams = new boolean[fileCount];
        boolean[] emptyFiles = new boolean[fileCount];

        while (true) {
            int property = input.readId();
            if (property == K_END) {
                return buildEntries(names, emptyStreams, emptyFiles, streamsInfo);
            }

            int size = input.readIntNumber("file property size");
            int end = input.checkedEnd(size);
            switch (property) {
                case K_EMPTY_STREAM -> readBooleanVector(input, emptyStreams);
                case K_EMPTY_FILE -> readEmptyFileVector(input, emptyStreams, emptyFiles);
                case K_NAME -> readNames(input, end, names);
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
            StreamsInfo streamsInfo
    ) throws IOException {
        ArrayList<SevenZipEntryMetadata> entries = new ArrayList<>(names.length);
        int streamIndex = 0;
        for (int index = 0; index < names.length; index++) {
            String name = names[index];
            if (name == null || name.isEmpty()) {
                throw new IOException("7z file entry is missing a name");
            }
            if (emptyStreams[index]) {
                boolean directory = !emptyFiles[index];
                entries.add(new SevenZipEntryMetadata(
                        name,
                        directory,
                        0L,
                        SevenZipEntryMetadata.NO_DATA_OFFSET
                ));
            } else {
                entries.add(new SevenZipEntryMetadata(
                        name,
                        false,
                        streamsInfo.unpackSize(streamIndex),
                        streamsInfo.dataOffset(streamIndex)
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
        boolean[] emptyFileValues = new boolean[countTrue(emptyStreams)];
        readBooleanVector(input, emptyFileValues);

        int valueIndex = 0;
        for (int index = 0; index < emptyStreams.length; index++) {
            if (emptyStreams[index]) {
                emptyFiles[index] = emptyFileValues[valueIndex++];
            }
        }
    }

    /// Reads UTF-16LE null-terminated names.
    private static void readNames(HeaderInput input, int end, String[] names) throws IOException {
        int external = input.readUnsignedByte();
        if (external != 0) {
            throw new UnsupportedOperationException("7z external name storage is not implemented yet");
        }

        for (int index = 0; index < names.length; index++) {
            int start = input.position();
            while (true) {
                if (input.position() + 1 >= end) {
                    throw new IOException("Unterminated 7z file name");
                }
                int low = input.readUnsignedByte();
                int high = input.readUnsignedByte();
                if (low == 0 && high == 0) {
                    int byteLength = input.position() - start - 2;
                    names[index] = new String(input.bytes(), start, byteLength, StandardCharsets.UTF_16LE);
                    break;
                }
            }
        }
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

    /// Skips CRC digest definitions.
    private static void skipDigests(HeaderInput input, int count) throws IOException {
        boolean[] defined = new boolean[count];
        int allAreDefined = input.readUnsignedByte();
        if (allAreDefined != 0) {
            Arrays.fill(defined, true);
        } else {
            readBooleanVector(input, defined);
        }
        for (boolean value : defined) {
            if (value) {
                input.skipBytes(4);
            }
        }
    }

    /// Stores parsed pack stream information.
    @NotNullByDefault
    private static final class PackInfo {
        /// The empty pack information value.
        private static final PackInfo EMPTY = new PackInfo(0L, new long[0]);

        /// The first pack stream position relative to the first byte after the signature header.
        private final long packPosition;

        /// The pack stream sizes.
        private final long[] packSizes;

        /// Creates pack stream information.
        private PackInfo(long packPosition, long[] packSizes) {
            this.packPosition = packPosition;
            this.packSizes = packSizes.clone();
        }
    }

    /// Stores parsed Copy stream information.
    @NotNullByDefault
    private static final class StreamsInfo {
        /// The empty streams information value.
        private static final StreamsInfo EMPTY = new StreamsInfo(0L, new long[0], new long[0]);

        /// The first pack stream position relative to the first byte after the signature header.
        private final long packPosition;

        /// The pack stream sizes.
        private final long[] packSizes;

        /// The unpack stream sizes.
        private final long[] unpackSizes;

        /// Creates stream information.
        private StreamsInfo(long packPosition, long[] packSizes, long[] unpackSizes) {
            if (packSizes.length != unpackSizes.length) {
                throw new IllegalArgumentException("packSizes and unpackSizes must have the same length");
            }
            this.packPosition = packPosition;
            this.packSizes = packSizes.clone();
            this.unpackSizes = unpackSizes.clone();
        }

        /// Returns the number of unpack streams.
        private int streamCount() {
            return unpackSizes.length;
        }

        /// Returns the unpack size for a stream.
        private long unpackSize(int index) throws IOException {
            requireStreamIndex(index);
            return unpackSizes[index];
        }

        /// Returns the absolute archive data offset for a stream.
        private long dataOffset(int index) throws IOException {
            requireStreamIndex(index);
            long offset = SevenZipSignatureHeader.SIZE + packPosition;
            for (int i = 0; i < index; i++) {
                offset = Math.addExact(offset, packSizes[i]);
            }
            return offset;
        }

        /// Requires a stream index to be valid and supported.
        private void requireStreamIndex(int index) throws IOException {
            if (index < 0 || index >= unpackSizes.length) {
                throw new IOException("7z file entry references a missing stream");
            }
            if (packSizes[index] != unpackSizes[index]) {
                throw new UnsupportedOperationException("7z compressed streams are not implemented yet");
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
                    return value | ((long) (first & (mask - 1)) << (8 * index));
                }
                value |= (long) readUnsignedByte() << (8 * index);
                mask >>>= 1;
            }
            return value;
        }

        /// Reads a 7z variable-length integer that must fit in an `int`.
        private int readIntNumber(String description) throws IOException {
            long value = readNumber();
            if (value > Integer.MAX_VALUE) {
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
}
