// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Objects;

/// Streams raw RAR5 LZ bytes through the format's standard post-processing filters.
@NotNullByDefault
final class Rar5OutputPipeline {
    /// The delta filter identifier.
    static final int FILTER_DELTA = 0;

    /// The x86 CALL filter identifier.
    static final int FILTER_X86_E8 = 1;

    /// The x86 CALL/JMP filter identifier.
    static final int FILTER_X86_E8_E9 = 2;

    /// The ARM branch filter identifier.
    static final int FILTER_ARM = 3;

    /// The largest filter block permitted by RAR5.
    static final int MAX_FILTER_SIZE = 1 << 22;

    /// The largest number of filters accepted for one entry.
    private static final int MAX_FILTER_COUNT = 8192;

    /// The size of the unfiltered output staging buffer.
    private static final int OUTPUT_BUFFER_SIZE = 64 * 1024;

    /// The caller-owned final output.
    private final OutputStream output;

    /// The absolute raw dictionary position at the start of this entry.
    private final long entryStartPosition;

    /// The declared number of bytes produced for this entry.
    private final long expectedSize;

    /// Pending non-overlapping filters in stream order.
    private final ArrayDeque<Filter> filters = new ArrayDeque<>();

    /// Unfiltered bytes waiting for a bulk output write.
    private final byte[] outputBuffer = new byte[OUTPUT_BUFFER_SIZE];

    /// The number of staged bytes in {@link #outputBuffer}.
    private int outputBufferSize;

    /// The number of raw bytes accepted for this entry.
    private long acceptedSize;

    /// The number of final bytes forwarded to the caller.
    private long writtenSize;

    /// The absolute end of the last registered filter.
    private long lastFilterEnd;

    /// The total number of filters registered for this entry.
    private int filterCount;

    /// Creates an output pipeline for one compressed entry.
    Rar5OutputPipeline(OutputStream output, long entryStartPosition, long expectedSize) {
        this.output = Objects.requireNonNull(output, "output");
        if (entryStartPosition < 0L || expectedSize < 0L) {
            throw new IllegalArgumentException("Invalid RAR5 output range");
        }
        this.entryStartPosition = entryStartPosition;
        this.expectedSize = expectedSize;
        this.lastFilterEnd = entryStartPosition;
    }

    /// Returns the absolute raw position immediately after bytes already accepted for this entry.
    long rawPosition() {
        return entryStartPosition + acceptedSize;
    }

    /// Registers one future filter whose start offset is relative to the current raw position.
    void registerFilter(long startOffset, long size, int type, int channels) throws IOException {
        if (startOffset < 0L || startOffset > 0xffff_ffffL) {
            throw new IOException("Invalid RAR5 filter start offset");
        }
        if (size < 0L || size > MAX_FILTER_SIZE) {
            throw new IOException("RAR5 filter exceeds the supported block size");
        }
        if (type < FILTER_DELTA || type > FILTER_ARM) {
            throw new IOException("Unsupported RAR5 filter type: " + type);
        }
        if (type == FILTER_DELTA && (channels < 1 || channels > 32)) {
            throw new IOException("Invalid RAR5 delta channel count");
        }
        if (filterCount >= MAX_FILTER_COUNT) {
            throw new IOException("RAR5 entry contains too many filters");
        }

        long start;
        long end;
        try {
            start = Math.addExact(rawPosition(), startOffset);
            end = Math.addExact(start, size);
        } catch (ArithmeticException exception) {
            throw new IOException("RAR5 filter position overflows", exception);
        }
        if (start < lastFilterEnd) {
            throw new IOException("RAR5 filters overlap or are out of order");
        }
        long entryEnd;
        try {
            entryEnd = Math.addExact(entryStartPosition, expectedSize);
        } catch (ArithmeticException exception) {
            throw new IOException("RAR5 entry output position overflows", exception);
        }
        if (end > entryEnd) {
            throw new IOException("RAR5 filter extends beyond the declared entry size");
        }

        filterCount++;
        lastFilterEnd = end;
        if (size != 0L) {
            filters.addLast(new Filter(start, (int) size, type, channels));
        }
    }

    /// Accepts one raw LZ byte and emits it when any covering filter is complete.
    void accept(int value) throws IOException {
        if (acceptedSize >= expectedSize) {
            throw new IOException("RAR5 decompressor exceeded the declared unpacked size");
        }

        long position = rawPosition();
        Filter filter = filters.peekFirst();
        if (filter == null || position < filter.startPosition) {
            stage(value);
        } else {
            if (position != filter.startPosition + filter.filledSize) {
                throw new IOException("RAR5 filter stream position is inconsistent");
            }
            filter.data[filter.filledSize++] = (byte) value;
            if (filter.filledSize == filter.data.length) {
                flushStaged();
                byte[] transformed = applyFilter(
                        filter.type,
                        filter.channels,
                        filter.startPosition - entryStartPosition,
                        filter.data
                );
                writeFinal(transformed, 0, transformed.length);
                filters.removeFirst();
            }
        }
        acceptedSize++;
    }

