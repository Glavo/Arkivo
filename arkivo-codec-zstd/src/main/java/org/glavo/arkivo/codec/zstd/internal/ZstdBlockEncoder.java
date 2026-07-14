// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/// Encodes independent Zstandard blocks using raw, run-length, or sequence-compressed payloads.
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

    /// Encodes one block including its three-byte block header.
    ///
    /// @param source block bytes
    /// @param length number of valid source bytes
    /// @param last whether this is the last block in its frame
    /// @param parameters encoder parameters
    static byte[] encode(byte[] source, int length, boolean last, ZstdEncoderParameters parameters) {
        if (length < 0 || length > source.length || length > parameters.blockSize()) {
            throw new IllegalArgumentException("Invalid Zstandard block length");
        }

        if (length > 0 && isRunLength(source, length)) {
            byte[] result = new byte[4];
            writeBlockHeader(result, length, 1, last);
            result[3] = source[0];
            return result;
        }

        byte[] compressed = encodeCompressed(source, length, parameters);
        if (compressed.length < length) {
            byte[] result = new byte[3 + compressed.length];
            writeBlockHeader(result, compressed.length, 2, last);
            System.arraycopy(compressed, 0, result, 3, compressed.length);
            return result;
        }

        byte[] result = new byte[3 + length];
        writeBlockHeader(result, length, 0, last);
        System.arraycopy(source, 0, result, 3, length);
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

    /// Attempts to encode a sequence-compressed block with raw literals.
    private static byte[] encodeCompressed(byte[] source, int length, ZstdEncoderParameters parameters) {
        @Unmodifiable List<Sequence> sequences = findSequences(source, length, parameters);
        if (sequences.isEmpty()) {
            return source;
        }

        int literalSize = length;
        for (Sequence sequence : sequences) {
            literalSize -= sequence.length();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(length);
        writeRawLiteralsHeader(output, literalSize);
        int literalStart = 0;
        for (Sequence sequence : sequences) {
            output.write(source, literalStart, sequence.position() - literalStart);
            literalStart = sequence.position() + sequence.length();
        }
        output.write(source, literalStart, length - literalStart);

        writeSequenceCount(output, sequences.size());
        output.write(0);

        EncodedSequence[] encoded = new EncodedSequence[sequences.size()];
        for (int index = 0; index < sequences.size(); index++) {
            Sequence sequence = sequences.get(index);
            int literalCode = lengthCode(sequence.literalLength(), LITERAL_BASELINES, LITERAL_BITS);
            int matchCode = lengthCode(sequence.length(), MATCH_BASELINES, MATCH_BITS);
            long offsetValue = Integer.toUnsignedLong(sequence.distance()) + 3L;
            int offsetCode = 63 - Long.numberOfLeadingZeros(offsetValue);
            encoded[index] = new EncodedSequence(sequence, literalCode, offsetCode, matchCode, offsetValue);
        }

        ReverseBitWriter bits = new ReverseBitWriter();
        EncodedSequence last = encoded[encoded.length - 1];
        int literalState = ZstdSequenceEntropy.LITERAL_LENGTH_ENCODER.initialState(last.literalCode());
        int offsetState = ZstdSequenceEntropy.OFFSET_ENCODER.initialState(last.offsetCode());
        int matchState = ZstdSequenceEntropy.MATCH_LENGTH_ENCODER.initialState(last.matchCode());
        writeSequenceExtraBits(bits, last);

        for (int index = encoded.length - 2; index >= 0; index--) {
            EncodedSequence sequence = encoded[index];
            ZstdSequenceEntropy.Transition offsetTransition =
                    ZstdSequenceEntropy.OFFSET_ENCODER.transition(sequence.offsetCode(), offsetState);
            bits.writeBits(offsetTransition.value(), offsetTransition.bitCount());
            offsetState = offsetTransition.state();

            ZstdSequenceEntropy.Transition matchTransition =
                    ZstdSequenceEntropy.MATCH_LENGTH_ENCODER.transition(sequence.matchCode(), matchState);
            bits.writeBits(matchTransition.value(), matchTransition.bitCount());
            matchState = matchTransition.state();

            ZstdSequenceEntropy.Transition literalTransition =
                    ZstdSequenceEntropy.LITERAL_LENGTH_ENCODER.transition(sequence.literalCode(), literalState);
            bits.writeBits(literalTransition.value(), literalTransition.bitCount());
            literalState = literalTransition.state();
            writeSequenceExtraBits(bits, sequence);
        }

        bits.writeBits(matchState, ZstdSequenceEntropy.MATCH_LENGTH_ENCODER.tableLog());
        bits.writeBits(offsetState, ZstdSequenceEntropy.OFFSET_ENCODER.tableLog());
        bits.writeBits(literalState, ZstdSequenceEntropy.LITERAL_LENGTH_ENCODER.tableLog());
        output.writeBytes(bits.finish());
        return output.toByteArray();
    }

    /// Finds non-overlapping matches from left to right in the block and configured dictionary tail.
    private static @Unmodifiable List<Sequence> findSequences(
            byte[] source,
            int length,
            ZstdEncoderParameters parameters
    ) {
        int minimumMatch = parameters.minimumMatch();
        if (length < minimumMatch) {
            return List.of();
        }

        int[] heads = new int[1 << parameters.hashLog()];
        Arrays.fill(heads, Integer.MIN_VALUE);
        int[] previous = new int[length];
        Arrays.fill(previous, Integer.MIN_VALUE);
        byte[] dictionary = parameters.dictionary().content();
        int windowSize = parameters.windowLog() >= 30
                ? Integer.MAX_VALUE
                : 1 << parameters.windowLog();
        seedDictionary(heads, dictionary, windowSize);

        ArrayList<Sequence> sequences = new ArrayList<>();
        int literalStart = 0;
        int lastPosition = length - 4;
        int position = 0;
        while (position <= lastPosition) {
            int hash = hash(source, position, heads.length - 1);
            int candidate = heads[hash];
            previous[position] = candidate;
            heads[hash] = position;

            Match match = findMatchAt(
                    source,
                    length,
                    position,
                    candidate,
                    previous,
                    dictionary,
                    windowSize,
                    parameters
            );
            if (match.length() < minimumMatch) {
                position++;
                continue;
            }

            sequences.add(new Sequence(
                    position,
                    match.length(),
                    match.distance(),
                    position - literalStart
            ));
            int matchEnd = position + match.length();
            for (int skipped = position + 1; skipped < matchEnd && skipped <= lastPosition; skipped++) {
                int skippedHash = hash(source, skipped, heads.length - 1);
                previous[skipped] = heads[skippedHash];
                heads[skippedHash] = skipped;
            }
            position = matchEnd;
            literalStart = matchEnd;
        }
        return List.copyOf(sequences);
    }

    /// Finds the best match beginning at one already inserted source position.
    private static Match findMatchAt(
            byte[] source,
            int length,
            int position,
            int candidate,
            int[] previous,
            byte[] dictionary,
            int windowSize,
            ZstdEncoderParameters parameters
    ) {
        Match best = Match.NONE;
        int searched = 0;
        while (candidate != Integer.MIN_VALUE && searched++ < parameters.searchDepth()) {
            int distance = position - candidate;
            if (distance <= 0 || distance > windowSize || distance > parameters.chainLimit()) {
                break;
            }
            int matchLength = commonLength(source, length, position, candidate, dictionary);
            if (matchLength > best.length()) {
                best = new Match(matchLength, distance);
                if (matchLength == length - position
                        || parameters.targetLength() > 0 && matchLength >= parameters.targetLength()) {
                    break;
                }
            }
            candidate = candidate >= 0 ? previous[candidate] : Integer.MIN_VALUE;
        }
        return best;
    }

    /// Seeds the hash table with the latest matching position from the usable dictionary tail.
    private static void seedDictionary(int[] heads, byte[] dictionary, int windowSize) {
        int first = Math.max(0, dictionary.length - windowSize);
        int last = dictionary.length - 4;
        for (int position = first; position <= last; position++) {
            heads[hash(dictionary, position, heads.length - 1)] = position - dictionary.length;
        }
    }

    /// Returns the common length for a block or dictionary candidate.
    private static int commonLength(
            byte[] source,
            int sourceLength,
            int position,
            int candidate,
            byte[] dictionary
    ) {
        int maximum = sourceLength - position;
        if (candidate < 0) {
            int dictionaryPosition = dictionary.length + candidate;
            maximum = Math.min(maximum, dictionary.length - dictionaryPosition);
            int length = 0;
            while (length < maximum
                    && source[position + length] == dictionary[dictionaryPosition + length]) {
                length++;
            }
            return length;
        }

        int length = 0;
        while (length < maximum && source[position + length] == source[candidate + length]) {
            length++;
        }
        return length;
    }

    /// Hashes four bytes into a power-of-two table.
    private static int hash(byte[] source, int offset, int mask) {
        int value = Byte.toUnsignedInt(source[offset])
                | Byte.toUnsignedInt(source[offset + 1]) << 8
                | Byte.toUnsignedInt(source[offset + 2]) << 16
                | source[offset + 3] << 24;
        int tableLog = Integer.bitCount(mask);
        return (int) (Integer.toUnsignedLong(value * 0x9e37_79b1) >>> (32 - tableLog));
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
    private static void writeSequenceExtraBits(ReverseBitWriter bits, EncodedSequence encoded) {
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

    /// Writes a raw-literals section size header.
    private static void writeRawLiteralsHeader(ByteArrayOutputStream output, int literalSize) {
        if (literalSize <= 31) {
            output.write(literalSize << 3);
        } else if (literalSize <= 4095) {
            int header = (literalSize << 4) | 4;
            output.write(header);
            output.write(header >>> 8);
        } else {
            int header = (literalSize << 4) | 12;
            output.write(header);
            output.write(header >>> 8);
            output.write(header >>> 16);
        }
    }

    /// Writes a little-endian block header.
    private static void writeBlockHeader(byte[] target, int payloadSize, int type, boolean last) {
        int header = payloadSize << 3 | type << 1 | (last ? 1 : 0);
        target[0] = (byte) header;
        target[1] = (byte) (header >>> 8);
        target[2] = (byte) (header >>> 16);
    }

    /// Holds one non-overlapping sequence selected by the match finder.
    ///
    /// @param position match start in the source block
    /// @param length match length
    /// @param distance backward match distance
    /// @param literalLength number of literals preceding this match
    private record Sequence(int position, int length, int distance, int literalLength) {
    }

    /// Holds sequence symbols and the explicit offset value written to the bitstream.
    ///
    /// @param sequence selected sequence
    /// @param literalCode literal-length symbol
    /// @param offsetCode offset symbol
    /// @param matchCode match-length symbol
    /// @param offsetValue explicit offset value before its baseline is removed
    private record EncodedSequence(
            Sequence sequence,
            int literalCode,
            int offsetCode,
            int matchCode,
            long offsetValue
    ) {
    }

    /// Holds one selected match.
    ///
    /// @param length match length
    /// @param distance backward match distance
    private record Match(int length, int distance) {
        /// Sentinel indicating that no match was found.
        private static final Match NONE = new Match(0, 0);

        /// Creates match metadata.
        private Match {
        }
    }

    /// Writes little-endian fields that a Zstandard reverse bit reader consumes in reverse order.
    @NotNullByDefault
    private static final class ReverseBitWriter {
        /// Packed output bytes.
        private byte[] bytes = new byte[8];

        /// Number of useful bits written.
        private int bitCount;

        /// Appends the low `count` bits of a value.
        private void writeBits(long value, int count) {
            if (count < 0 || count > 31 || (count != 0 && value >>> count != 0L)) {
                throw new IllegalArgumentException("Invalid Zstandard reverse bit field");
            }
            ensureCapacity(bitCount + count + 1);
            for (int bit = 0; bit < count; bit++) {
                if ((value & 1L << bit) != 0L) {
                    bytes[(bitCount + bit) >>> 3] |= (byte) (1 << ((bitCount + bit) & 7));
                }
            }
            bitCount += count;
        }

        /// Appends the terminal marker and returns the complete stream.
        private byte[] finish() {
            ensureCapacity(bitCount + 1);
            bytes[bitCount >>> 3] |= (byte) (1 << (bitCount & 7));
            bitCount++;
            return Arrays.copyOf(bytes, (bitCount + 7) >>> 3);
        }

        /// Expands the packed byte array to hold a bit count.
        private void ensureCapacity(int requiredBits) {
            int requiredBytes = (requiredBits + 7) >>> 3;
            if (requiredBytes > bytes.length) {
                bytes = Arrays.copyOf(bytes, Math.max(requiredBytes, bytes.length * 2));
            }
        }
    }

    /// Creates no instances.
    private ZstdBlockEncoder() {
    }
}
