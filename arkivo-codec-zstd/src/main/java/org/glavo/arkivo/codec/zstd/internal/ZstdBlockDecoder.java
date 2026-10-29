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
/// A failed decoding attempt may overwrite a previous result. Returned views cannot modify history.
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

    /// Packed value bases and extra-bit counts for literal-length codes.
    private static final long @Unmodifiable [] LITERAL_VALUES = SequenceTable.values(35);

    /// Packed value bases and extra-bit counts for match-length codes.
    private static final long @Unmodifiable [] MATCH_VALUES = SequenceTable.values(52);

    /// Packed value bases and extra-bit counts for offset codes.
    private static final long @Unmodifiable [] OFFSET_VALUES = SequenceTable.values(31);

    /// Predefined literal-length table.
    private static final SequenceTable DEFAULT_LITERAL_TABLE =
            SequenceTable.predefined(ZstdSequenceEntropy.LITERAL_LENGTH_DECODER, 35);

    /// Predefined match-length table.
    private static final SequenceTable DEFAULT_MATCH_TABLE =
            SequenceTable.predefined(ZstdSequenceEntropy.MATCH_LENGTH_DECODER, 52);

    /// Predefined offset table.
    private static final SequenceTable DEFAULT_OFFSET_TABLE =
            SequenceTable.predefined(ZstdSequenceEntropy.OFFSET_DECODER, 31);

    /// Configured dictionary context.
    private ZstdDictionaryContext dictionary = ZstdDictionaryContext.NONE;

    /// Retained frame output and, for eligible windows, storage for the next block.
    private final ZstdHistory history;

    /// Workspace for decoded literals and raw literals returned by zero-sequence blocks.
    private byte @Nullable [] literals;

    /// Separate RLE and match workspace, allocated only when history cannot provide block storage.
    private byte @Nullable [] outputWorkspace;

    /// Current Huffman table.
    private @Nullable ZstdEntropy.HuffmanTable huffmanTable;

    /// Current literal-length table.
    private @Nullable SequenceTable literalLengthTable;

    /// Current offset table.
    private @Nullable SequenceTable offsetTable;

    /// Current match-length table.
    private @Nullable SequenceTable matchLengthTable;

    /// Most-recent match offset.
    private long repeatedOffset1;

    /// Second-most-recent match offset.
    private long repeatedOffset2;

    /// Third-most-recent match offset.
    private long repeatedOffset3;

    /// Number of bytes produced before the current block.
    private long frameSize;

    /// Creates a block decoder and resets the supplied history for exclusive use by this frame.
    ///
    /// @throws IOException if a dictionary sequence table exceeds the format's limits
    ZstdBlockDecoder(ZstdHistory history, ZstdDictionaryContext dictionary) throws IOException {
        this.history = history;
        reset(dictionary);
    }

    /// Starts an independent frame in the same window, reusing only byte workspaces.
    void reset(ZstdDictionaryContext dictionary) throws IOException {
        clear();
        this.dictionary = dictionary;
        this.huffmanTable = dictionary.huffmanTable();
        @Nullable ZstdEntropy.FseTable literals = dictionary.literalLengthTable();
        @Nullable ZstdEntropy.FseTable offsets = dictionary.offsetTable();
        @Nullable ZstdEntropy.FseTable matches = dictionary.matchLengthTable();
        this.literalLengthTable = literals == null ? null : SequenceTable.expand(literals, 35, null);
        this.offsetTable = offsets == null ? null : SequenceTable.expand(offsets, 31, null);
        this.matchLengthTable = matches == null ? null : SequenceTable.expand(matches, 52, null);
        this.repeatedOffset1 = dictionary.repeatedOffset1();
        this.repeatedOffset2 = dictionary.repeatedOffset2();
        this.repeatedOffset3 = dictionary.repeatedOffset3();
    }

    /// Discards frame history, tables and dictionary references while retaining byte workspaces.
    void clear() {
        history.reset();
        dictionary = ZstdDictionaryContext.NONE;
        huffmanTable = null;
        literalLengthTable = null;
        offsetTable = null;
        matchLengthTable = null;
        repeatedOffset1 = 1;
        repeatedOffset2 = 4;
        repeatedOffset3 = 8;
        frameSize = 0;
    }

    /// Returns the window size required for reusing this decoder's history storage.
    long windowSize() {
        return history.capacity();
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
        byte @Nullable [] output = history.beginBlock(size);
        boolean sharedOutput = output != null;
        int start = 0;
        if (output == null) {
            outputWorkspace = prepareWorkspace(outputWorkspace, size);
            output = outputWorkspace;
        } else {
            start = history.blockOffset();
        }
        Arrays.fill(output, start, start + size, (byte) value);
        return finishBlock(output, start, size, sharedOutput);
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
            return finishBlock(currentLiterals, 0, literalResult.literalSize(), false);
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
        SequenceTable literalTable = literalLengthTable;
        SequenceTable offsetsTable = offsetTable;
        SequenceTable matchesTable = matchLengthTable;
        int literalState = bits.readBits(literalTable.tableLog());
        int offsetState = bits.readBits(offsetsTable.tableLog());
        int matchState = bits.readBits(matchesTable.tableLog());
        long[] literalEntries = literalTable.entries();
        long[] offsetEntries = offsetsTable.entries();
        long[] matchEntries = matchesTable.entries();

        byte @Nullable [] output = history.beginBlock(maximumOutputSize);
        boolean sharedOutput = output != null;
        int outputStart = 0;
        int historyStart = 0;
        if (output == null) {
            outputWorkspace = prepareWorkspace(outputWorkspace, maximumOutputSize);
            output = outputWorkspace;
        } else {
            outputStart = history.blockOffset();
            // Older bytes may remain in the array, but only the retained window can satisfy a match.
            historyStart = outputStart - (int) history.size();
        }
        int outputLimit = outputStart + maximumOutputSize;
        int outputPosition = outputStart;
        int literalPosition = literalResult.offset();
        long literalEntry = literalEntries[literalState];
        long offsetEntry = offsetEntries[offsetState];
        long matchEntry = matchEntries[matchState];
        for (int sequence = 1; sequence < sequenceCount; sequence++) {
            int offsetBits = (int) (offsetEntry >>> 32) & 31;
            int matchBits = (int) (matchEntry >>> 32) & 31;
            int literalBits = (int) (literalEntry >>> 32) & 31;
            int literalStateBits = (int) (literalEntry >>> 37) & 15;
            int matchStateBits = (int) (matchEntry >>> 37) & 15;
            int offsetStateBits = (int) (offsetEntry >>> 37) & 15;
            int stateBits = literalStateBits + matchStateBits + offsetStateBits;
            int totalBits = offsetBits + matchBits + literalBits + stateBits;
            long offsetValue;
            int matchLength;
            int literalLength;
            if (totalBits <= 56) {
                // One bounded load covers the common case, including all three state transitions.
                // From low to high: OF, ML and LL transitions, then LL, ML and OF extra bits.
                long fields = bits.readSequenceBits(totalBits);
                offsetState = (int) (offsetEntry >>> 41) + (int) (fields & ((1L << offsetStateBits) - 1));
                fields >>>= offsetStateBits;
                matchState = (int) (matchEntry >>> 41) + (int) (fields & ((1L << matchStateBits) - 1));
                fields >>>= matchStateBits;
                literalState = (int) (literalEntry >>> 41) + (int) (fields & ((1L << literalStateBits) - 1));
                fields >>>= literalStateBits;
                if ((literalBits | matchBits) == 0) {
                    literalLength = (int) literalEntry;
                    matchLength = (int) matchEntry;
                    offsetValue = (offsetEntry & 0xffff_ffffL) + fields;
                } else {
                    literalLength = (int) literalEntry + (int) (fields & ((1L << literalBits) - 1));
                    fields >>>= literalBits;
                    matchLength = (int) matchEntry + (int) (fields & ((1L << matchBits) - 1));
                    offsetValue = (offsetEntry & 0xffff_ffffL) + (fields >>> matchBits);
                }
            } else {
                offsetValue = (offsetEntry & 0xffff_ffffL) + Integer.toUnsignedLong(bits.readBits(offsetBits));
                matchLength = (int) matchEntry + bits.readBits(matchBits);
                literalLength = (int) literalEntry + bits.readBits(literalBits);
                literalState = (int) (literalEntry >>> 41) + bits.readBits(literalStateBits);
                matchState = (int) (matchEntry >>> 41) + bits.readBits(matchStateBits);
                offsetState = (int) (offsetEntry >>> 41) + bits.readBits(offsetStateBits);
            }
            // Fetch the next validated states before copying this sequence's output.
            literalEntry = literalEntries[literalState];
            offsetEntry = offsetEntries[offsetState];
            matchEntry = matchEntries[matchState];
            if (literalLength > literalLimit - literalPosition) {
                throw new IOException("Zstandard sequence exceeds its literal section");
            }
            // Each validated length fits in 18 bits, so their sum cannot overflow.
            if (literalLength + matchLength > outputLimit - outputPosition) {
                throw new IOException("Decoded Zstandard block exceeds the frame block-size limit");
            }
            if (literalLength <= Long.BYTES && literalPosition <= literalLimit - Long.BYTES
                    && outputPosition <= output.length - Long.BYTES) {
                // Only literalLength bytes belong to this run; subsequent output replaces the lookahead.
                long word = ByteArrayAccess.readLongLittleEndian(currentLiterals, literalPosition);
                ByteArrayAccess.writeLongLittleEndian(output, outputPosition, word);
            } else {
                copyNonOverlapping(currentLiterals, literalPosition, output, outputPosition, literalLength);
            }
            literalPosition += literalLength;
            outputPosition += literalLength;

            long distance = resolveOffset(offsetValue, literalLength);
            if (matchLength <= Long.BYTES && distance >= Long.BYTES
                    && distance <= outputPosition - historyStart && outputPosition <= output.length - Long.BYTES) {
                // Read only decoded bytes. Following workspace bytes are not committed with this match.
                long word = ByteArrayAccess.readLongLittleEndian(output, outputPosition - (int) distance);
                ByteArrayAccess.writeLongLittleEndian(output, outputPosition, word);
            } else {
                copyMatch(output, outputPosition, matchLength, distance, outputStart, historyStart);
            }
            outputPosition += matchLength;
        }

        // The final sequence has extra bits but no following FSE state transitions.
        long offsetValue = (offsetEntry & 0xffff_ffffL)
                + Integer.toUnsignedLong(bits.readBits((int) (offsetEntry >>> 32) & 31));
        int matchLength = (int) matchEntry + bits.readBits((int) (matchEntry >>> 32) & 31);
        int literalLength = (int) literalEntry + bits.readBits((int) (literalEntry >>> 32) & 31);
        if (literalLength > literalLimit - literalPosition) {
            throw new IOException("Zstandard sequence exceeds its literal section");
        }
        if (literalLength + matchLength > outputLimit - outputPosition) {
            throw new IOException("Decoded Zstandard block exceeds the frame block-size limit");
        }
        copyNonOverlapping(currentLiterals, literalPosition, output, outputPosition, literalLength);
        literalPosition += literalLength;
        outputPosition += literalLength;
        copyMatch(output, outputPosition, matchLength, resolveOffset(offsetValue, literalLength),
                outputStart, historyStart);
        outputPosition += matchLength;
        bits.requireFullyConsumed();

        int remainingLiterals = literalLimit - literalPosition;
        if (remainingLiterals > outputLimit - outputPosition) {
            throw new IOException("Zstandard literals exceed the block size limit");
        }
        copyNonOverlapping(currentLiterals, literalPosition, output, outputPosition, remainingLiterals);
        outputPosition += remainingLiterals;
        return finishBlock(output, outputStart, outputPosition - outputStart, sharedOutput);
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
            SequenceTable predefined,
            @Nullable SequenceTable previous,
            String name
    ) throws IOException {
        long @Nullable [] workspace = previous == null || previous == predefined ? null : previous.entries();
        return switch (mode) {
            case 0 -> new TableResult(predefined, 0);
            case 1 -> {
                requireAvailable(limit, offset, 1);
                int symbol = Byte.toUnsignedInt(source[offset]);
                if (symbol > maximumSymbol) {
                    throw new IOException("Invalid Zstandard " + name + " RLE symbol");
                }
                yield new TableResult(SequenceTable.expand(ZstdEntropy.FseTable.rle(symbol), maximumSymbol, workspace), 1);
            }
            case 2 -> {
                ZstdEntropy.FseParseResult parsed =
                        ZstdEntropy.readFseTable(source, offset, limit, maximumSymbol, maximumTableLog);
                yield new TableResult(SequenceTable.expand(parsed.table(), maximumSymbol, workspace), parsed.bytesRead());
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
            // Validated offset codes and their extra bits bound offsetValue to an unsigned int.
            distance = offsetValue - 3L;
            repeatedOffset3 = repeatedOffset2;
            repeatedOffset2 = repeatedOffset1;
            repeatedOffset1 = distance;
            return distance;
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

    /// Copies one potentially overlapping match from block, frame history, or dictionary content.
    /// The current block begins at `outputStart`; `historyStart` bounds the retained prefix in this array.
    /// Both are zero when history uses separate storage.
    private void copyMatch(byte[] output, int outputPosition, int length, long distance,
                           int outputStart, int historyStart) throws IOException {
        while (distance > outputPosition - historyStart && length > 0) {
            long beforeBlock = distance - (outputPosition - outputStart);
            int copied;
            if (beforeBlock <= history.size()) {
                copied = (int) Math.min(length, beforeBlock);
                history.copy(beforeBlock, output, outputPosition, copied);
            } else {
                long absolutePosition = frameSize + outputPosition - outputStart;
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
        if (length <= distance) {
            copyNonOverlapping(output, outputPosition - (int) distance, output, outputPosition, length);
            return;
        }
        if (distance == 1) {
            Arrays.fill(output, outputPosition, outputPosition + length, output[outputPosition - 1]);
            return;
        }
        int copied = (int) distance;
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

    /// Commits the completed block range and exposes a read-only view bounded to that range.
    private @UnmodifiableView ByteBuffer finishBlock(byte[] block, int start, int length, boolean sharedOutput) throws IOException {
        if (sharedOutput) {
            history.commitBlock(length);
        } else {
            history.append(block, length);
        }
        frameSize += length;
        return ByteBuffer.wrap(block, start, length).slice().asReadOnlyBuffer();
    }

    /// Requires one byte range to lie within the current block's logical input limit.
    private static void requireAvailable(int limit, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > limit - length) {
            throw new IOException("Truncated Zstandard compressed block");
        }
    }

    /// Copies validated, disjoint ranges, using bounded word transfers for eight to thirty-two bytes.
    private static void copyNonOverlapping(byte[] source, int start, byte[] target, int offset, int length) {
        if (length >= Long.BYTES && length <= Long.BYTES * 4) {
            long head = ByteArrayAccess.readLongLittleEndian(source, start);
            long tail = ByteArrayAccess.readLongLittleEndian(source, start + length - Long.BYTES);
            if (length > Long.BYTES * 2) {
                long innerHead = ByteArrayAccess.readLongLittleEndian(source, start + Long.BYTES);
                long innerTail = ByteArrayAccess.readLongLittleEndian(source, start + length - Long.BYTES * 2);
                ByteArrayAccess.writeLongLittleEndian(target, offset + Long.BYTES, innerHead);
                ByteArrayAccess.writeLongLittleEndian(target, offset + length - Long.BYTES * 2, innerTail);
            }
            ByteArrayAccess.writeLongLittleEndian(target, offset, head);
            ByteArrayAccess.writeLongLittleEndian(target, offset + length - Long.BYTES, tail);
        } else {
            System.arraycopy(source, start, target, offset, length);
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
    ///
    /// @param table the selected table
    /// @param bytesRead the number of bytes consumed by its description
    @NotNullByDefault
    private record TableResult(SequenceTable table, int bytesRead) {
        /// Creates selected table metadata.
        private TableResult {
        }
    }

    /// Stores decoded sequence values and FSE transitions in one entry per state.
    /// Every transition's full destination range lies within the table's logical state count.
    /// Non-predefined entries borrow a per-alphabet workspace that the next table replacement may overwrite.
    ///
    /// @param tableLog the logarithm of the number of states, or zero for RLE
    /// @param entries unsigned value bases in bits 0..31, extra-bit counts in bits 32..36,
    ///                transition widths in bits 37..40, and transition bases in bits 41..49
    @NotNullByDefault
    private record SequenceTable(int tableLog, long[] entries) {
        /// Borrows the expanded state entries until this table is replaced.
        private SequenceTable {
        }

        /// Expands a sequence alphabet into the supplied workspace, or a larger array if necessary.
        /// The maximum symbol is 35 for literal lengths, 52 for match lengths, or 31 for offsets.
        /// Out-of-range symbols are rejected before the table is used to decode sequences.
        static SequenceTable expand(ZstdEntropy.FseTable table, int maximumSymbol, long @Nullable [] workspace)
                throws IOException {
            if (table.tableLog() > 9) {
                throw new IOException("Zstandard sequence table is too large");
            }
            int stateCount = 1 << table.tableLog();
            long[] entries = workspace != null && workspace.length >= stateCount ? workspace : new long[stateCount];
            long @Unmodifiable [] values = maximumSymbol == 35 ? LITERAL_VALUES
                    : maximumSymbol == 52 ? MATCH_VALUES : OFFSET_VALUES;
            for (int state = 0; state < stateCount; state++) {
                int stateBits = table.numberOfBits(state);
                int stateBase = table.baseline(state);
                if (stateBits > table.tableLog() || stateBase > stateCount - (1 << stateBits)) {
                    throw new IOException("Invalid Zstandard FSE transition");
                }
                int symbol = table.symbol(state);
                if (symbol > maximumSymbol) {
                    throw new IOException("Invalid Zstandard sequence code");
                }
                entries[state] = values[symbol] | ((long) stateBits << 37) | ((long) stateBase << 41);
            }
            return new SequenceTable(table.tableLog(), entries);
        }

        /// Precomputes the value fields shared by all states of one sequence alphabet.
        private static long @Unmodifiable [] values(int maximumSymbol) {
            long[] values = new long[maximumSymbol + 1];
            for (int symbol = 0; symbol < values.length; symbol++) {
                int baseline;
                int extraBits;
                if (maximumSymbol == 35) {
                    baseline = LITERAL_BASELINES[symbol];
                    extraBits = LITERAL_BITS[symbol];
                } else if (maximumSymbol == 52) {
                    baseline = MATCH_BASELINES[symbol];
                    extraBits = MATCH_BITS[symbol];
                } else {
                    baseline = 1 << symbol;
                    extraBits = symbol;
                }
                values[symbol] = Integer.toUnsignedLong(baseline) | ((long) extraBits << 32);
            }
            return values;
        }

        /// Expands a predefined table whose alphabet is fixed by the format.
        private static SequenceTable predefined(ZstdEntropy.FseTable table, int maximumSymbol) {
            try {
                return expand(table, maximumSymbol, null);
            } catch (IOException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }
    }
}
