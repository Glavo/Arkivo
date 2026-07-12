// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bcj;

import org.glavo.arkivo.codec.transform.TransformingInputStream;
import org.glavo.arkivo.codec.transform.TransformingOutputStream;
import org.glavo.arkivo.codec.transform.ByteTransform;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.tukaani.xz.ARMOptions;
import org.tukaani.xz.ARMThumbOptions;
import org.tukaani.xz.ArrayCache;
import org.tukaani.xz.FilterOptions;
import org.tukaani.xz.FinishableOutputStream;
import org.tukaani.xz.FinishableWrapperOutputStream;
import org.tukaani.xz.IA64Options;
import org.tukaani.xz.PowerPCOptions;
import org.tukaani.xz.SPARCOptions;
import org.tukaani.xz.X86Options;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/// Tests BCJ transforms against independent XZ filter streams.
@NotNullByDefault
public final class BCJTransformsTest {
    /// The nonzero start offset used to verify state initialization.
    private static final int START_OFFSET = 0x1000;

    /// Verifies every 7z BCJ architecture against XZ with cross-buffer instructions and a nonzero start offset.
    @Test
    public void bcjInteroperabilityAcrossChunks() throws IOException {
        assertBcjInteroperability(x86Sample(), x86Options(), BCJTransforms::x86);
        assertBcjInteroperability(powerPcSample(), powerPcOptions(), BCJTransforms::powerPc);
        assertBcjInteroperability(ia64Sample(), ia64Options(), BCJTransforms::ia64);
        assertBcjInteroperability(armSample(), armOptions(), BCJTransforms::arm);
        assertBcjInteroperability(armThumbSample(), armThumbOptions(), BCJTransforms::armThumb);
        assertBcjInteroperability(sparcSample(), sparcOptions(), BCJTransforms::sparc);
    }

    /// Verifies that a tail shorter than one x86 instruction passes through unchanged.
    @Test
    public void incompleteInstructionTailPassesThrough() throws IOException {
        byte[] original = new byte[]{(byte) 0xe8, 1, 2, 3};
        byte[] encoded = encodeNatively(original, BCJTransforms.x86(true, 0));
        assertArrayEquals(original, encoded);
        assertArrayEquals(original, decodeNatively(encoded, BCJTransforms.x86(false, 0)));
    }

    /// Verifies one BCJ encoder and decoder against XZ.
    private static void assertBcjInteroperability(
            byte[] original,
            FilterOptions options,
            TransformFactory factory
    ) throws IOException {
        byte[] expected = encodeWithXz(original, options);
        byte[] encoded = encodeNatively(original, factory.create(true, START_OFFSET));
        assertFalse(Arrays.equals(original, expected));
        assertArrayEquals(expected, encoded);
        assertArrayEquals(original, decodeNatively(encoded, factory.create(false, START_OFFSET)));

        try (InputStream xzDecoder = options.getInputStream(
                new ByteArrayInputStream(encoded),
                ArrayCache.getDummyCache()
        )) {
            assertArrayEquals(original, xzDecoder.readAllBytes());
        }
    }

