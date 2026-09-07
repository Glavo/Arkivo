// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz.internal.filter;

import org.glavo.arkivo.codec.transform.TransformingInputStream;
import org.glavo.arkivo.codec.transform.TransformingOutputStream;
import org.glavo.arkivo.codec.transform.TransformingReadableByteChannel;
import org.glavo.arkivo.codec.transform.TransformingWritableByteChannel;
import org.glavo.arkivo.codec.transform.ByteTransform;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.tukaani.xz.ARM64Options;
import org.tukaani.xz.ARMOptions;
import org.tukaani.xz.ARMThumbOptions;
import org.tukaani.xz.ArrayCache;
import org.tukaani.xz.FilterOptions;
import org.tukaani.xz.FinishableOutputStream;
import org.tukaani.xz.FinishableWrapperOutputStream;
import org.tukaani.xz.IA64Options;
import org.tukaani.xz.PowerPCOptions;
import org.tukaani.xz.RISCVOptions;
import org.tukaani.xz.SPARCOptions;
import org.tukaani.xz.X86Options;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests BCJ transforms against independent XZ filter streams.
@NotNullByDefault
public final class BCJTransformsTest {
    /// The nonzero start offset used to verify state initialization.
    private static final int START_OFFSET = 0x1000;

    /// Verifies every 7z BCJ architecture against XZ with cross-buffer instructions and a nonzero start offset.
    @Test
    public void bcjInteroperabilityAcrossChunks() throws IOException {
        assertBcjInteroperability(x86Sample(), x86Options(), BCJTransforms::x86);
        assertBcjInteroperability(powerPCSample(), powerPCOptions(), BCJTransforms::powerPC);
        assertBcjInteroperability(ia64Sample(), ia64Options(), BCJTransforms::ia64);
        assertBcjInteroperability(armSample(), armOptions(), BCJTransforms::arm);
        assertBcjInteroperability(armThumbSample(), armThumbOptions(), BCJTransforms::armThumb);
        assertBcjInteroperability(sparcSample(), sparcOptions(), BCJTransforms::sparc);
        assertBcjInteroperability(arm64Sample(), arm64Options(), BCJTransforms::arm64);
        assertBcjInteroperability(riscVSample(), riscVOptions(), BCJTransforms::riscV);
    }

    /// Verifies overlapping x86 opcode candidates exercise recent-candidate masks exactly like XZ for Java.
    @Test
    public void denseX86CandidatesMatchReference() throws IOException {
        byte[] sample = new byte[32_771];
        new Random(0x86bc_1a5eL).nextBytes(sample);
        for (int offset = 0; offset + 16 < sample.length; offset += 32) {
            if ((offset & 32) == 0) {
                sample[offset] = (byte) 0xe8;
                sample[offset + 1] = (byte) 0xe9;
                sample[offset + 2] = (byte) 0xe8;
                sample[offset + 3] = (byte) 0xe9;
                sample[offset + 4] = 0x7f;
                sample[offset + 5] = 0x7f;
                sample[offset + 6] = (byte) 0xe8;
                sample[offset + 7] = 0;
                sample[offset + 10] = 0;
            } else {
                sample[offset] = (byte) 0xe8;
                sample[offset + 2] = (byte) 0xe9;
                sample[offset + 4] = 0x7f;
                sample[offset + 5] = 0x7f;
                sample[offset + 6] = 0;
            }
        }

        assertBcjInteroperability(sample, x86Options(), BCJTransforms::x86);
    }

    /// Verifies that a tail shorter than one x86 instruction passes through unchanged.
    @Test
    public void incompleteInstructionTailPassesThrough() throws IOException {
        byte[] original = new byte[]{(byte) 0xe8, 1, 2, 3};
        byte[] encoded = encodeNatively(original, BCJTransforms.x86(ByteTransform.Direction.ENCODE, 0));
        assertArrayEquals(original, encoded);
        assertArrayEquals(original, decodeNatively(encoded, BCJTransforms.x86(ByteTransform.Direction.DECODE, 0)));
    }