    /// Completes the entry and verifies that all declared bytes and filters were produced.
    void finish() throws IOException {
        if (acceptedSize != expectedSize) {
            throw new IOException(
                    "RAR5 decompressor produced " + acceptedSize + " bytes; expected " + expectedSize
            );
        }
        if (!filters.isEmpty()) {
            throw new IOException("RAR5 compressed data ended inside a filter block");
        }
        flushStaged();
        if (writtenSize != expectedSize) {
            throw new IOException("RAR5 filter pipeline produced an invalid output size");
        }
    }

    /// Applies one standard RAR5 filter and returns its final bytes.
    static byte[] applyFilter(int type, int channels, long fileOffset, byte[] source) throws IOException {
        Objects.requireNonNull(source, "source");
        return switch (type) {
            case FILTER_DELTA -> applyDelta(channels, source);
            case FILTER_X86_E8 -> applyX86(false, fileOffset, source);
            case FILTER_X86_E8_E9 -> applyX86(true, fileOffset, source);
            case FILTER_ARM -> applyArm(fileOffset, source);
            default -> throw new IOException("Unsupported RAR5 filter type: " + type);
        };
    }

    /// Reconstructs interleaved channels from delta-coded bytes.
    private static byte[] applyDelta(int channels, byte[] source) throws IOException {
        if (channels < 1 || channels > 32) {
            throw new IOException("Invalid RAR5 delta channel count");
        }
        byte[] result = new byte[source.length];
        int sourceIndex = 0;
        for (int channel = 0; channel < channels; channel++) {
            int previous = 0;
            for (int destination = channel; destination < result.length; destination += channels) {
                previous = (previous - source[sourceIndex++]) & 0xff;
                result[destination] = (byte) previous;
            }
        }
        return result;
    }

    /// Restores transformed 24-bit relative x86 branch addresses.
    private static byte[] applyX86(boolean includeJump, long fileOffset, byte[] source) {
        byte[] result = source.clone();
        int end = result.length - 4;
        for (int index = 0; index < end; index++) {
            int opcode = result[index] & 0xff;
            if (opcode != 0xe8 && (!includeJump || opcode != 0xe9)) {
                continue;
            }

            int addressIndex = index + 1;
            int address = readInt32LittleEndian(result, addressIndex);
            int instructionOffset = (int) (fileOffset + addressIndex) & 0x00ff_ffff;
            if (Integer.compareUnsigned(address, 1 << 24) < 0) {
                address -= instructionOffset;
            } else if (Integer.compareUnsigned(address, -instructionOffset) >= 0) {
                address += 1 << 24;
            } else {
                index += 4;
                continue;
            }
            writeInt32LittleEndian(result, addressIndex, address);
            index += 4;
        }
        return result;
    }

    /// Restores transformed ARM branch immediates.
    private static byte[] applyArm(long fileOffset, byte[] source) {
        byte[] result = source.clone();
        int alignedLength = result.length & ~3;
        for (int index = 0; index < alignedLength; index += 4) {
            if ((result[index + 3] & 0xff) != 0xeb) {
                continue;
            }
            int instruction = readInt32LittleEndian(result, index);
            int instructionOffset = (int) (fileOffset + index) >>> 2;
            instruction = (instruction & 0xff00_0000)
                    | ((instruction - instructionOffset) & 0x00ff_ffff);
            writeInt32LittleEndian(result, index, instruction);
        }
        return result;
    }

    /// Reads one little-endian 32-bit integer from a filter block.
    private static int readInt32LittleEndian(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | (data[offset + 1] & 0xff) << 8
                | (data[offset + 2] & 0xff) << 16
                | (data[offset + 3] & 0xff) << 24;
    }

    /// Writes one little-endian 32-bit integer into a filter block.
    private static void writeInt32LittleEndian(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    /// Stages one unfiltered byte for a later bulk write.
    private void stage(int value) throws IOException {
        outputBuffer[outputBufferSize++] = (byte) value;
        if (outputBufferSize == outputBuffer.length) {
            flushStaged();
        }
    }

    /// Writes all currently staged unfiltered bytes.
    private void flushStaged() throws IOException {
        if (outputBufferSize != 0) {
            writeFinal(outputBuffer, 0, outputBufferSize);
            outputBufferSize = 0;
        }
    }

    /// Forwards final bytes to the caller while enforcing the declared size.
    private void writeFinal(byte[] data, int offset, int length) throws IOException {
        if (writtenSize > expectedSize - length) {
            throw new IOException("RAR5 filter pipeline exceeded the declared unpacked size");
        }
        output.write(data, offset, length);
        writtenSize += length;
    }

    /// Holds one pending non-overlapping post-processing filter.
    @NotNullByDefault
    private static final class Filter {
        /// The absolute first raw byte covered by this filter.
        private final long startPosition;

        /// The filter identifier.
        private final int type;

        /// The delta channel count, or zero for non-delta filters.
        private final int channels;

        /// Raw bytes collected for this filter.
        private final byte[] data;

        /// The number of raw filter bytes collected so far.
        private int filledSize;

        /// Creates one pending filter descriptor and its bounded data buffer.
        private Filter(long startPosition, int size, int type, int channels) {
            this.startPosition = startPosition;
            this.type = type;
            this.channels = channels;
            this.data = new byte[size];
        }
    }
}