    /// Encodes bytes through a native filter using deliberately fragmented writes.
    private static byte[] encodeNatively(byte[] original, ByteTransform transform) throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (TransformingOutputStream output = new TransformingOutputStream(target, transform)) {
            for (byte value : original) {
                output.write(value);
            }
        }
        return target.toByteArray();
    }

    /// Decodes bytes through a native filter whose source exposes at most three bytes per read.
    private static byte[] decodeNatively(byte[] encoded, ByteTransform transform) throws IOException {
        try (TransformingInputStream input = new TransformingInputStream(
                new ChunkedInputStream(encoded, 3),
                transform
        )) {
            return input.readAllBytes();
        }
    }

    /// Encodes bytes through the corresponding independent XZ filter.
    private static byte[] encodeWithXz(byte[] original, FilterOptions options) throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        FinishableOutputStream output = options.getOutputStream(
                new FinishableWrapperOutputStream(target),
                ArrayCache.getDummyCache()
        );
        output.write(original);
        output.finish();
        return target.toByteArray();
    }

    /// Returns XZ x86 options configured with the shared start offset.
    private static X86Options x86Options() throws IOException {
        X86Options options = new X86Options();
        options.setStartOffset(START_OFFSET);
        return options;
    }

    /// Returns XZ PowerPC options configured with the shared start offset.
    private static PowerPCOptions powerPcOptions() throws IOException {
        PowerPCOptions options = new PowerPCOptions();
        options.setStartOffset(START_OFFSET);
        return options;
    }

    /// Returns XZ IA-64 options configured with the shared start offset.
    private static IA64Options ia64Options() throws IOException {
        IA64Options options = new IA64Options();
        options.setStartOffset(START_OFFSET);
        return options;
    }

    /// Returns XZ ARM options configured with the shared start offset.
    private static ARMOptions armOptions() throws IOException {
        ARMOptions options = new ARMOptions();
        options.setStartOffset(START_OFFSET);
        return options;
    }

    /// Returns XZ ARM-Thumb options configured with the shared start offset.
    private static ARMThumbOptions armThumbOptions() throws IOException {
        ARMThumbOptions options = new ARMThumbOptions();
        options.setStartOffset(START_OFFSET);
        return options;
    }

    /// Returns XZ SPARC options configured with the shared start offset.
    private static SPARCOptions sparcOptions() throws IOException {
        SPARCOptions options = new SPARCOptions();
        options.setStartOffset(START_OFFSET);
        return options;
    }

    /// Returns x86 bytes with CALL and JMP operands on both sides of the 8 KiB filter boundary.
    private static byte[] x86Sample() {
        byte[] sample = filledSample((byte) 0x90);
        putX86Branch(sample, 5, 0xe8, 0x20);
        putX86Branch(sample, 8189, 0xe9, -0x40);
        putX86Branch(sample, 8500, 0xe8, 0x100);
        return sample;
    }

    /// Returns big-endian PowerPC branch instructions.
    private static byte[] powerPcSample() {
        byte[] sample = filledSample((byte) 0);
        putIntBigEndian(sample, 0, 0x48000001);
        putIntBigEndian(sample, 8188, 0x4bfffffd);
        putIntBigEndian(sample, 8500, 0x48000101);
        return sample;
    }

    /// Returns IA-64 bundles containing convertible branch slots.
    private static byte[] ia64Sample() {
        byte[] sample = filledSample((byte) 0);
        putIa64BranchBundle(sample, 0, 0x12345);
        putIa64BranchBundle(sample, 8176, 0x23456);
        putIa64BranchBundle(sample, 8496, 0x34567);
        return sample;
    }

    /// Returns little-endian ARM branch-with-link instructions.
    private static byte[] armSample() {
        byte[] sample = filledSample((byte) 0);
        putArmBranch(sample, 0, 0x20);
        putArmBranch(sample, 8188, -0x40);
        putArmBranch(sample, 8500, 0x100);
        return sample;
    }

    /// Returns little-endian ARM-Thumb branch instructions including one crossing the 8 KiB boundary.
    private static byte[] armThumbSample() {
        byte[] sample = filledSample((byte) 0);
        putArmThumbBranch(sample, 0, 0x20);
        putArmThumbBranch(sample, 8190, 0x40);
        putArmThumbBranch(sample, 8500, 0x100);
        return sample;
    }

    /// Returns big-endian SPARC CALL instructions.
    private static byte[] sparcSample() {
        byte[] sample = filledSample((byte) 0);
        putIntBigEndian(sample, 0, 0x40000010);
        putIntBigEndian(sample, 8188, 0x7ffffff0);
        putIntBigEndian(sample, 8500, 0x40000100);
        return sample;
    }

    /// Returns a 9003-byte sample initialized to one byte value.
    private static byte[] filledSample(byte value) {
        byte[] sample = new byte[9003];
        Arrays.fill(sample, value);
        return sample;
    }

    /// Stores one x86 branch opcode and little-endian relative address.
    private static void putX86Branch(byte[] sample, int offset, int opcode, int address) {
        sample[offset] = (byte) opcode;
        putIntLittleEndian(sample, offset + 1, address);
    }

    /// Stores one little-endian ARM branch-with-link instruction.
    private static void putArmBranch(byte[] sample, int offset, int address) {
        int encoded = address >>> 2;
        sample[offset] = (byte) encoded;
        sample[offset + 1] = (byte) (encoded >>> 8);
        sample[offset + 2] = (byte) (encoded >>> 16);
        sample[offset + 3] = (byte) 0xeb;
    }

    /// Stores one little-endian ARM-Thumb branch instruction.
    private static void putArmThumbBranch(byte[] sample, int offset, int address) {
        int encoded = address >>> 1;
        sample[offset + 1] = (byte) (0xf0 | encoded >>> 19 & 7);
        sample[offset] = (byte) (encoded >>> 11);
        sample[offset + 3] = (byte) (0xf8 | encoded >>> 8 & 7);
        sample[offset + 2] = (byte) encoded;
    }

    /// Stores one IA-64 bundle whose third slot is a convertible branch.
    private static void putIa64BranchBundle(byte[] sample, int offset, int address) {
        sample[offset] = 16;
        long instruction = 5L << 37 | (long) (address & 0x0fffff) << 13;
        instruction |= (long) (address & 0x100000) << 16;
        long packed = instruction << 7;
        for (int index = 0; index < 6; index++) {
            sample[offset + 10 + index] = (byte) (packed >>> (index * 8));
        }
    }

    /// Stores one little-endian integer.
    private static void putIntLittleEndian(byte[] sample, int offset, int value) {
        sample[offset] = (byte) value;
        sample[offset + 1] = (byte) (value >>> 8);
        sample[offset + 2] = (byte) (value >>> 16);
        sample[offset + 3] = (byte) (value >>> 24);
    }

    /// Stores one big-endian integer.
    private static void putIntBigEndian(byte[] sample, int offset, int value) {
        sample[offset] = (byte) (value >>> 24);
        sample[offset + 1] = (byte) (value >>> 16);
        sample[offset + 2] = (byte) (value >>> 8);
        sample[offset + 3] = (byte) value;
    }

    /// Creates stateful encoder and decoder transforms for one BCJ architecture.
    @FunctionalInterface
    @NotNullByDefault
    private interface TransformFactory {
        /// Creates an encoder or decoder with the requested absolute start offset.
        ByteTransform create(boolean encoder, int startOffset);
    }

    /// Limits each bulk source read to a fixed number of bytes.
    @NotNullByDefault
    private static final class ChunkedInputStream extends InputStream {
        /// The complete encoded source bytes.
        private final byte[] bytes;

        /// The maximum number of bytes returned by one bulk read.
        private final int maximumChunk;

        /// The next source byte position.
        private int position;

        /// Creates a fragmented source.
        private ChunkedInputStream(byte[] bytes, int maximumChunk) {
            this.bytes = bytes.clone();
            this.maximumChunk = maximumChunk;
        }

        /// Reads one source byte.
        @Override
        public int read() {
            return position == bytes.length ? -1 : Byte.toUnsignedInt(bytes[position++]);
        }

        /// Reads at most the configured chunk size.
        @Override
        public int read(byte[] target, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (length == 0) {
                return 0;
            }
            if (position == bytes.length) {
                return -1;
            }
            int count = Math.min(Math.min(length, maximumChunk), bytes.length - position);
            System.arraycopy(bytes, position, target, offset, count);
            position += count;
            return count;
        }
    }
}
