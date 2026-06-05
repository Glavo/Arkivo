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
        while (true) {
            int property = input.readId();
            if (property == K_END) {
                input.requireFullyConsumed();
                return List.copyOf(entries);
            }
            switch (property) {
                case K_ADDITIONAL_STREAMS_INFO, K_MAIN_STREAMS_INFO -> skipStreamsInfo(input);
                case K_FILES_INFO -> entries.addAll(readFilesInfo(input));
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

    /// Reads a `FilesInfo` block.
    private static List<SevenZipEntryMetadata> readFilesInfo(HeaderInput input) throws IOException {
        int fileCount = input.readIntNumber("file count");
        String[] names = new String[fileCount];
        boolean[] emptyStreams = new boolean[fileCount];
        boolean[] emptyFiles = new boolean[fileCount];

        while (true) {
            int property = input.readId();
            if (property == K_END) {
                return buildEntries(names, emptyStreams, emptyFiles);
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
            boolean[] emptyFiles
    ) throws IOException {
        ArrayList<SevenZipEntryMetadata> entries = new ArrayList<>(names.length);
        for (int index = 0; index < names.length; index++) {
            String name = names[index];
            if (name == null || name.isEmpty()) {
                throw new IOException("7z file entry is missing a name");
            }
            if (!emptyStreams[index]) {
                throw new UnsupportedOperationException("7z non-empty file streams are not implemented yet");
            }
            boolean directory = !emptyFiles[index];
            entries.add(new SevenZipEntryMetadata(name, directory, 0L));
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
