// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;

/// Selects and encodes raw, run-length, or Huffman-compressed Zstandard literal sections.
@NotNullByDefault
final class ZstdLiteralEncoder {
    /// The maximum Huffman table logarithm emitted by this encoder.
    private static final int MAX_HUFFMAN_LOG = 11;

    /// The largest symbol whose preceding weights fit the direct table-description form.
    private static final int MAX_DIRECT_WEIGHT_SYMBOL = 128;

    /// Encodes one complete literal section using the smallest supported representation.
    static byte[] encode(byte[] literals) {
        byte[] raw = encodePlain(literals, 0);
        if (literals.length == 0) {
            return raw;
        }
        if (isRunLength(literals)) {
            return encodePlain(literals, 1);
        }

        @Nullable HuffmanEncoding huffman = buildHuffmanEncoding(literals);
        if (huffman == null) {
            return raw;
        }
        byte @Nullable [] compressed = encodeCompressed(literals, huffman);
        return compressed != null && compressed.length < raw.length ? compressed : raw;
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
    private static byte @Nullable [] encodeCompressed(byte[] literals, HuffmanEncoding huffman) {
        byte[] description = encodeTableDescription(huffman);
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
        return addCompressedHeader(literals.length, payload, streamCount);
    }

    /// Prefixes a Huffman payload with the smallest compatible compressed-literals header.
    private static byte @Nullable [] addCompressedHeader(
            int regeneratedSize,
            byte[] payload,
            int streamCount
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
                    | 2L;
        } else if (streamCount == 4
                && regeneratedSize <= 0x3fff
                && compressedSize <= 0x3fff) {
            headerSize = 4;
            header = (long) compressedSize << 18
                    | (long) regeneratedSize << 4
                    | 2L << 2
                    | 2L;
        } else if (streamCount == 4
                && regeneratedSize <= 0x3ffff
                && compressedSize <= 0x3ffff) {
            headerSize = 5;
            header = (long) compressedSize << 22
                    | (long) regeneratedSize << 4
                    | 3L << 2
                    | 2L;
        } else {
            return null;
        }

        byte[] result = new byte[headerSize + compressedSize];
        writeLittleEndian(result, 0, header, headerSize);
        System.arraycopy(payload, 0, result, headerSize, compressedSize);
        return result;
    }

    /// Builds a deterministic complete Huffman tree and its canonical Zstandard codes.
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
        if (distinctSymbols < 2 || maximumSymbol > MAX_DIRECT_WEIGHT_SYMBOL) {
            return null;
        }

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
            nodeFrequencies[nodeCount] = symbolFrequencies[symbol];
            symbols[nodeCount] = symbol;
            minimumSymbols[nodeCount] = symbol;
            queue.add(nodeCount++);
        }
        while (queue.size() > 1) {
            int left = queue.remove();
            int right = queue.remove();
            nodeFrequencies[nodeCount] = nodeFrequencies[left] + nodeFrequencies[right];
            minimumSymbols[nodeCount] = Math.min(minimumSymbols[left], minimumSymbols[right]);
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
                if (depth > MAX_HUFFMAN_LOG) {
                    return null;
                }
                codeLengths[symbols[node]] = depth;
                tableLog = Math.max(tableLog, depth);
                continue;
            }
            nodeStack[stackSize] = rightChildren[node];
            depthStack[stackSize++] = depth + 1;
            nodeStack[stackSize] = leftChildren[node];
            depthStack[stackSize++] = depth + 1;
        }

        int[] weights = new int[256];
        for (int symbol = 0; symbol <= maximumSymbol; symbol++) {
            if (codeLengths[symbol] != 0) {
                weights[symbol] = tableLog + 1 - codeLengths[symbol];
            }
        }
        int[] codes = buildCanonicalCodes(weights, maximumSymbol + 1, tableLog);
        return new HuffmanEncoding(maximumSymbol, weights, codeLengths, codes);
    }

    /// Assigns canonical codes in the order used by Zstandard Huffman decoding tables.
    private static int[] buildCanonicalCodes(int[] weights, int symbolCount, int tableLog) {
        int[] codes = new int[256];
        int nextCode = 0;
        for (int weight = 1; weight <= tableLog; weight++) {
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

    /// Encodes direct Huffman weights while omitting the inferred final symbol.
    private static byte[] encodeTableDescription(HuffmanEncoding huffman) {
        int weightCount = huffman.maximumSymbol();
        byte[] result = new byte[1 + ((weightCount + 1) >>> 1)];
        result[0] = (byte) (127 + weightCount);
        for (int index = 0; index < weightCount; index++) {
            int shift = (index & 1) == 0 ? 4 : 0;
            result[1 + (index >>> 1)] |= (byte) (huffman.weights()[index] << shift);
        }
        return result;
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

    /// Holds a direct Huffman table description and its canonical encoding tables.
    ///
    /// @param maximumSymbol final symbol whose weight is inferred by the decoder
    /// @param weights Zstandard weights indexed by symbol
    /// @param codeLengths code lengths indexed by symbol
    /// @param codes canonical codes indexed by symbol
    private record HuffmanEncoding(
            int maximumSymbol,
            int @Unmodifiable [] weights,
            int @Unmodifiable [] codeLengths,
            int @Unmodifiable [] codes
    ) {
    }

    /// Creates no instances.
    private ZstdLiteralEncoder() {
    }
}
