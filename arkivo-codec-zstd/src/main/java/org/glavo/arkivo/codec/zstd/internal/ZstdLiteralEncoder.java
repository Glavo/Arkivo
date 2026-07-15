// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;

/// Selects and encodes raw, run-length, or Huffman-compressed Zstandard literal sections.
@NotNullByDefault
final class ZstdLiteralEncoder {
    /// The maximum Huffman table logarithm supported by the format.
    private static final int MAX_HUFFMAN_LOG = 12;

    /// The largest symbol whose preceding weights fit the direct table-description form.
    private static final int MAX_DIRECT_WEIGHT_SYMBOL = 128;

    /// Encodes one complete literal section without a reusable frame-local table.
    static byte[] encode(byte[] literals) {
        return encode(literals, null).bytes();
    }

    /// Encodes one complete literal section and returns the table active after it.
    static LiteralEncoding encode(
            byte[] literals,
            @Nullable HuffmanEncoding previous
    ) {
        byte[] best = encodePlain(literals, 0);
        @Nullable HuffmanEncoding selectedTable = previous;
        if (literals.length == 0) {
            return new LiteralEncoding(best, selectedTable);
        }
        if (isRunLength(literals)) {
            return new LiteralEncoding(encodePlain(literals, 1), selectedTable);
        }

        if (previous != null && previous.canEncode(literals)) {
            byte @Nullable [] repeated = encodeCompressed(literals, previous, false);
            if (repeated != null && repeated.length < best.length) {
                best = repeated;
            }
        }

        @Nullable HuffmanEncoding huffman = buildHuffmanEncoding(literals);
        if (huffman != null) {
            byte @Nullable [] compressed = encodeCompressed(literals, huffman, true);
            if (compressed != null && compressed.length < best.length) {
                best = compressed;
                selectedTable = huffman;
            }
        }
        return new LiteralEncoding(best, selectedTable);
    }

    /// Returns whether every literal has the same value.
    private static boolean isRunLength(byte[] literals) {
        byte value = literals[0];
        for (int index = 1; index < literals.length; index++) {
            if (literals[index] != value) {
                return false;
            }
        }
        return true;
    }

    /// Encodes a raw or run-length literal section.
    private static byte[] encodePlain(byte[] literals, int type) {
        int size = literals.length;
        int headerSize = size <= 31 ? 1 : size <= 4095 ? 2 : 3;
        int payloadSize = type == 0 ? size : 1;
        byte[] result = new byte[headerSize + payloadSize];
        int header;
        if (headerSize == 1) {
            header = size << 3 | type;
        } else if (headerSize == 2) {
            header = size << 4 | 4 | type;
        } else {
            header = size << 4 | 12 | type;
        }
        writeLittleEndian(result, 0, header, headerSize);
        if (type == 0) {
            System.arraycopy(literals, 0, result, headerSize, size);
        } else {
            result[headerSize] = literals[0];
        }
        return result;
    }

    /// Encodes a Huffman literal section, or returns `null` when its sizes are not representable.
    private static byte @Nullable [] encodeCompressed(
            byte[] literals,
            HuffmanEncoding huffman,
            boolean includeDescription
    ) {
        byte @Nullable [] description =
                includeDescription ? encodeTableDescription(huffman) : new byte[0];
        if (description == null) {
            return null;
        }
        byte[] payload;
        int streamCount;
        if (literals.length <= 1023) {
            byte[] stream = encodeStream(literals, 0, literals.length, huffman);
            payload = new byte[description.length + stream.length];
            System.arraycopy(description, 0, payload, 0, description.length);
            System.arraycopy(stream, 0, payload, description.length, stream.length);
            streamCount = 1;
        } else {
            int segmentSize = (literals.length + 3) >>> 2;
            byte[][] streams = new byte[4][];
            for (int stream = 0; stream < 3; stream++) {
                streams[stream] = encodeStream(
                        literals,
                        stream * segmentSize,
                        segmentSize,
                        huffman
                );
            }
            streams[3] = encodeStream(
                    literals,
                    segmentSize * 3,
                    literals.length - segmentSize * 3,
                    huffman
            );
            for (int stream = 0; stream < 3; stream++) {
                if (streams[stream].length > 0xffff) {
                    return null;
                }
            }

            ByteArrayOutputStream encoded = new ByteArrayOutputStream(literals.length);
            encoded.writeBytes(description);
            for (int stream = 0; stream < 3; stream++) {
                int size = streams[stream].length;
                encoded.write(size);
                encoded.write(size >>> 8);
            }
            for (byte[] stream : streams) {
                encoded.writeBytes(stream);
            }
            payload = encoded.toByteArray();
            streamCount = 4;
        }
        return addCompressedHeader(literals.length, payload, streamCount, includeDescription ? 2 : 3);
    }

