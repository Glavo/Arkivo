// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Arrays;
import java.util.Objects;

/// Computes the unkeyed 256-bit BLAKE2sp digest used by RAR5 file hash records.
@NotNullByDefault
final class RarBlake2sp {
    /// The BLAKE2sp digest size in bytes.
    static final int DIGEST_SIZE = 32;

    /// The number of parallel BLAKE2s leaf nodes.
    private static final int PARALLELISM = 8;

    /// The BLAKE2s compression block size.
    private static final int BLOCK_SIZE = 64;

    /// The input stripe distributed across all leaf nodes.
    private static final int STRIPE_SIZE = PARALLELISM * BLOCK_SIZE;

    /// The eight state words defined for BLAKE2s.
    private static final int @Unmodifiable [] INITIALIZATION_VECTOR = {
            0x6a09e667,
            0xbb67ae85,
            0x3c6ef372,
            0xa54ff53a,
            0x510e527f,
            0x9b05688c,
            0x1f83d9ab,
            0x5be0cd19
    };

    /// The ten BLAKE2s message schedules.
    private static final byte @Unmodifiable [] @Unmodifiable [] MESSAGE_SCHEDULE = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
            {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
            {11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4},
            {7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8},
            {9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13},
            {2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9},
            {12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11},
            {13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10},
            {6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5},
            {10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0}
    };

    /// The eight stateful leaf hashes.
    private final Blake2s[] leaves = new Blake2s[PARALLELISM];

    /// The root hash combining all leaf digests.
    private final Blake2s root;

    /// The current partial 512-byte input stripe.
    private final byte[] stripe = new byte[STRIPE_SIZE];

    /// The number of bytes currently retained in the partial stripe.
    private int stripeLength;

    /// Whether the current digest has been finalized.
    private boolean finished;

    /// Creates one empty BLAKE2sp digest state.
    RarBlake2sp() {
        for (int index = 0; index < leaves.length; index++) {
            leaves[index] = new Blake2s(index, 0, index == leaves.length - 1);
        }
        root = new Blake2s(0, 1, true);
    }

    /// Resets this instance to the empty-message state.
    void reset() {
        for (Blake2s leaf : leaves) {
            leaf.reset();
        }
        root.reset();
        Arrays.fill(stripe, (byte) 0);
        stripeLength = 0;
        finished = false;
    }

    /// Adds one input byte.
    void update(int value) {
        ensureActive();
        stripe[stripeLength++] = (byte) value;
        if (stripeLength == stripe.length) {
            processFullStripe();
        }
    }

    /// Adds every byte from the requested input range.
    void update(byte[] input, int offset, int length) {
        ensureActive();
        Objects.checkFromIndexSize(offset, length, input.length);
        int position = offset;
        int remaining = length;
        while (remaining > 0) {
            int count = Math.min(remaining, stripe.length - stripeLength);
            System.arraycopy(input, position, stripe, stripeLength, count);
            stripeLength += count;
            position += count;
            remaining -= count;
            if (stripeLength == stripe.length) {
                processFullStripe();
            }
        }
    }

    /// Finalizes and returns the 32-byte BLAKE2sp digest.
    byte[] digest() {
        ensureActive();
        for (int index = 0; index < leaves.length; index++) {
            int offset = index * BLOCK_SIZE;
            if (stripeLength > offset) {
                leaves[index].update(
                        stripe,
                        offset,
                        Math.min(BLOCK_SIZE, stripeLength - offset)
                );
            }
        }

        byte[] leafDigests = new byte[PARALLELISM * DIGEST_SIZE];
        for (int index = 0; index < leaves.length; index++) {
            byte[] digest = leaves[index].digest();
            System.arraycopy(digest, 0, leafDigests, index * DIGEST_SIZE, digest.length);
            Arrays.fill(digest, (byte) 0);
        }
        root.update(leafDigests, 0, leafDigests.length);
        byte[] result = root.digest();

        Arrays.fill(leafDigests, (byte) 0);
        Arrays.fill(stripe, (byte) 0);
        stripeLength = 0;
        finished = true;
        return result;
    }

    /// Distributes one complete input stripe to the eight leaf hashes.
    private void processFullStripe() {
        for (int index = 0; index < leaves.length; index++) {
            leaves[index].update(stripe, index * BLOCK_SIZE, BLOCK_SIZE);
        }
        Arrays.fill(stripe, (byte) 0);
        stripeLength = 0;
    }

    /// Requires this state not to have been finalized.
    private void ensureActive() {
        if (finished) {
            throw new IllegalStateException("BLAKE2sp digest has already been finalized");
        }
    }

    /// Implements one parameterized BLAKE2s tree node.
    @NotNullByDefault
    private static final class Blake2s {
        /// The tree node offset encoded in the parameter block.
        private final int nodeOffset;

        /// The tree node depth encoded in the parameter block.
        private final int nodeDepth;

        /// Whether this is the final node at its tree depth.
        private final boolean lastNode;

        /// The eight chaining words.
        private final int[] state = new int[8];

        /// The current uncompressed input block.
        private final byte[] block = new byte[BLOCK_SIZE];

        /// The decoded message words reused by compression rounds.
        private final int[] message = new int[16];

        /// The compression work vector.
        private final int[] work = new int[16];

        /// The number of bytes represented by previously compressed blocks.
        private long byteCount;

