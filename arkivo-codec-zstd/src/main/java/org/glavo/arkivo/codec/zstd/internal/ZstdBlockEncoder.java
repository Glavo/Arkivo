// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/// Encodes Zstandard blocks while retaining frame-local entropy state.
@NotNullByDefault
final class ZstdBlockEncoder {
    /// Baselines for literal-length codes.
    private static final int @Unmodifiable [] LITERAL_BASELINES = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
            16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512,
            1024, 2048, 4096, 8192, 16384, 32768, 65536
    };

    /// Extra-bit counts for literal-length codes.
    private static final int @Unmodifiable [] LITERAL_BITS = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8,
            9, 10, 11, 12, 13, 14, 15, 16
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
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 3, 3, 4, 4,
            5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16
    };

    /// Huffman table established by an earlier compressed block in the current frame.
    private @Nullable ZstdLiteralEncoder.HuffmanEncoding huffmanTable;

    /// Literal-length table established by the dictionary or an earlier sequence section.
    private @Nullable ZstdEntropy.FseEncoderTable literalLengthTable;

    /// Offset table established by the dictionary or an earlier sequence section.
    private @Nullable ZstdEntropy.FseEncoderTable offsetTable;

    /// Match-length table established by the dictionary or an earlier sequence section.
    private @Nullable ZstdEntropy.FseEncoderTable matchLengthTable;

    /// Retained frame tail used as a cross-block match prefix.
    private byte @Unmodifiable [] history = new byte[0];

    /// Number of uncompressed bytes accepted in the current frame.
    private long frameSize;

    /// Maximum retained and searchable distance.
    private int historyLimit;

    /// Whether dictionary and repeated-offset state is known at this block boundary.
    private boolean contextualState;

    /// Most-recent match offset.
    private int repeatedOffset1;

    /// Second-most-recent match offset.
    private int repeatedOffset2;

    /// Third-most-recent match offset.
    private int repeatedOffset3;

    /// Initializes one independently scheduled job at its frame offset.
    void resetJob(
            ZstdEncoderParameters parameters,
            byte @Unmodifiable [] prefix,
            long frameOffset
    ) {
        if (frameOffset < 0L || prefix.length > frameOffset) {
            throw new IllegalArgumentException("Invalid Zstandard parallel job prefix");
        }
        if (frameOffset == 0L) {
            reset(parameters);
            return;
        }

        huffmanTable = null;
        literalLengthTable = null;
        offsetTable = null;
        matchLengthTable = null;
        historyLimit = matchDistanceLimit(parameters);
        int retained = Math.min(prefix.length, historyLimit);
        history = Arrays.copyOfRange(prefix, prefix.length - retained, prefix.length);
        frameSize = frameOffset;
        contextualState = false;
        repeatedOffset1 = 0;
        repeatedOffset2 = 0;
        repeatedOffset3 = 0;
    }

    /// Resets frame-local state to the configured dictionary.
    void reset(ZstdEncoderParameters parameters) {
        ZstdDictionaryContext dictionary = parameters.dictionary();
        huffmanTable = dictionary.huffmanEncoding();
        literalLengthTable =
                ZstdSequenceEntropy.invertLiteralLengths(dictionary.literalLengthTable());
        offsetTable = ZstdSequenceEntropy.invertOffsets(dictionary.offsetTable());
        matchLengthTable = ZstdSequenceEntropy.invertMatchLengths(dictionary.matchLengthTable());
        history = new byte[0];
        frameSize = 0L;
        historyLimit = matchDistanceLimit(parameters);
        contextualState = true;
        repeatedOffset1 = dictionary.repeatedOffset1();
        repeatedOffset2 = dictionary.repeatedOffset2();
        repeatedOffset3 = dictionary.repeatedOffset3();
    }

    /// Encodes one block including its three-byte block header.
    ///
    /// @param source block bytes
    /// @param length number of valid source bytes
    /// @param last whether this is the last block in its frame
    /// @param parameters encoder parameters
    byte[] encode(byte[] source, int length, boolean last, ZstdEncoderParameters parameters) {
        return encode(source, length, last, parameters, List.of());
    }

    /// Encodes one block with preplanned matches beyond the ordinary hash-chain distance.
    ///
    /// @param source block bytes
    /// @param length number of valid source bytes
    /// @param last whether this is the last block in its frame
    /// @param parameters encoder parameters
    /// @param longDistanceMatches verified frame-history matches for this block
    byte[] encode(
            byte[] source,
            int length,
            boolean last,
            ZstdEncoderParameters parameters,
            @Unmodifiable List<ZstdLongDistanceMatcher.Match> longDistanceMatches
    ) {
        if (length < 0 || length > source.length || length > parameters.blockSize()) {
            throw new IllegalArgumentException("Invalid Zstandard block length");
        }

        if (length > 0 && isRunLength(source, length)) {
            byte[] result = new byte[4];
            writeBlockHeader(result, length, 1, last);
            result[3] = source[0];
            appendHistory(source, length);
            return result;
        }

        CompressedEncoding compressed = encodeCompressed(
                source,
                length,
                parameters,
                longDistanceMatches
        );
        byte[] payload = compressed.payload();
        if (payload.length < length) {
            huffmanTable = compressed.huffmanTable();
            literalLengthTable = compressed.literalLengthTable();
            offsetTable = compressed.offsetTable();
            matchLengthTable = compressed.matchLengthTable();
            repeatedOffset1 = compressed.repeatedOffset1();
            repeatedOffset2 = compressed.repeatedOffset2();
            repeatedOffset3 = compressed.repeatedOffset3();
            byte[] result = new byte[3 + payload.length];
            writeBlockHeader(result, payload.length, 2, last);
            System.arraycopy(payload, 0, result, 3, payload.length);
            appendHistory(source, length);
            return result;
        }

        byte[] result = new byte[3 + length];
        writeBlockHeader(result, length, 0, last);
        System.arraycopy(source, 0, result, 3, length);
        appendHistory(source, length);
        return result;
    }

    /// Returns whether every byte in a non-empty block has the same value.
    private static boolean isRunLength(byte[] source, int length) {
        byte value = source[0];
        for (int index = 1; index < length; index++) {
            if (source[index] != value) {
                return false;
            }
        }
        return true;
    }

    /// Attempts to encode a sequence-compressed block.
    private CompressedEncoding encodeCompressed(
            byte[] source,
            int length,
            ZstdEncoderParameters parameters,
            @Unmodifiable List<ZstdLongDistanceMatcher.Match> longDistanceMatches
    ) {
        @Unmodifiable List<Sequence> sequences = findSequences(
                source,
                length,
                parameters,
                longDistanceMatches
        );
        if (sequences.isEmpty()) {
            return new CompressedEncoding(
                    source,
                    huffmanTable,
                    literalLengthTable,
                    offsetTable,
                    matchLengthTable,
                    repeatedOffset1,
                    repeatedOffset2,
                    repeatedOffset3
            );
        }

        int literalSize = length;
        for (Sequence sequence : sequences) {
            literalSize -= sequence.length();
        }
        byte[] literals = new byte[literalSize];
        int literalStart = 0;
        int literalOffset = 0;
        for (Sequence sequence : sequences) {
            int runLength = sequence.position() - literalStart;
            System.arraycopy(source, literalStart, literals, literalOffset, runLength);
            literalOffset += runLength;
            literalStart = sequence.position() + sequence.length();
        }
        System.arraycopy(source, literalStart, literals, literalOffset, length - literalStart);

        ByteArrayOutputStream output = new ByteArrayOutputStream(length);
        ZstdLiteralEncoder.LiteralEncoding literalEncoding =
                ZstdLiteralEncoder.encode(literals, huffmanTable);
        output.writeBytes(literalEncoding.bytes());
        writeSequenceCount(output, sequences.size());

        EncodedSequence[] encoded = new EncodedSequence[sequences.size()];
        int[] literalCodes = new int[sequences.size()];
        int[] offsetCodes = new int[sequences.size()];
        int[] matchCodes = new int[sequences.size()];
        RepeatedOffsets repeated =
                new RepeatedOffsets(repeatedOffset1, repeatedOffset2, repeatedOffset3);
        for (int index = 0; index < sequences.size(); index++) {
            Sequence sequence = sequences.get(index);
            int literalCode = literalLengthCode(sequence.literalLength());
            int matchCode = matchLengthCode(sequence.length());
            OffsetEncoding offsetEncoding = selectOffset(
                    sequence.distance(), sequence.literalLength(), repeated, contextualState
            );
            repeated = offsetEncoding.repeatedOffsets();
            long offsetValue = offsetEncoding.value();
            int offsetCode = 63 - Long.numberOfLeadingZeros(offsetValue);
            encoded[index] = new EncodedSequence(
                    sequence,
                    literalCode,
                    offsetCode,
                    matchCode,
                    offsetValue
            );
            literalCodes[index] = literalCode;
            offsetCodes[index] = offsetCode;
            matchCodes[index] = matchCode;
        }

        ZstdSequenceEntropy.TableEncoding literalsTable =
                ZstdSequenceEntropy.selectLiteralLengths(literalCodes, literalLengthTable);
        ZstdSequenceEntropy.TableEncoding offsetsTable =
                ZstdSequenceEntropy.selectOffsets(offsetCodes, offsetTable);
        ZstdSequenceEntropy.TableEncoding matchesTable =
                ZstdSequenceEntropy.selectMatchLengths(matchCodes, matchLengthTable);
        output.write(
                literalsTable.mode() << 6
                        | offsetsTable.mode() << 4
                        | matchesTable.mode() << 2
        );
        output.writeBytes(literalsTable.description());
        output.writeBytes(offsetsTable.description());
        output.writeBytes(matchesTable.description());

        ZstdEntropy.FseEncoderTable literalEncoder = literalsTable.table();
        ZstdEntropy.FseEncoderTable offsetEncoder = offsetsTable.table();
        ZstdEntropy.FseEncoderTable matchEncoder = matchesTable.table();
        ZstdEntropy.ReverseBitWriter bits = new ZstdEntropy.ReverseBitWriter();
        EncodedSequence last = encoded[encoded.length - 1];
        int literalState = literalEncoder.initialState(last.literalCode());
        int offsetState = offsetEncoder.initialState(last.offsetCode());
        int matchState = matchEncoder.initialState(last.matchCode());
        writeSequenceExtraBits(bits, last);

        for (int index = encoded.length - 2; index >= 0; index--) {
            EncodedSequence sequence = encoded[index];
            ZstdEntropy.FseTransition offsetTransition =
                    offsetEncoder.transition(sequence.offsetCode(), offsetState);
            bits.writeBits(offsetTransition.value(), offsetTransition.bitCount());
            offsetState = offsetTransition.state();

            ZstdEntropy.FseTransition matchTransition =
                    matchEncoder.transition(sequence.matchCode(), matchState);
            bits.writeBits(matchTransition.value(), matchTransition.bitCount());
            matchState = matchTransition.state();

            ZstdEntropy.FseTransition literalTransition =
                    literalEncoder.transition(sequence.literalCode(), literalState);
            bits.writeBits(literalTransition.value(), literalTransition.bitCount());
            literalState = literalTransition.state();
            writeSequenceExtraBits(bits, sequence);
        }

        bits.writeBits(matchState, matchEncoder.tableLog());
        bits.writeBits(offsetState, offsetEncoder.tableLog());
        bits.writeBits(literalState, literalEncoder.tableLog());
        output.writeBytes(bits.finish());
        return new CompressedEncoding(
                output.toByteArray(),
                literalEncoding.table(),
                literalEncoder,
                offsetEncoder,
                matchEncoder,
                repeated.first(),
                repeated.second(),
                repeated.third()
        );
    }

    /// Finds non-overlapping matches across the retained frame prefix and current block.
    private @Unmodifiable List<Sequence> findSequences(
            byte[] source,
            int length,
            ZstdEncoderParameters parameters,
            @Unmodifiable List<ZstdLongDistanceMatcher.Match> longDistanceMatches
    ) {
        @Unmodifiable List<ZstdMatchParser.Match> matches = ZstdMatchParser.parse(
                source,
                length,
                buildMatchPrefix(parameters),
                historyLimit,
                parameters,
                longDistanceMatches
        );
        ArrayList<Sequence> sequences = new ArrayList<>(matches.size());
        int literalStart = 0;
        for (ZstdMatchParser.Match match : matches) {
            sequences.add(new Sequence(
                    match.position(),
                    match.length(),
                    match.distance(),
                    match.position() - literalStart
            ));
            literalStart = match.position() + match.length();
        }
        return List.copyOf(sequences);
    }

    /// Builds the contiguous dictionary and frame-history prefix visible to this block.
    private byte[] buildMatchPrefix(ZstdEncoderParameters parameters) {
        int dictionarySize = 0;
        byte[] dictionary = parameters.dictionary().content();
        if (contextualState && frameSize < historyLimit) {
            int remaining = (int) ((long) historyLimit - frameSize);
            dictionarySize = Math.min(dictionary.length, remaining);
        }

        byte[] prefix = new byte[dictionarySize + history.length];
        if (dictionarySize != 0) {
            System.arraycopy(
                    dictionary,
                    dictionary.length - dictionarySize,
                    prefix,
                    0,
                    dictionarySize
            );
        }
        System.arraycopy(history, 0, prefix, dictionarySize, history.length);
        return prefix;
    }

    /// Returns the bounded distance retained by the current match finder.
    static int matchDistanceLimit(ZstdEncoderParameters parameters) {
        int windowSize = parameters.windowLog() >= 30
                ? Integer.MAX_VALUE
                : 1 << parameters.windowLog();
        return Math.min(windowSize, parameters.chainLimit());
    }

    /// Appends one uncompressed block to the retained frame tail.
    private void appendHistory(byte[] source, int length) {
        frameSize += length;
        if (length == 0 || historyLimit == 0) {
            return;
        }
        if (length >= historyLimit) {
            history = Arrays.copyOfRange(source, length - historyLimit, length);
            return;
        }

        int retained = Math.min(history.length, historyLimit - length);
        byte[] updated = new byte[retained + length];
        System.arraycopy(history, history.length - retained, updated, 0, retained);
        System.arraycopy(source, 0, updated, retained, length);
        history = updated;
    }

    /// Selects the canonical literal-length code covering a value.
    static int literalLengthCode(int value) {
        return lengthCode(value, LITERAL_BASELINES, LITERAL_BITS);
    }

    /// Selects the canonical match-length code covering a value.
    static int matchLengthCode(int value) {
        return lengthCode(value, MATCH_BASELINES, MATCH_BITS);
    }

    /// Selects the canonical code covering a length value.
    private static int lengthCode(int value, int[] baselines, int[] bits) {
        for (int code = baselines.length - 1; code >= 0; code--) {
            long maximum = Integer.toUnsignedLong(baselines[code]) + ((1L << bits[code]) - 1L);
            if (value >= baselines[code] && value <= maximum) {
                return code;
            }
        }
        throw new IllegalArgumentException("Zstandard sequence length is out of range");
    }

    /// Selects an explicit or repeated offset and returns the resulting decoder state.
    static OffsetEncoding selectOffset(
            int distance,
            int literalLength,
            RepeatedOffsets repeated,
            boolean repeatedEnabled
    ) {
        if (distance <= 0 || literalLength < 0) {
            throw new IllegalArgumentException("Invalid Zstandard sequence offset");
        }

        int first = repeated.first();
        int second = repeated.second();
        int third = repeated.third();
        if (repeatedEnabled) {
            if (literalLength != 0) {
                if (distance == first) {
                    return new OffsetEncoding(1L, repeated);
                }
                if (distance == second) {
                    return new OffsetEncoding(
                            2L,
                            new RepeatedOffsets(second, first, third)
                    );
                }
                if (distance == third) {
                    return new OffsetEncoding(
                            3L,
                            new RepeatedOffsets(third, first, second)
                    );
                }
            } else {
                if (distance == second) {
                    return new OffsetEncoding(
                            1L,
                            new RepeatedOffsets(second, first, third)
                    );
                }
                if (distance == third) {
                    return new OffsetEncoding(
                            2L,
                            new RepeatedOffsets(third, first, second)
                    );
                }
                if (first > 1 && distance == first - 1) {
                    return new OffsetEncoding(
                            3L,
                            new RepeatedOffsets(distance, first, second)
                    );
                }
            }
        }

        return new OffsetEncoding(
                Integer.toUnsignedLong(distance) + 3L,
                new RepeatedOffsets(distance, first, second)
        );
    }

    /// Writes the variable-width number of sequences field.
    private static void writeSequenceCount(ByteArrayOutputStream output, int count) {
        if (count < 0 || count > 0xffff + 0x7f00) {
            throw new IllegalArgumentException("Invalid Zstandard sequence count");
        }
        if (count < 128) {
            output.write(count);
        } else if (count < 0x7f00) {
            output.write(0x80 + (count >>> 8));
            output.write(count);
        } else {
            int adjusted = count - 0x7f00;
            output.write(0xff);
            output.write(adjusted);
            output.write(adjusted >>> 8);
        }
    }

    /// Writes one sequence's extra bits in reverse decoder order.
    private static void writeSequenceExtraBits(
            ZstdEntropy.ReverseBitWriter bits,
            EncodedSequence encoded
    ) {
        Sequence sequence = encoded.sequence();
        bits.writeBits(
                sequence.literalLength() - LITERAL_BASELINES[encoded.literalCode()],
                LITERAL_BITS[encoded.literalCode()]
        );
        bits.writeBits(
                sequence.length() - MATCH_BASELINES[encoded.matchCode()],
                MATCH_BITS[encoded.matchCode()]
        );
        bits.writeBits(
                encoded.offsetValue() - (1L << encoded.offsetCode()),
                encoded.offsetCode()
        );
    }

    /// Writes a little-endian block header.
    private static void writeBlockHeader(byte[] target, int payloadSize, int type, boolean last) {
        int header = payloadSize << 3 | type << 1 | (last ? 1 : 0);
        target[0] = (byte) header;
        target[1] = (byte) (header >>> 8);
        target[2] = (byte) (header >>> 16);
    }

    /// Holds a compressed block payload and frame-local state active after it.
    ///
    /// @param payload compressed block payload
    /// @param huffmanTable active Huffman table after decoding the payload
    /// @param literalLengthTable active literal-length table after decoding the payload
    /// @param offsetTable active offset table after decoding the payload
    /// @param matchLengthTable active match-length table after decoding the payload
    /// @param repeatedOffset1 active most-recent offset after decoding the payload
    /// @param repeatedOffset2 active second-most-recent offset after decoding the payload
    /// @param repeatedOffset3 active third-most-recent offset after decoding the payload
    private record CompressedEncoding(
            byte @Unmodifiable [] payload,
            @Nullable ZstdLiteralEncoder.HuffmanEncoding huffmanTable,
            @Nullable ZstdEntropy.FseEncoderTable literalLengthTable,
            @Nullable ZstdEntropy.FseEncoderTable offsetTable,
            @Nullable ZstdEntropy.FseEncoderTable matchLengthTable,
            int repeatedOffset1,
            int repeatedOffset2,
            int repeatedOffset3
    ) {
    }

    /// Holds one non-overlapping sequence selected by the match finder.
    ///
    /// @param position match start in the source block
    /// @param length match length
    /// @param distance backward match distance
    /// @param literalLength number of literals preceding this match
    private record Sequence(int position, int length, int distance, int literalLength) {
    }

    /// Holds sequence symbols and the offset value written to the bitstream.
    ///
    /// @param sequence selected sequence
    /// @param literalCode literal-length symbol
    /// @param offsetCode offset symbol
    /// @param matchCode match-length symbol
    /// @param offsetValue explicit or repeated offset value before its baseline is removed
    private record EncodedSequence(
            Sequence sequence,
            int literalCode,
            int offsetCode,
            int matchCode,
            long offsetValue
    ) {
    }

    /// Holds the three repeated offsets active at one sequence boundary.
    ///
    /// @param first most-recent offset
    /// @param second second-most-recent offset
    /// @param third third-most-recent offset
    record RepeatedOffsets(int first, int second, int third) {
    }

    /// Holds one encoded offset value and its resulting repeated-offset state.
    ///
    /// @param value encoded offset value before its power-of-two baseline is removed
    /// @param repeatedOffsets state active after decoding the offset
    record OffsetEncoding(long value, RepeatedOffsets repeatedOffsets) {
    }

    /// Creates an encoder with no frame-local Huffman table.
    ZstdBlockEncoder() {
    }
}
