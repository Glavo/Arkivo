// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate64.internal;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.spi.OwnedChannelCloser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.Objects;

/// Encodes raw Deflate64 blocks with fixed Huffman codes and bounded hash-chain matching.
@NotNullByDefault
public final class Deflate64ChannelEncoder implements CompressionEncoder {
    /// The Deflate64 history-window size.
    private static final int WINDOW_SIZE = 1 << 16;

    /// The maximum uncompressed bytes held for one output block.
    private static final int BLOCK_SIZE = 1 << 16;

    /// The hash-table size used by the match finder.
    private static final int HASH_SIZE = 1 << 16;

    /// The minimum match length represented by Deflate64.
    private static final int MINIMUM_MATCH_LENGTH = 3;

    /// The largest standard Deflate match length before Deflate64 symbol 285.
    private static final int MAXIMUM_STANDARD_MATCH_LENGTH = 258;

    /// The base lengths for symbols 257 through 285.
    private static final int @Unmodifiable [] LENGTH_BASES = {
            3, 4, 5, 6, 7, 8, 9, 10,
            11, 13, 15, 17,
            19, 23, 27, 31,
            35, 43, 51, 59,
            67, 83, 99, 115,
            131, 163, 195, 227,
            3
    };

    /// The extra-bit counts for symbols 257 through 285.
    private static final int @Unmodifiable [] LENGTH_EXTRA_BITS = {
            0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1,
            2, 2, 2, 2,
            3, 3, 3, 3,
            4, 4, 4, 4,
            5, 5, 5, 5,
            16
    };

    /// The target receiving the raw Deflate64 stream.
    private final WritableByteChannel target;

    /// Tracks closure of the owned compressed-data target.
    private final OwnedChannelCloser targetCloser;

    /// The maximum hash-chain candidates examined for each position.
    private final int searchLimit;

    /// The little-endian bit writer.
    private final BitOutput bits;

    /// The current uncompressed block.
    private final byte[] block = new byte[BLOCK_SIZE];

    /// The history retained from preceding blocks.
    private final byte[] history = new byte[WINDOW_SIZE];

    /// Hash-chain heads for the combined history and current block.
    private final int[] hashHeads = new int[HASH_SIZE];

    /// Previous positions in the combined bounded search domain.
    private final int[] previous = new int[WINDOW_SIZE + BLOCK_SIZE];

    /// The number of bytes in the current block.
    private int blockSize;

    /// The number of retained history bytes.
    private int historySize;

    /// The number of uncompressed bytes accepted.
    private long inputBytes;

    /// Whether this encoder remains open.
    private boolean open = true;

    /// Creates an encoder at the requested compression level from 0 through 9.
    public Deflate64ChannelEncoder(
            WritableByteChannel target,
            ChannelOwnership ownership,
            int compressionLevel
    ) {
        this.target = Objects.requireNonNull(target, "target");
        this.targetCloser = new OwnedChannelCloser(target, ownership);
        if (compressionLevel < 0 || compressionLevel > 9) {
            throw new IllegalArgumentException("Deflate64 compression level must be between 0 and 9");
        }
        searchLimit = compressionLevel == 0 ? 0 : 1 << Math.min(compressionLevel + 1, 10);
        bits = new BitOutput(target);
    }

    /// Buffers uncompressed bytes and emits complete nonfinal blocks as needed.
    @Override
    public int write(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        ensureOpen();
        int start = source.position();
        while (source.hasRemaining()) {
            if (blockSize == block.length) {
                writeBlock(false);
            }
            int copied = Math.min(source.remaining(), block.length - blockSize);
            source.get(block, blockSize, copied);
            blockSize += copied;
        }
        int count = source.position() - start;
        inputBytes += count;
        return count;
    }

    /// Ends the current block and writes complete compressed bytes to the target.
    @Override
    public void flush() throws IOException {
        ensureOpen();
        if (blockSize > 0) {
            writeBlock(false);
        }
        bits.flush();
    }

    /// Writes the final block and closes this encoder context.
    @Override
    public void finish() throws IOException {
        if (!open) {
            targetCloser.close();
            return;
        }
        @Nullable Throwable failure = null;
        try {
            writeBlock(true);
            bits.finish();
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
        }
        open = false;
        targetCloser.closeAfter(failure);

    }