        /// The number of bytes retained in the current block.
        private int blockLength;

        /// Whether this node has been finalized.
        private boolean finished;

        /// Creates one BLAKE2s tree node.
        private Blake2s(int nodeOffset, int nodeDepth, boolean lastNode) {
            this.nodeOffset = nodeOffset;
            this.nodeDepth = nodeDepth;
            this.lastNode = lastNode;
            reset();
        }

        /// Resets this node and applies its 32-byte tree parameter block.
        private void reset() {
            System.arraycopy(INITIALIZATION_VECTOR, 0, state, 0, state.length);
            state[0] ^= DIGEST_SIZE | PARALLELISM << 16 | 2 << 24;
            state[2] ^= nodeOffset;
            state[3] ^= nodeDepth << 16 | DIGEST_SIZE << 24;
            Arrays.fill(block, (byte) 0);
            Arrays.fill(message, 0);
            Arrays.fill(work, 0);
            byteCount = 0L;
            blockLength = 0;
            finished = false;
        }

        /// Adds input while retaining the final full block for finalization.
        private void update(byte[] input, int offset, int length) {
            if (finished) {
                throw new IllegalStateException("BLAKE2s node has already been finalized");
            }
            Objects.checkFromIndexSize(offset, length, input.length);
            int position = offset;
            int remaining = length;
            while (remaining > 0) {
                if (blockLength == block.length) {
                    byteCount += block.length;
                    compress(false);
                    blockLength = 0;
                }
                int count = Math.min(remaining, block.length - blockLength);
                System.arraycopy(input, position, block, blockLength, count);
                blockLength += count;
                position += count;
                remaining -= count;
            }
        }

        /// Finalizes and returns this node's 32-byte digest.
        private byte[] digest() {
            if (finished) {
                throw new IllegalStateException("BLAKE2s node has already been finalized");
            }
            byteCount += blockLength;
            Arrays.fill(block, blockLength, block.length, (byte) 0);
            compress(true);

            byte[] output = new byte[DIGEST_SIZE];
            for (int index = 0; index < state.length; index++) {
                writeLittleEndianInt(state[index], output, index * Integer.BYTES);
            }
            Arrays.fill(block, (byte) 0);
            Arrays.fill(message, 0);
            Arrays.fill(work, 0);
            blockLength = 0;
            finished = true;
            return output;
        }

        /// Applies the ten-round BLAKE2s compression function to the current block.
        private void compress(boolean lastBlock) {
            for (int index = 0; index < message.length; index++) {
                message[index] = readLittleEndianInt(block, index * Integer.BYTES);
            }
            System.arraycopy(state, 0, work, 0, state.length);
            System.arraycopy(
                    INITIALIZATION_VECTOR,
                    0,
                    work,
                    state.length,
                    INITIALIZATION_VECTOR.length
            );
            work[12] ^= (int) byteCount;
            work[13] ^= (int) (byteCount >>> Integer.SIZE);
            if (lastBlock) {
                work[14] = ~work[14];
                if (lastNode) {
                    work[15] = ~work[15];
                }
            }

            for (int round = 0; round < MESSAGE_SCHEDULE.length; round++) {
                byte[] schedule = MESSAGE_SCHEDULE[round];
                mix(0, 4, 8, 12, message[schedule[0]], message[schedule[1]]);
                mix(1, 5, 9, 13, message[schedule[2]], message[schedule[3]]);
                mix(2, 6, 10, 14, message[schedule[4]], message[schedule[5]]);
                mix(3, 7, 11, 15, message[schedule[6]], message[schedule[7]]);
                mix(0, 5, 10, 15, message[schedule[8]], message[schedule[9]]);
                mix(1, 6, 11, 12, message[schedule[10]], message[schedule[11]]);
                mix(2, 7, 8, 13, message[schedule[12]], message[schedule[13]]);
                mix(3, 4, 9, 14, message[schedule[14]], message[schedule[15]]);
            }
            for (int index = 0; index < state.length; index++) {
                state[index] ^= work[index] ^ work[index + state.length];
            }
        }

        /// Applies one BLAKE2s G mixing operation.
        private void mix(int a, int b, int c, int d, int first, int second) {
            work[a] += work[b] + first;
            work[d] = Integer.rotateRight(work[d] ^ work[a], 16);
            work[c] += work[d];
            work[b] = Integer.rotateRight(work[b] ^ work[c], 12);
            work[a] += work[b] + second;
            work[d] = Integer.rotateRight(work[d] ^ work[a], 8);
            work[c] += work[d];
            work[b] = Integer.rotateRight(work[b] ^ work[c], 7);
        }

        /// Reads one little-endian 32-bit word.
        private static int readLittleEndianInt(byte[] input, int offset) {
            return Byte.toUnsignedInt(input[offset])
                    | Byte.toUnsignedInt(input[offset + 1]) << 8
                    | Byte.toUnsignedInt(input[offset + 2]) << 16
                    | Byte.toUnsignedInt(input[offset + 3]) << 24;
        }

        /// Writes one little-endian 32-bit word.
        private static void writeLittleEndianInt(int value, byte[] output, int offset) {
            output[offset] = (byte) value;
            output[offset + 1] = (byte) (value >>> 8);
            output[offset + 2] = (byte) (value >>> 16);
            output[offset + 3] = (byte) (value >>> 24);
        }
    }
}