    /// Verifies all transform factories reject null directions and offsets outside the unsigned 32-bit domain.
    @Test
    public void transformFactoriesValidateArguments() {
        assertThrows(NullPointerException.class, () -> BCJTransforms.x86(null, 0L));
        assertThrows(IllegalArgumentException.class, () -> BCJTransforms.arm64(ByteTransform.Direction.ENCODE, -1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> BCJTransforms.riscV(ByteTransform.Direction.DECODE, 0x1_0000_0000L)
        );
    }

    /// Verifies one BCJ encoder and decoder against XZ.
    private static void assertBcjInteroperability(
            byte[] original,
            FilterOptions options,
            TransformFactory factory
    ) throws IOException {
        byte[] expected = encodeWithXz(original, options);
        byte[] encoded = encodeNatively(original, factory.create(ByteTransform.Direction.ENCODE, START_OFFSET));
        assertFalse(Arrays.equals(original, expected));
        assertArrayEquals(expected, encoded);
        assertArrayEquals(original, decodeNatively(encoded, factory.create(ByteTransform.Direction.DECODE, START_OFFSET)));

        for (int chunk : new int[]{1, 7, 4093, 8191, 8192, 8193}) {
            for (boolean direct : new boolean[]{false, true}) {
                assertArrayEquals(expected, encodeThroughChannel(original,
                        factory.create(ByteTransform.Direction.ENCODE, START_OFFSET), chunk, direct));
                assertArrayEquals(original, decodeThroughChannel(expected,
                        factory.create(ByteTransform.Direction.DECODE, START_OFFSET), chunk, direct));
            }
        }

        try (InputStream xzDecoder = options.getInputStream(
                new ByteArrayInputStream(encoded),
                ArrayCache.getDummyCache()
        )) {
            assertArrayEquals(original, xzDecoder.readAllBytes());
        }
    }

    /// Encodes bounded, read-only source windows and checks that finish never repeats a buffered tail.
    private static byte[] encodeThroughChannel(byte[] original, ByteTransform transform, int chunk, boolean direct)
            throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        ByteBuffer storage = direct ? ByteBuffer.allocateDirect(chunk + 4) : ByteBuffer.allocate(chunk + 4);
        try (TransformingWritableByteChannel output = new TransformingWritableByteChannel(
                Channels.newChannel(target), transform)) {
            for (int offset = 0; offset < original.length; offset += chunk) {
                int count = Math.min(chunk, original.length - offset);
                storage.clear().position(2);
                storage.put(original, offset, count);
                ByteBuffer source = storage.asReadOnlyBuffer().position(2).mark().limit(2 + count);
                assertEquals(count, output.write(source));
                assertEquals(2 + count, source.position());
                assertEquals(2 + count, source.limit());
                source.reset();
                assertEquals(2, source.position());
            }
            output.finish();
            int finishedSize = target.size();
            output.finish();
            assertEquals(finishedSize, target.size());
        }
        return target.toByteArray();
    }

