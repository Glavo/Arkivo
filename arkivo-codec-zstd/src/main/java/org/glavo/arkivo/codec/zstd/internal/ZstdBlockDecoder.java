// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;
import org.glavo.arkivo.internal.ByteArrayAccess;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.Arrays;

/// Decodes Zstandard raw, run-length, and compressed blocks into a bounded history window.
@NotNullByDefault
final class ZstdBlockDecoder {
    /// Maximum decompressed size of one block.
    static final int MAX_BLOCK_SIZE = 128 * 1024;

    /// Baselines for literal-length codes.
    private static final int @Unmodifiable [] LITERAL_BASELINES = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
            16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512,
            1024, 2048, 4096, 8192, 16384, 32768, 65536
    };

    /// Extra-bit counts for literal-length codes.
    private static final int @Unmodifiable [] LITERAL_BITS = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11, 12,
            13, 14, 15, 16
    };

    /// Baselines for match-length codes.
    private static final int @Unmodifiable [] MATCH_BASELINES = {
            3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
            17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
            30, 31, 32, 33, 34, 35, 37, 39, 41, 43, 47, 51, 59,
            67, 83, 99, 131, 259, 515, 1027, 2051, 4099, 8195,
            16387, 32771, 65539
    };

    /// Extra-bit counts for match-length codes.
    private static final int @Unmodifiable [] MATCH_BITS = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11,
            12, 13, 14, 15, 16
    };

    /// Predefined literal-length table.
    private static final ZstdEntropy.FseTable DEFAULT_LITERAL_TABLE =
            ZstdSequenceEntropy.LITERAL_LENGTH_DECODER;

    /// Predefined match-length table.
    private static final ZstdEntropy.FseTable DEFAULT_MATCH_TABLE =
            ZstdSequenceEntropy.MATCH_LENGTH_DECODER;

    /// Predefined offset table.
    private static final ZstdEntropy.FseTable DEFAULT_OFFSET_TABLE =
            ZstdSequenceEntropy.OFFSET_DECODER;

    /// Configured dictionary context.
    private final ZstdDictionary dictionary;

    /// Ring buffer containing prior frame output.
    private final ZstdHistory history;

    /// Current literal workspace.
    private final byte[] literals = new byte[MAX_BLOCK_SIZE];

    /// Current Huffman table.
    private @Nullable ZstdEntropy.HuffmanTable huffmanTable;

    /// Current literal-length table.
    private @Nullable ZstdEntropy.FseTable literalLengthTable;

    /// Current offset table.
    private @Nullable ZstdEntropy.FseTable offsetTable;

    /// Current match-length table.
    private @Nullable ZstdEntropy.FseTable matchLengthTable;

    /// Most-recent match offset.
    private long repeatedOffset1;

    /// Second-most-recent match offset.
    private long repeatedOffset2;

    /// Third-most-recent match offset.
    private long repeatedOffset3;

    /// Number of bytes produced before the current block.
    private long frameSize;

    /// Creates a block decoder for one frame.
    ZstdBlockDecoder(long windowSize, ZstdDictionary dictionary) throws IOException {
        if (windowSize < 0L) {
            throw new IllegalArgumentException("windowSize must not be negative");
        }
        this.dictionary = dictionary;
        this.history = new ZstdHistory(windowSize);
        this.huffmanTable = dictionary.huffmanTable();
        this.literalLengthTable = dictionary.literalLengthTable();
        this.offsetTable = dictionary.offsetTable();
        this.matchLengthTable = dictionary.matchLengthTable();
        this.repeatedOffset1 = dictionary.repeatedOffset1();
        this.repeatedOffset2 = dictionary.repeatedOffset2();
        this.repeatedOffset3 = dictionary.repeatedOffset3();
    }

    /// Returns the number of bytes produced in the frame.
    long frameSize() {
        return frameSize;
    }

    /// Decodes one raw block payload.
    byte[] decodeRaw(byte[] payload) throws IOException {
        if (payload.length > MAX_BLOCK_SIZE) {
            throw new IOException("Zstandard raw block exceeds 128 KiB");
        }
        appendHistory(payload);
        return payload;
    }

    /// Decodes one run-length block.
    byte[] decodeRle(int value, int size) throws IOException {
        if (size < 0 || size > MAX_BLOCK_SIZE) {
            throw new IOException("Zstandard RLE block exceeds 128 KiB");
        }
        byte[] output = new byte[size];
        Arrays.fill(output, (byte) value);
        appendHistory(output);
        return output;
    }

    /// Decodes one compressed block payload.
    byte[] decodeCompressed(byte[] source) throws IOException {
        LiteralsResult literalResult = decodeLiterals(source);
        int position = literalResult.bytesRead();
        if (position >= source.length) {
            throw new IOException("Missing Zstandard sequences section");
        }
        int firstCount = Byte.toUnsignedInt(source[position++]);
        int sequenceCount;
        if (firstCount < 128) {
            sequenceCount = firstCount;
        } else if (firstCount < 255) {
            requireAvailable(source, position, 1);
            sequenceCount = ((firstCount - 128) << 8) + Byte.toUnsignedInt(source[position++]);
        } else {
            requireAvailable(source, position, 2);
            sequenceCount = readUnsignedShort(source, position) + 0x7f00;
            position += 2;
        }
        if (sequenceCount == 0) {
            if (position != source.length) {
                throw new IOException("Zstandard zero-sequence block contains extraneous data");
            }
            byte[] output = Arrays.copyOf(literals, literalResult.literalSize());
            appendHistory(output);
            return output;
        }

        requireAvailable(source, position, 1);
        int modes = Byte.toUnsignedInt(source[position++]);
        if ((modes & 3) != 0) {
            throw new IOException("Reserved Zstandard sequence mode bits are set");
        }
        TableResult literalTableResult = readSequenceTable(
                source,
                position,
                source.length,
                modes >>> 6,
                35,
                9,
                DEFAULT_LITERAL_TABLE,
                literalLengthTable,
                "literal length"
        );
        literalLengthTable = literalTableResult.table();
        position += literalTableResult.bytesRead();

        TableResult offsetTableResult = readSequenceTable(
                source,
                position,
                source.length,
                (modes >>> 4) & 3,
                31,
                8,
                DEFAULT_OFFSET_TABLE,
                offsetTable,
                "offset"
        );
        offsetTable = offsetTableResult.table();
        position += offsetTableResult.bytesRead();

        TableResult matchTableResult = readSequenceTable(
                source,
                position,
                source.length,
                (modes >>> 2) & 3,
                52,
                9,
                DEFAULT_MATCH_TABLE,
                matchLengthTable,
                "match length"
        );
        matchLengthTable = matchTableResult.table();
        position += matchTableResult.bytesRead();

        if (position >= source.length) {
            throw new IOException("Missing Zstandard sequence bitstream");
        }
        ZstdEntropy.ReverseBitReader bits = new ZstdEntropy.ReverseBitReader(source, position, source.length);
        ZstdEntropy.FseTable literalTable = literalLengthTable;
        ZstdEntropy.FseTable offsetsTable = offsetTable;
        ZstdEntropy.FseTable matchesTable = matchLengthTable;
        int literalState = bits.readBits(literalTable.tableLog());
        int offsetState = bits.readBits(offsetsTable.tableLog());
        int matchState = bits.readBits(matchesTable.tableLog());

        byte[] output = new byte[MAX_BLOCK_SIZE];
        int outputPosition = 0;
        int literalPosition = 0;
        for (int sequence = 0; sequence < sequenceCount; sequence++) {
            int literalCode = literalTable.symbol(literalState);
            int offsetCode = offsetsTable.symbol(offsetState);
            int matchCode = matchesTable.symbol(matchState);
            if (literalCode >= LITERAL_BASELINES.length
                    || matchCode >= MATCH_BASELINES.length
                    || offsetCode > 31) {
                throw new IOException("Invalid Zstandard sequence code");
            }

            long offsetValue = (1L << offsetCode)
                    + Integer.toUnsignedLong(bits.readBits(offsetCode));
            int matchLength = MATCH_BASELINES[matchCode] + bits.readBits(MATCH_BITS[matchCode]);
            int literalLength = LITERAL_BASELINES[literalCode] + bits.readBits(LITERAL_BITS[literalCode]);
            if (literalLength > literalResult.literalSize() - literalPosition
                    || literalLength > MAX_BLOCK_SIZE - outputPosition) {
                throw new IOException("Zstandard sequence exceeds its literal section");
            }
            System.arraycopy(literals, literalPosition, output, outputPosition, literalLength);
            literalPosition += literalLength;
            outputPosition += literalLength;

            long distance = resolveOffset(offsetValue, literalLength);
            if (matchLength > MAX_BLOCK_SIZE - outputPosition) {
                throw new IOException("Zstandard sequence exceeds the block size limit");
            }
            copyMatch(output, outputPosition, matchLength, distance);
            outputPosition += matchLength;

            if (sequence + 1 < sequenceCount) {
                literalState = literalTable.nextState(literalState, bits);
                matchState = matchesTable.nextState(matchState, bits);
                offsetState = offsetsTable.nextState(offsetState, bits);
            }
        }
        bits.requireFullyConsumed();

        int remainingLiterals = literalResult.literalSize() - literalPosition;
        if (remainingLiterals > MAX_BLOCK_SIZE - outputPosition) {
            throw new IOException("Zstandard literals exceed the block size limit");
        }
        System.arraycopy(literals, literalPosition, output, outputPosition, remainingLiterals);
        outputPosition += remainingLiterals;
        byte[] result = Arrays.copyOf(output, outputPosition);
        appendHistory(result);
        return result;
    }

    /// Decodes one literal section.
    private LiteralsResult decodeLiterals(byte[] source) throws IOException {
        requireAvailable(source, 0, 1);
        int headerByte = Byte.toUnsignedInt(source[0]);
        int type = headerByte & 3;
        int sizeFormat = (headerByte >>> 2) & 3;
        int regeneratedSize;
        int compressedSize;
        int headerSize;
        int streamCount;
        if (type <= 1) {
            if (sizeFormat == 0 || sizeFormat == 2) {
                headerSize = 1;
                regeneratedSize = headerByte >>> 3;
            } else if (sizeFormat == 1) {
                headerSize = 2;
                requireAvailable(source, 0, headerSize);
                regeneratedSize = readUnsignedShort(source, 0) >>> 4;
            } else {
                headerSize = 3;
                requireAvailable(source, 0, headerSize);
                regeneratedSize = readUnsigned24(source) >>> 4;
            }
            compressedSize = type == 0 ? regeneratedSize : 1;
            streamCount = 0;
        } else {
            if (sizeFormat <= 1) {
                headerSize = 3;
                requireAvailable(source, 0, headerSize);
                int header = readUnsigned24(source);
                regeneratedSize = (header >>> 4) & 0x3ff;
                compressedSize = (header >>> 14) & 0x3ff;
                streamCount = sizeFormat == 0 ? 1 : 4;
            } else if (sizeFormat == 2) {
                headerSize = 4;
                requireAvailable(source, 0, headerSize);
                long header = readUnsignedInt(source);
                regeneratedSize = (int) ((header >>> 4) & 0x3fff);
                compressedSize = (int) (header >>> 18);
                streamCount = 4;
            } else {
                headerSize = 5;
                requireAvailable(source, 0, headerSize);
                long header = readUnsigned40(source);
                regeneratedSize = (int) ((header >>> 4) & 0x3ffff);
                compressedSize = (int) ((header >>> 22) & 0x3ffff);
                streamCount = 4;
            }
        }
        if (regeneratedSize > MAX_BLOCK_SIZE) {
            throw new IOException("Zstandard literal section exceeds 128 KiB");
        }
        requireAvailable(source, headerSize, compressedSize);
        if (type == 0) {
            System.arraycopy(source, headerSize, literals, 0, regeneratedSize);
        } else if (type == 1) {

            Arrays.fill(literals, 0, regeneratedSize, source[headerSize]);
        } else {
            int payloadOffset = headerSize;
            int payloadLimit = headerSize + compressedSize;
            if (type == 2) {
                ZstdEntropy.HuffmanParseResult parsed =
                        ZstdEntropy.readHuffmanTable(source, payloadOffset, payloadLimit);
                huffmanTable = parsed.table();
                payloadOffset += parsed.bytesRead();
            } else if (huffmanTable == null) {
                throw new IOException("Zstandard treeless literal section has no prior Huffman table");
            }
            decodeHuffmanStreams(
                    huffmanTable,
                    source,
                    payloadOffset,
                    payloadLimit,
                    regeneratedSize,
                    streamCount
            );
        }
        return new LiteralsResult(regeneratedSize, headerSize + compressedSize);
    }

    /// Decodes one or four Huffman streams.
    private void decodeHuffmanStreams(
            ZstdEntropy.HuffmanTable table,
            byte[] source,
            int offset,
            int limit,
            int outputSize,
            int streamCount
    ) throws IOException {
        if (streamCount == 1) {
            table.decode(source, offset, limit, literals, 0, outputSize);
            return;
        }
        if (outputSize < 4) {
            throw new IOException("Four-stream Zstandard Huffman literals are too short");
        }
        requireAvailable(source, offset, 6);
        int stream1 = readUnsignedShort(source, offset);
        int stream2 = readUnsignedShort(source, offset + 2);
        int stream3 = readUnsignedShort(source, offset + 4);
        int stream4 = limit - (offset + 6) - stream1 - stream2 - stream3;
        if (stream1 <= 0 || stream2 <= 0 || stream3 <= 0 || stream4 <= 0) {
            throw new IOException("Invalid Zstandard Huffman stream sizes");
        }
        int decoded1 = (outputSize + 3) >>> 2;
        int decoded4 = outputSize - decoded1 * 3;
        int input = offset + 6;
        table.decode(source, input, input + stream1, literals, 0, decoded1);
        input += stream1;
        table.decode(source, input, input + stream2, literals, decoded1, decoded1);
        input += stream2;
        table.decode(source, input, input + stream3, literals, decoded1 * 2, decoded1);
        input += stream3;
        table.decode(source, input, limit, literals, decoded1 * 3, decoded4);
    }

    /// Reads one sequence-code FSE table according to its mode.
    private static TableResult readSequenceTable(
            byte[] source,
            int offset,
            int limit,
            int mode,
            int maximumSymbol,
            int maximumTableLog,
            ZstdEntropy.FseTable predefined,
            @Nullable ZstdEntropy.FseTable previous,
            String name
    ) throws IOException {
        return switch (mode) {
            case 0 -> new TableResult(predefined, 0);
            case 1 -> {
                requireAvailable(source, offset, 1);
                int symbol = Byte.toUnsignedInt(source[offset]);
                if (symbol > maximumSymbol) {
                    throw new IOException("Invalid Zstandard " + name + " RLE symbol");
                }
                yield new TableResult(ZstdEntropy.FseTable.rle(symbol), 1);
            }
            case 2 -> {
                ZstdEntropy.FseParseResult parsed =
                        ZstdEntropy.readFseTable(source, offset, limit, maximumSymbol, maximumTableLog);
                yield new TableResult(parsed.table(), parsed.bytesRead());
            }
            case 3 -> {
                if (previous == null) {
                    throw new IOException("Zstandard " + name + " repeat mode has no prior table");
                }
                yield new TableResult(previous, 0);
            }
            default -> throw new AssertionError(mode);
        };
    }

    /// Resolves and updates one explicit or repeated offset.
    private long resolveOffset(long offsetValue, int literalLength) throws IOException {
        long distance;
        if (offsetValue > 3L) {
            distance = offsetValue - 3L;
            repeatedOffset3 = repeatedOffset2;
            repeatedOffset2 = repeatedOffset1;
            repeatedOffset1 = requireDistance(distance);
            return repeatedOffset1;
        }

        int selected = (int) offsetValue;
        if (literalLength == 0) {
            selected++;
        }
        if (selected == 1) {
            return repeatedOffset1;
        }
        if (selected == 2) {
            long distance2 = repeatedOffset2;
            repeatedOffset2 = repeatedOffset1;
            repeatedOffset1 = distance2;
            return distance2;
        }
        if (selected == 3) {
            long distance3 = repeatedOffset3;
            repeatedOffset3 = repeatedOffset2;
            repeatedOffset2 = repeatedOffset1;
            repeatedOffset1 = distance3;
            return distance3;
        }
        long distance4 = repeatedOffset1 - 1L;
        if (distance4 == 0) {
            throw new IOException("Invalid zero Zstandard repeated offset");
        }
        repeatedOffset3 = repeatedOffset2;
        repeatedOffset2 = repeatedOffset1;
        repeatedOffset1 = distance4;
        return distance4;
    }

    /// Converts one positive offset to an array-compatible distance.
    private static long requireDistance(long distance) throws IOException {
        if (distance <= 0L || distance > 0xffff_fffcL) {
            throw new IOException("Zstandard match offset is out of range");
        }
        return distance;
    }

    /// Copies one potentially overlapping match from block, frame history, or dictionary content.
    private void copyMatch(byte[] output, int outputPosition, int length, long distance) throws IOException {
        for (int index = 0; index < length; index++) {
            int current = outputPosition + index;
            if (distance <= current) {
                output[current] = output[(int) (current - distance)];
                continue;
            }
            long beforeBlock = distance - current;
            if (beforeBlock <= history.size()) {
                output[current] = history.get(beforeBlock);
                continue;
            }

            long absolutePosition = frameSize + current;
            long dictionaryDistance = distance - absolutePosition;
            byte[] dictionaryContent = dictionary.content();
            if (dictionaryDistance <= 0L
                    || absolutePosition > history.capacity()
                    || dictionaryDistance > dictionaryContent.length) {
                throw new IOException("Zstandard match offset exceeds available history");
            }
            output[current] = dictionaryContent[dictionaryContent.length - (int) dictionaryDistance];
        }
    }
    /// Appends a completed block to frame history.
    private void appendHistory(byte[] block) throws IOException {
        for (byte value : block) {
            history.append(value);
        }
        frameSize += block.length;
    }

    /// Requires one byte range to be available.
    private static void requireAvailable(byte[] source, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > source.length - length) {
            throw new IOException("Truncated Zstandard compressed block");
        }
    }

    /// Reads an unsigned little-endian 16-bit value.
    private static int readUnsignedShort(byte[] source, int offset) {
        return Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(source, offset));
    }

    /// Reads an unsigned little-endian 24-bit value.
    private static int readUnsigned24(byte[] source) {
        return readUnsignedShort(source, 0)
                | Byte.toUnsignedInt(source[2]) << 16;
    }

    /// Reads an unsigned little-endian 32-bit value.
    private static long readUnsignedInt(byte[] source) {
        return Integer.toUnsignedLong(ByteArrayAccess.readIntLittleEndian(source, 0));
    }

    /// Reads an unsigned little-endian 40-bit value.
    private static long readUnsigned40(byte[] source) {
        return readUnsignedInt(source)
                | (long) Byte.toUnsignedInt(source[4]) << 32;
    }

    /// Holds decoded literal metadata.
    private record LiteralsResult(int literalSize, int bytesRead) {
        /// Creates literal metadata.
        private LiteralsResult {
        }
    }

    /// Holds a selected sequence table and its encoded size.
    private record TableResult(ZstdEntropy.FseTable table, int bytesRead) {
        /// Creates selected table metadata.
        private TableResult {
        }
    }
}