    /// Returns the number of uncompressed bytes accepted.
    @Override
    public long inputBytes() {
        return inputBytes;
    }

    /// Returns the number of compressed bytes written to the target.
    @Override
    public long outputBytes() {
        return bits.byteCount();
    }

    /// Returns whether this encoder remains open.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Finishes and closes this encoder context.
    @Override
    public void close() throws IOException {
        finish();
    }

    /// Encodes one fixed-Huffman block and retains its trailing history.
    private void writeBlock(boolean finalBlock) throws IOException {
        bits.writeBits(1, finalBlock ? 1 : 0);
        bits.writeBits(2, 1);
        initializeMatchFinder();

        int position = 0;
        while (position < blockSize) {
            int logicalPosition = historySize + position;
            Match match = findMatch(logicalPosition);
            if (match.length() >= MINIMUM_MATCH_LENGTH) {
                writeLength(match.length());
                writeDistance(match.distance());
                int end = position + match.length();
                while (position < end) {
                    insertPosition(historySize + position);
                    position++;
                }
            } else {
                writeFixedSymbol(Byte.toUnsignedInt(block[position]));
                insertPosition(logicalPosition);
                position++;
            }
        }
        writeFixedSymbol(256);
        retainHistory();
        blockSize = 0;
    }

    /// Initializes hash chains with the retained history.
    private void initializeMatchFinder() {
        Arrays.fill(hashHeads, -1);
        Arrays.fill(previous, -1);
        for (int position = 0; position + 2 < historySize; position++) {
            insertPosition(position);
        }
    }

    /// Finds the best bounded match at one combined-domain position.
    private Match findMatch(int position) {
        int remaining = historySize + blockSize - position;
        if (searchLimit == 0 || remaining < MINIMUM_MATCH_LENGTH) {
            return Match.NONE;
        }
        int candidate = hashHeads[hash(position)];
        int bestLength = 0;
        int bestDistance = 0;
        int searched = 0;
        while (candidate >= 0 && searched++ < searchLimit) {
            int distance = position - candidate;
            if (distance > WINDOW_SIZE) {
                break;
            }
            if (byteAt(candidate) == byteAt(position)
                    && byteAt(candidate + 1) == byteAt(position + 1)
                    && byteAt(candidate + 2) == byteAt(position + 2)) {
                int length = MINIMUM_MATCH_LENGTH;
                while (length < remaining && byteAt(candidate + length) == byteAt(position + length)) {
                    length++;
                }
                if (length > bestLength) {
                    bestLength = length;
                    bestDistance = distance;
                    if (length == remaining) {
                        break;
                    }
                }
            }
            candidate = previous[candidate];
        }
        return bestLength >= MINIMUM_MATCH_LENGTH
                ? new Match(bestLength, bestDistance)
                : Match.NONE;
    }

    /// Inserts one position into its three-byte hash chain.
    private void insertPosition(int position) {
        if (position + 2 >= historySize + blockSize) {
            return;
        }
        int hash = hash(position);
        previous[position] = hashHeads[hash];
        hashHeads[hash] = position;
    }

    /// Returns the hash of three bytes at one combined-domain position.
    private int hash(int position) {
        int value = byteAt(position);
        value = value * 251 + byteAt(position + 1);
        value = value * 251 + byteAt(position + 2);
        return value & (HASH_SIZE - 1);
    }

    /// Returns one unsigned byte from retained history or the current block.
    private int byteAt(int position) {
        return position < historySize
                ? Byte.toUnsignedInt(history[position])
                : Byte.toUnsignedInt(block[position - historySize]);
    }

    /// Writes one Deflate64 length symbol and its extra bits.
    private void writeLength(int length) throws IOException {
        if (length > MAXIMUM_STANDARD_MATCH_LENGTH) {
            writeFixedSymbol(285);
            bits.writeBits(16, length - MINIMUM_MATCH_LENGTH);
            return;
        }
        for (int index = 0; index < LENGTH_BASES.length - 1; index++) {
            int extraBits = LENGTH_EXTRA_BITS[index];
            int base = LENGTH_BASES[index];
            int maximum = base + (1 << extraBits) - 1;
            if (length <= maximum) {
                writeFixedSymbol(257 + index);
                bits.writeBits(extraBits, length - base);
                return;
            }
        }
        throw new AssertionError(length);
    }