    /// Decodes fragmented input into guarded windows that repeatedly split ready output across working buffers.
    private static byte[] decodeThroughChannel(byte[] encoded, ByteTransform transform, int chunk, boolean direct)
            throws IOException {
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        ByteBuffer storage = direct ? ByteBuffer.allocateDirect(8197) : ByteBuffer.allocate(8197);
        byte[] bytes = new byte[8193];
        try (TransformingReadableByteChannel input = new TransformingReadableByteChannel(
                Channels.newChannel(new ChunkedInputStream(encoded, chunk)), transform)) {
            while (true) {
                storage.clear();
                storage.put(0, (byte) 99).put(1, (byte) 99).put(8195, (byte) 99).put(8196, (byte) 99);
                storage.position(2).mark().limit(8195);
                int count = input.read(storage);
                assertEquals(8195, storage.limit());
                assertEquals(count < 0 ? 2 : 2 + count, storage.position());
                storage.reset();
                assertEquals(2, storage.position());
                if (count < 0) {
                    break;
                }
                assertTrue(count > 0);
                storage.get(bytes, 0, count);
                actual.write(bytes, 0, count);
                storage.clear();
                assertEquals(99, storage.get(0));
                assertEquals(99, storage.get(1));
                assertEquals(99, storage.get(8195));
                assertEquals(99, storage.get(8196));
            }
            assertEquals(-1, input.read(ByteBuffer.allocate(1)));
        }
        return actual.toByteArray();
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
    private static PowerPCOptions powerPCOptions() throws IOException {
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

    /// Returns XZ ARM64 options configured with the shared start offset.
    private static ARM64Options arm64Options() throws IOException {
        ARM64Options options = new ARM64Options();
        options.setStartOffset(START_OFFSET);
        return options;
    }

    /// Returns XZ RISC-V options configured with the shared start offset.
    private static RISCVOptions riscVOptions() throws IOException {
        RISCVOptions options = new RISCVOptions();
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
    private static byte[] powerPCSample() {
        byte[] sample = filledSample((byte) 0);
        ByteArrayAccess.writeIntBigEndian(sample, 0, 0x48000001);
        ByteArrayAccess.writeIntBigEndian(sample, 8188, 0x4bfffffd);
        ByteArrayAccess.writeIntBigEndian(sample, 8500, 0x48000101);
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
        ByteArrayAccess.writeIntBigEndian(sample, 0, 0x40000010);
        ByteArrayAccess.writeIntBigEndian(sample, 8188, 0x7ffffff0);
        ByteArrayAccess.writeIntBigEndian(sample, 8500, 0x40000100);
        return sample;
    }

    /// Returns little-endian ARM64 BL and ADRP instructions around the transform-buffer boundary.
    private static byte[] arm64Sample() {
        byte[] sample = filledSample((byte) 0);
        ByteArrayAccess.writeIntLittleEndian(sample, 0, 0x9400_0008);
        putArm64Adrp(sample, 64, 0x0004_0000);
        ByteArrayAccess.writeIntLittleEndian(sample, 8188, 0x9000_0000);
        ByteArrayAccess.writeIntLittleEndian(sample, 8500, 0x97ff_fff0);
        return sample;
    }

    /// Returns RISC-V JAL, AUIPC pairs, and a reversible special-format candidate across chunk boundaries.
    private static byte[] riscVSample() {
        byte[] sample = filledSample((byte) 0);
        ByteArrayAccess.writeIntLittleEndian(sample, 0, 0x0000_00ef);
        putRiscVAuipcPair(sample, 16, 5, 0x1234_5000, 0x123);
        putRiscVSpecialPair(sample, 48, 5, 0x1234_5678);
        ByteArrayAccess.writeIntLittleEndian(sample, 64, 0x0000_01ef);
        ByteArrayAccess.writeIntLittleEndian(sample, 72, 0x0000_0297);
        ByteArrayAccess.writeIntLittleEndian(sample, 76, 0x0000_0000);
        ByteArrayAccess.writeIntLittleEndian(sample, 88, 0x0000_0017);
        putRiscVAuipcPair(sample, 8188, 10, 0x7fff_f000, -16);
        ByteArrayAccess.writeIntLittleEndian(sample, 8500, 0x0010_02ef);
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
        ByteArrayAccess.writeIntLittleEndian(sample, offset + 1, address);
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

    /// Stores a valid RISC-V AUIPC and I-type instruction pair using the same source register.
    private static void putRiscVAuipcPair(
            byte[] sample,
            int offset,
            int register,
            int upperImmediate,
            int lowerImmediate
    ) {
        ByteArrayAccess.writeIntLittleEndian(sample, offset, upperImmediate | register << 7 | 0x17);
        ByteArrayAccess.writeIntLittleEndian(
                sample,
                offset + Integer.BYTES,
                lowerImmediate << 20 | register << 15 | register << 7 | 0x13
        );
    }

    /// Stores a RISC-V special-format candidate that exercises the transform's arbitrary-data bijection.
    private static void putRiscVSpecialPair(byte[] sample, int offset, int sourceRegister, int address) {
        int instruction = sourceRegister << 27 | 3 << 12 | 2 << 7 | 0x17;
        ByteArrayAccess.writeIntLittleEndian(sample, offset, instruction);
        ByteArrayAccess.writeIntLittleEndian(sample, offset + Integer.BYTES, address);
    }

    /// Stores an ARM64 ADRP instruction with the requested page-relative immediate.
    private static void putArm64Adrp(byte[] sample, int offset, int address) {
        int instruction = 0x9000_0000 | (address & 3) << 29 | (address & 0x001f_fffc) << 3;
        ByteArrayAccess.writeIntLittleEndian(sample, offset, instruction);
    }

    /// Creates stateful encoder and decoder transforms for one BCJ architecture.
    @FunctionalInterface
    @NotNullByDefault
    private interface TransformFactory {
        /// Creates a transform in the requested direction with an absolute start offset.
        ByteTransform create(ByteTransform.Direction direction, long startOffset);
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
