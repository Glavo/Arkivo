// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;
import org.glavo.arkivo.internal.ByteArrayAccess;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/// Decodes Zstandard raw, run-length, and compressed blocks into a bounded history window.
///
/// Returned buffers borrow internal storage and must be consumed before decoding another block.
/// A failed decoding attempt may overwrite a previous result. History is independent of returned views.
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
    private final ZstdDictionaryContext dictionary;

    /// Ring buffer containing prior frame output.
    private final ZstdHistory history;

    /// Workspace for decoded literals and raw literals returned by zero-sequence blocks.
    private byte @Nullable [] literals;

    /// Lazily allocated RLE and match-expansion workspace, reused within this frame.
    private byte @Nullable [] outputWorkspace;

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

    /// Creates a block decoder and resets the supplied history for exclusive use by this frame.
    ZstdBlockDecoder(ZstdHistory history, ZstdDictionaryContext dictionary) {
        this.dictionary = dictionary;
        this.history = history;
        history.reset();
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

    /// Adds the first `length` bytes of one completely delivered raw block to history.
    void appendRaw(byte[] payload, int length) throws IOException {
        Objects.checkFromIndexSize(0, length, payload.length);
        if (length > MAX_BLOCK_SIZE) {
            throw new IOException("Zstandard raw block exceeds 128 KiB");
        }
        history.append(payload, length);
        frameSize += length;
    }

    /// Decodes one run-length block and returns a read-only buffer containing exactly its bytes.
    @UnmodifiableView ByteBuffer decodeRle(int value, int size) throws IOException {
        if (size < 0 || size > MAX_BLOCK_SIZE) {
            throw new IOException("Zstandard RLE block exceeds 128 KiB");
        }
        outputWorkspace = prepareWorkspace(outputWorkspace, size);
        byte[] output = outputWorkspace;
        Arrays.fill(output, 0, size, (byte) value);
        return finishBlock(output, size);
    }

    /// Decodes `source[0..limit)` and returns a read-only output view positioned at zero.
    /// The maximum output size is the frame's block-size limit, between zero and [#MAX_BLOCK_SIZE].
    @UnmodifiableView ByteBuffer decodeCompressed(byte[] source, int limit, int maximumOutputSize) throws IOException {
        Objects.checkFromIndexSize(0, limit, source.length);
        Objects.checkIndex(maximumOutputSize, MAX_BLOCK_SIZE + 1);
        LiteralsResult literalResult = decodeLiterals(source, limit);
        byte[] currentLiterals = literalResult.bytes();
        int literalLimit = literalResult.offset() + literalResult.literalSize();
        if (literalResult.literalSize() > maximumOutputSize) {
            throw new IOException("Zstandard literals exceed the frame block-size limit");
        }
        int position = literalResult.bytesRead();
        if (position >= limit) {
            throw new IOException("Missing Zstandard sequences section");
        }
        int firstCount = Byte.toUnsignedInt(source[position++]);
        int sequenceCount;
        if (firstCount < 128) {
            sequenceCount = firstCount;
        } else if (firstCount < 255) {
            requireAvailable(limit, position, 1);
            sequenceCount = ((firstCount - 128) << 8) + Byte.toUnsignedInt(source[position++]);
        } else {
            requireAvailable(limit, position, 2);
            sequenceCount = readUnsignedShort(source, position) + 0x7f00;
            position += 2;
        }
        if (sequenceCount == 0) {
            if (position != limit) {
                throw new IOException("Zstandard zero-sequence block contains extraneous data");
            }
            if (currentLiterals == source) {
                // Returned views must not borrow the input array after this call.
                literals = prepareWorkspace(literals, literalResult.literalSize());
                System.arraycopy(source, literalResult.offset(), literals, 0, literalResult.literalSize());
                currentLiterals = literals;
            }
            return finishBlock(currentLiterals, literalResult.literalSize());
        }

        requireAvailable(limit, position, 1);
        int modes = Byte.toUnsignedInt(source[position++]);
        if ((modes & 3) != 0) {
            throw new IOException("Reserved Zstandard sequence mode bits are set");
        }
        TableResult literalTableResult = readSequenceTable(
                source,
                position,
                limit,
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
                limit,
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
                limit,
                (modes >>> 2) & 3,
                52,
                9,
                DEFAULT_MATCH_TABLE,
                matchLengthTable,
                "match length"
        );
        matchLengthTable = matchTableResult.table();
        position += matchTableResult.bytesRead();

        if (position >= limit) {
            throw new IOException("Missing Zstandard sequence bitstream");
        }
        ZstdEntropy.ReverseBitReader bits = new ZstdEntropy.ReverseBitReader(source, position, limit);
        ZstdEntropy.FseTable literalTable = literalLengthTable;
        ZstdEntropy.FseTable offsetsTable = offsetTable;
        ZstdEntropy.FseTable matchesTable = matchLengthTable;
        int literalState = bits.readBits(literalTable.tableLog());
        int offsetState = bits.readBits(offsetsTable.tableLog());
        int matchState = bits.readBits(matchesTable.tableLog());

        outputWorkspace = prepareWorkspace(outputWorkspace, maximumOutputSize);
        byte[] output = outputWorkspace;
        int outputPosition = 0;
        int literalPosition = literalResult.offset();
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
            if (literalLength > literalLimit - literalPosition
                    || literalLength > maximumOutputSize - outputPosition) {
                throw new IOException("Zstandard sequence exceeds its literal section");
            }
            System.arraycopy(currentLiterals, literalPosition, output, outputPosition, literalLength);
            literalPosition += literalLength;
            outputPosition += literalLength;

            long distance = resolveOffset(offsetValue, literalLength);
            if (matchLength > maximumOutputSize - outputPosition) {
                throw new IOException("Decoded Zstandard block exceeds the frame block-size limit");
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

        int remainingLiterals = literalLimit - literalPosition;
        if (remainingLiterals > maximumOutputSize - outputPosition) {
            throw new IOException("Zstandard literals exceed the block size limit");
        }
        System.arraycopy(currentLiterals, literalPosition, output, outputPosition, remainingLiterals);
        outputPosition += remainingLiterals;
        return finishBlock(output, outputPosition);
    }

    /// Locates raw literals in the input or decodes encoded literals into the reusable workspace.
    private LiteralsResult decodeLiterals(byte[] source, int limit) throws IOException {
        requireAvailable(limit, 0, 1);
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
                requireAvailable(limit, 0, headerSize);
                regeneratedSize = readUnsignedShort(source, 0) >>> 4;
            } else {
                headerSize = 3;
                requireAvailable(limit, 0, headerSize);
                regeneratedSize = readUnsigned24(source) >>> 4;
            }
            compressedSize = type == 0 ? regeneratedSize : 1;
            streamCount = 0;
        } else {
            if (sizeFormat <= 1) {
                headerSize = 3;
                requireAvailable(limit, 0, headerSize);
                int header = readUnsigned24(source);
                regeneratedSize = (header >>> 4) & 0x3ff;
                compressedSize = (header >>> 14) & 0x3ff;
                streamCount = sizeFormat == 0 ? 1 : 4;
            } else if (sizeFormat == 2) {
                headerSize = 4;
                requireAvailable(limit, 0, headerSize);
                long header = readUnsignedInt(source);
                regeneratedSize = (int) ((header >>> 4) & 0x3fff);
                compressedSize = (int) (header >>> 18);
                streamCount = 4;
            } else {
                headerSize = 5;
                requireAvailable(limit, 0, headerSize);
                long header = readUnsigned40(source);
                regeneratedSize = (int) ((header >>> 4) & 0x3ffff);
                compressedSize = (int) ((header >>> 22) & 0x3ffff);
                streamCount = 4;
            }
        }
        if (regeneratedSize > MAX_BLOCK_SIZE) {
            throw new IOException("Zstandard literal section exceeds 128 KiB");
        }
        requireAvailable(limit, headerSize, compressedSize);
        if (type == 0) {
            return new LiteralsResult(source, headerSize, regeneratedSize, headerSize + compressedSize);
        }
        literals = prepareWorkspace(literals, regeneratedSize);
        byte[] currentLiterals = literals;
        if (type == 1) {
            Arrays.fill(currentLiterals, 0, regeneratedSize, source[headerSize]);
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
            if (streamCount == 1) {
                huffmanTable.decode(source, payloadOffset, payloadLimit, currentLiterals, 0, regeneratedSize);
            } else {
                huffmanTable.decodeFourStreams(source, payloadOffset, payloadLimit, currentLiterals, regeneratedSize);
            }
        }
        return new LiteralsResult(currentLiterals, 0, regeneratedSize, headerSize + compressedSize);
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
                requireAvailable(limit, offset, 1);
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
        while (distance > outputPosition && length > 0) {
            long beforeBlock = distance - outputPosition;
            int copied;
            if (beforeBlock <= history.size()) {
                copied = (int) Math.min(length, beforeBlock);
                history.copy(beforeBlock, output, outputPosition, copied);
            } else {
                long absolutePosition = frameSize + outputPosition;
                long dictionaryDistance = distance - absolutePosition;
                byte[] dictionaryContent = dictionary.content();
                if (dictionaryDistance <= 0L
                        || absolutePosition > history.capacity()
                        || dictionaryDistance > dictionaryContent.length) {
                    throw new IOException("Zstandard match offset exceeds available history");
                }
                copied = (int) Math.min(length, dictionaryDistance);
                if (copied - 1L > history.capacity() - absolutePosition) {
                    throw new IOException("Zstandard match offset exceeds available history");
                }
                System.arraycopy(dictionaryContent, dictionaryContent.length - (int) dictionaryDistance,
                        output, outputPosition, copied);
            }
            outputPosition += copied;
            length -= copied;
        }
        if (length == 0) return;
        if (distance == 1) {
            Arrays.fill(output, outputPosition, outputPosition + length, output[outputPosition - 1]);
            return;
        }
        int copied = Math.min(length, (int) distance);
        System.arraycopy(output, outputPosition - (int) distance, output, outputPosition, copied);
        // Each copied range becomes a complete, nonoverlapping source for the next range.
        while (copied < length) {
            int extension = Math.min(copied, length - copied);
            System.arraycopy(output, outputPosition, output, outputPosition + copied, extension);
            copied += extension;
        }
    }

    /// Reuses sufficient storage or grows it geometrically, without preserving old contents.
    /// The requested size must already have been validated to lie between zero and [#MAX_BLOCK_SIZE].
    private static byte[] prepareWorkspace(byte @Nullable [] workspace, int size) {
        if (workspace != null && workspace.length >= size) return workspace;
        int capacity = workspace == null ? size
                : Math.min(MAX_BLOCK_SIZE, Math.max(size, workspace.length * 2));
        return new byte[capacity];
    }

    /// Saves only the completed prefix in history and exposes a read-only view bounded to that prefix.
    private @UnmodifiableView ByteBuffer finishBlock(byte[] block, int length) throws IOException {
        history.append(block, length);
        frameSize += length;
        return ByteBuffer.wrap(block, 0, length).slice().asReadOnlyBuffer();
    }

    /// Requires one byte range to lie within the current block's logical input limit.
    private static void requireAvailable(int limit, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > limit - length) {
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

    /// Describes the literal range used while decoding one block.
    ///
    /// @param bytes borrowed input or decoded-literal workspace
    /// @param offset first literal byte in the array
    /// @param literalSize number of literal bytes
    /// @param bytesRead encoded size of the literal section, including its header
    @NotNullByDefault
    private record LiteralsResult(byte @UnmodifiableView [] bytes, int offset, int literalSize, int bytesRead) {
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