    /// Prefixes a Huffman payload with the smallest compatible compressed-literals header.
    private static byte @Nullable [] addCompressedHeader(
            int regeneratedSize,
            byte[] payload,
            int streamCount,
            int type
    ) {
        int compressedSize = payload.length;
        int headerSize;
        long header;
        if (regeneratedSize <= 0x3ff && compressedSize <= 0x3ff) {
            int sizeFormat = streamCount == 1 ? 0 : 1;
            headerSize = 3;
            header = (long) compressedSize << 14
                    | (long) regeneratedSize << 4
                    | (long) sizeFormat << 2
                    | type;
        } else if (streamCount == 4
                && regeneratedSize <= 0x3fff
                && compressedSize <= 0x3fff) {
            headerSize = 4;
            header = (long) compressedSize << 18
                    | (long) regeneratedSize << 4
                    | 2L << 2
                    | type;
        } else if (streamCount == 4
                && regeneratedSize <= 0x3ffff
                && compressedSize <= 0x3ffff) {
            headerSize = 5;
            header = (long) compressedSize << 22
                    | (long) regeneratedSize << 4
                    | 3L << 2
                    | type;
        } else {
            return null;
        }

        byte[] result = new byte[headerSize + compressedSize];
        writeLittleEndian(result, 0, header, headerSize);
        System.arraycopy(payload, 0, result, headerSize, compressedSize);
        return result;
    }

    /// Builds a reusable Huffman table description from representative literal bytes.
    static byte @Nullable [] buildTableDescription(byte[] literals) {
        @Nullable HuffmanEncoding huffman = buildHuffmanEncoding(literals);
        return huffman != null ? encodeTableDescription(huffman) : null;
    }

    /// Builds a deterministic length-limited Huffman tree and its canonical Zstandard codes.
    private static @Nullable HuffmanEncoding buildHuffmanEncoding(byte[] literals) {
        long[] symbolFrequencies = new long[256];
        int maximumSymbol = 0;
        int distinctSymbols = 0;
        for (byte literal : literals) {
            int symbol = Byte.toUnsignedInt(literal);
            if (symbolFrequencies[symbol]++ == 0L) {
                distinctSymbols++;
                maximumSymbol = Math.max(maximumSymbol, symbol);
            }
        }
        if (distinctSymbols < 2) {
            return null;
        }

        long frequencyFloor = 0L;
        while (true) {
            long[] nodeFrequencies = new long[511];
            int[] leftChildren = new int[511];
            int[] rightChildren = new int[511];
            int[] symbols = new int[511];
            int[] minimumSymbols = new int[511];
            Arrays.fill(leftChildren, -1);
            Arrays.fill(rightChildren, -1);
            Arrays.fill(symbols, -1);
            Comparator<Integer> order = Comparator
                    .comparingLong((Integer node) -> nodeFrequencies[node])
                    .thenComparingInt(node -> minimumSymbols[node]);
            PriorityQueue<Integer> queue = new PriorityQueue<>(order);

            int nodeCount = 0;
            for (int symbol = 0; symbol <= maximumSymbol; symbol++) {
                if (symbolFrequencies[symbol] == 0L) {
                    continue;
                }
                nodeFrequencies[nodeCount] = Math.max(
                        symbolFrequencies[symbol],
                        frequencyFloor
                );
                symbols[nodeCount] = symbol;
                minimumSymbols[nodeCount] = symbol;
                queue.add(nodeCount++);
            }
            while (queue.size() > 1) {
                int left = queue.remove();
                int right = queue.remove();
                nodeFrequencies[nodeCount] =
                        nodeFrequencies[left] + nodeFrequencies[right];
                minimumSymbols[nodeCount] =
                        Math.min(minimumSymbols[left], minimumSymbols[right]);
                leftChildren[nodeCount] = left;
                rightChildren[nodeCount] = right;
                queue.add(nodeCount++);
            }

            int[] codeLengths = new int[256];
            int[] nodeStack = new int[nodeCount];
            int[] depthStack = new int[nodeCount];
            int stackSize = 1;
            nodeStack[0] = queue.remove();
            int tableLog = 0;
            while (stackSize > 0) {
                int node = nodeStack[--stackSize];
                int depth = depthStack[stackSize];
                if (symbols[node] >= 0) {
                    codeLengths[symbols[node]] = depth;
                    tableLog = Math.max(tableLog, depth);
                    continue;
                }
                nodeStack[stackSize] = rightChildren[node];
                depthStack[stackSize++] = depth + 1;
                nodeStack[stackSize] = leftChildren[node];
                depthStack[stackSize++] = depth + 1;
            }
            if (tableLog <= MAX_HUFFMAN_LOG) {
                int[] weights = new int[256];
                for (int symbol = 0; symbol <= maximumSymbol; symbol++) {
                    if (codeLengths[symbol] != 0) {
                        weights[symbol] = tableLog + 1 - codeLengths[symbol];
                    }
                }
                int[] codes = buildCanonicalCodes(
                        weights,
                        maximumSymbol + 1,
                        tableLog
                );
                return new HuffmanEncoding(
                        maximumSymbol,
                        weights,
                        codeLengths,
                        codes
                );
            }
            frequencyFloor = frequencyFloor == 0L ? 1L : frequencyFloor << 1;
        }
    }