    /// Writes one Deflate64 distance symbol and its extra bits.
    private void writeDistance(int distance) throws IOException {
        for (int symbol = 0; symbol < 32; symbol++) {
            int extraBits = symbol < 4 ? 0 : (symbol >>> 1) - 1;
            int base = symbol < 4
                    ? symbol + 1
                    : ((2 + (symbol & 1)) << extraBits) + 1;
            int maximum = base + (1 << extraBits) - 1;
            if (distance <= maximum) {
                bits.writeBits(5, reverseBits(symbol, 5));
                bits.writeBits(extraBits, distance - base);
                return;
            }
        }
        throw new AssertionError(distance);
    }

    /// Writes one symbol from the fixed literal/length tree.
    private void writeFixedSymbol(int symbol) throws IOException {
        int code;
        int length;
        if (symbol <= 143) {
            code = 0x30 + symbol;
            length = 8;
        } else if (symbol <= 255) {
            code = 0x190 + symbol - 144;
            length = 9;
        } else if (symbol <= 279) {
            code = symbol - 256;
            length = 7;
        } else if (symbol <= 287) {
            code = 0xc0 + symbol - 280;
            length = 8;
        } else {
            throw new AssertionError(symbol);
        }
        bits.writeBits(length, reverseBits(code, length));
    }

    /// Reverses the low-order `length` bits of a value.
    private static int reverseBits(int value, int length) {
        return Integer.reverse(value) >>> (Integer.SIZE - length);
    }

    /// Retains the final 64 KiB of the combined history and current block.
    private void retainHistory() {
        int combinedSize = historySize + blockSize;
        int retained = Math.min(WINDOW_SIZE, combinedSize);
        int start = combinedSize - retained;
        if (start < historySize) {
            int fromHistory = historySize - start;
            System.arraycopy(history, start, history, 0, fromHistory);
            System.arraycopy(block, 0, history, fromHistory, retained - fromHistory);
        } else {
            System.arraycopy(block, start - historySize, history, 0, retained);
        }
        historySize = retained;
    }

    /// Requires this encoder to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }

    /// Describes one selected LZ77 match.
    ///
    /// @param length match length
    /// @param distance backward match distance
    private record Match(int length, int distance) {
        /// The absence of a usable match.
        private static final Match NONE = new Match(0, 0);
    }

    /// Writes little-endian packed fields to the target channel.
    @NotNullByDefault
    private static final class BitOutput {
        /// The compressed-data target.
        private final WritableByteChannel target;

        /// The direct output staging buffer.
        private final ByteBuffer outputBuffer = ByteBuffer.allocateDirect(8192);

        /// Packed pending bits, with the next output bit in bit zero.
        private long buffer;

        /// The number of pending bits.
        private int bitCount;

        /// The number of complete bytes written to the target.
        private long byteCount;

        /// Creates a bit writer over a target channel.
        private BitOutput(WritableByteChannel target) {
            this.target = target;
        }

        /// Writes up to sixteen low-order bits.
        private void writeBits(int count, int value) throws IOException {
            if (count < 0 || count > 16) {
                throw new IllegalArgumentException("Deflate64 bit count must be between 0 and 16");
            }
            long mask = count == 0 ? 0L : (1L << count) - 1L;
            buffer |= ((long) value & mask) << bitCount;
            bitCount += count;
            while (bitCount >= 8) {
                writeByte((int) buffer & 0xff);
                buffer >>>= 8;
                bitCount -= 8;
            }
        }

        /// Writes all staged complete bytes without padding pending bits.
        private void flush() throws IOException {
            outputBuffer.flip();
            while (outputBuffer.hasRemaining()) {
                int written = target.write(outputBuffer);
                if (written == 0) {
                    throw new IOException("Deflate64 target channel made no progress");
                }
                byteCount += written;
            }
            outputBuffer.clear();
        }

        /// Pads the final partial byte and flushes the target buffer.
        private void finish() throws IOException {
            if (bitCount > 0) {
                writeByte((int) buffer & 0xff);
                buffer = 0L;
                bitCount = 0;
            }
            flush();
        }

        /// Returns the number of complete compressed bytes written.
        private long byteCount() {
            return byteCount;
        }

        /// Stages one complete compressed byte.
        private void writeByte(int value) throws IOException {
            if (!outputBuffer.hasRemaining()) {
                flush();
            }
            outputBuffer.put((byte) value);
        }
    }
}