    /// Assigns canonical codes in the order used by Zstandard Huffman decoding tables.
    private static int[] buildCanonicalCodes(int[] weights, int symbolCount, int tableLog) {
        int[] codes = new int[256];

        int nextCode = 0;
        for (int weight = 1; weight <= tableLog; weight++) {
            int codeLength = tableLog + 1 - weight;
            int count = 0;
            for (int symbol = 0; symbol < symbolCount; symbol++) {
                if (weights[symbol] == weight) {
                    codes[symbol] = nextCode + count++;
                }
            }
            nextCode = (nextCode + count) >>> 1;
        }
        return codes;
    }

    /// Reconstructs canonical encoding tables from validated Zstandard Huffman weights.
    static HuffmanEncoding fromWeights(int[] weights, int symbolCount, int tableLog) {
        if (symbolCount < 2
                || symbolCount > 256
                || symbolCount > weights.length
                || tableLog < 1
                || tableLog > MAX_HUFFMAN_LOG) {
            throw new IllegalArgumentException(
                    "Invalid Zstandard Huffman encoding parameters: symbolCount="
                            + symbolCount + ", tableLog=" + tableLog
                            + ", weightsLength=" + weights.length
            );
        }

        int[] encodingWeights = Arrays.copyOf(weights, 256);
        int[] codeLengths = new int[256];
        boolean hasCode = false;
        for (int symbol = 0; symbol < symbolCount; symbol++) {
            int weight = encodingWeights[symbol];
            if (weight < 0 || weight > tableLog) {
                throw new IllegalArgumentException("Invalid Zstandard Huffman weight");
            }
            if (weight != 0) {
                codeLengths[symbol] = tableLog + 1 - weight;
                hasCode = true;
            }
        }
        if (!hasCode) {
            throw new IllegalArgumentException("Empty Zstandard Huffman encoding table");
        }

        return new HuffmanEncoding(
                symbolCount - 1,
                encodingWeights,
                codeLengths,
                buildCanonicalCodes(encodingWeights, symbolCount, tableLog)
        );
    }

    /// Encodes direct or FSE-compressed Huffman weights while omitting the inferred final symbol.
    private static byte @Nullable [] encodeTableDescription(HuffmanEncoding huffman) {
        int weightCount = huffman.maximumSymbol();
        if (weightCount <= MAX_DIRECT_WEIGHT_SYMBOL) {
            byte[] result = new byte[1 + ((weightCount + 1) >>> 1)];
            result[0] = (byte) (127 + weightCount);
            for (int index = 0; index < weightCount; index++) {
                int shift = (index & 1) == 0 ? 4 : 0;
                result[1 + (index >>> 1)] |=
                        (byte) (huffman.weights()[index] << shift);
            }
            return result;
        }

        int[] frequencies = new int[MAX_HUFFMAN_LOG + 1];
        int distinctWeights = 0;
        int maximumWeight = 0;
        for (int index = 0; index < weightCount; index++) {
            int weight = huffman.weights()[index];
            if (frequencies[weight]++ == 0) {
                distinctWeights++;
                maximumWeight = Math.max(maximumWeight, weight);
            }
        }
        if (distinctWeights < 2) {
            return null;
        }

        int tableLog = 6;
        int[] normalized = ZstdSequenceEntropy.normalize(
                frequencies,
                maximumWeight + 1,
                weightCount,
                tableLog
        );
        byte[] tableDescription = ZstdEntropy.encodeFseTableDescription(
                normalized,
                maximumWeight + 1,
                tableLog
        );
        ZstdEntropy.FseEncoderTable encoder;
        try {
            encoder = ZstdEntropy.FseEncoderTable.fromDecoder(
                    ZstdEntropy.FseTable.fromNormalized(
                            normalized,
                            maximumWeight + 1,
                            tableLog
                    ),
                    maximumWeight + 1
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot build Zstandard Huffman-weight FSE table",
                    exception
            );
        }

        byte[] stream = encodeWeightStream(huffman.weights(), weightCount, encoder);
        int payloadSize = tableDescription.length + stream.length;
        if (payloadSize > 127) {
            return null;
        }
        byte[] result = new byte[1 + payloadSize];
        result[0] = (byte) payloadSize;
        System.arraycopy(tableDescription, 0, result, 1, tableDescription.length);
        System.arraycopy(stream, 0, result, 1 + tableDescription.length, stream.length);
        return result;
    }

    /// Encodes Huffman weights through the standard two-state FSE bitstream.
    private static byte[] encodeWeightStream(
            int @Unmodifiable [] weights,
            int count,
            ZstdEntropy.FseEncoderTable encoder
    ) {
        int index = count;
        int state1;
        int state2;
        ZstdEntropy.ReverseBitWriter bits = new ZstdEntropy.ReverseBitWriter();
        if ((count & 1) != 0) {
            state1 = encoder.initialState(weights[--index]);
            state2 = encoder.initialState(weights[--index]);
            ZstdEntropy.FseTransition transition =
                    encoder.transition(weights[--index], state1);
            bits.writeBits(transition.value(), transition.bitCount());
            state1 = transition.state();
        } else {
            state2 = encoder.initialState(weights[--index]);
            state1 = encoder.initialState(weights[--index]);
        }

        while (index > 0) {
            ZstdEntropy.FseTransition transition2 =
                    encoder.transition(weights[--index], state2);
            bits.writeBits(transition2.value(), transition2.bitCount());
            state2 = transition2.state();

            ZstdEntropy.FseTransition transition1 =
                    encoder.transition(weights[--index], state1);
            bits.writeBits(transition1.value(), transition1.bitCount());
            state1 = transition1.state();
        }
        bits.writeBits(state2, encoder.tableLog());
        bits.writeBits(state1, encoder.tableLog());
        return bits.finish();
    }

    /// Encodes one Huffman stream in the order consumed by a reverse bit reader.
    private static byte[] encodeStream(
            byte[] literals,
            int offset,
            int length,
            HuffmanEncoding huffman
    ) {
        ZstdEntropy.ReverseBitWriter bits = new ZstdEntropy.ReverseBitWriter();
        for (int index = offset + length - 1; index >= offset; index--) {
            int symbol = Byte.toUnsignedInt(literals[index]);
            bits.writeBits(huffman.codes()[symbol], huffman.codeLengths()[symbol]);
        }
        return bits.finish();
    }

    /// Writes the low bytes of an integer in little-endian order.
    private static void writeLittleEndian(byte[] target, int offset, long value, int byteCount) {
        for (int index = 0; index < byteCount; index++) {
            target[offset + index] = (byte) (value >>> (index * 8));
        }
    }

    /// Holds an encoded literal section and the frame-local table active after it.
    ///
    /// @param bytes complete literal-section bytes
    /// @param table active Huffman table, or null when no table has been established
    record LiteralEncoding(
            byte @Unmodifiable [] bytes,
            @Nullable HuffmanEncoding table
    ) {
    }

    /// Holds a Huffman table description and its canonical encoding tables.
    ///
    /// @param maximumSymbol final symbol whose weight is inferred by the decoder
    /// @param weights Zstandard weights indexed by symbol
    /// @param codeLengths code lengths indexed by symbol
    /// @param codes canonical codes indexed by symbol
    record HuffmanEncoding(
            int maximumSymbol,
            int @Unmodifiable [] weights,
            int @Unmodifiable [] codeLengths,
            int @Unmodifiable [] codes
    ) {
        /// Returns whether every literal has a code in this table.
        boolean canEncode(byte[] literals) {
            for (byte literal : literals) {
                if (codeLengths[Byte.toUnsignedInt(literal)] == 0) {
                    return false;
                }
            }
            return true;
        }
    }

    /// Creates no instances.
    private ZstdLiteralEncoder() {
    }
}
