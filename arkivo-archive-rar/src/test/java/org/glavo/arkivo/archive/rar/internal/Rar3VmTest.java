// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies native RAR3 standard filters, VM execution, and filtered-output scheduling.
@NotNullByDefault
public final class Rar3VmTest {
    /// Applies the standard x86 and channel-delta transforms to known inputs.
    @Test
    public void appliesStandardFilters() throws IOException {
        int[] registers = new int[7];
        assertArrayEquals(
                new byte[]{(byte) 0xe8, 4, 0, 0, 0},
                Rar3StandardFilters.apply(0, registers, new byte[]{(byte) 0xe8, 5, 0, 0, 0}, 0L)
        );

        registers[0] = 2;
        assertArrayEquals(
                new byte[]{-1, -2, -2, -4, -3, -6},
                Rar3StandardFilters.apply(3, registers, new byte[]{1, 1, 1, 2, 2, 2}, 0L)
        );
    }

    /// Runs a custom program that selects a shorter output range from VM memory.
    @Test
    public void executesCustomVmProgram() throws IOException {
        Rar3Vm.Program program = Rar3Vm.compile(sliceProgram());
        assertArrayEquals(
                new byte[]{20, 30},
                program.execute(new byte[]{10, 20, 30}, new int[7], 0, new byte[0], 0L)
        );
    }

    /// Parses a new filter descriptor and applies it when its complete raw block arrives.
    @Test
    public void schedulesParsedFilterDescriptor() throws IOException {
        byte[] program = sliceProgram();
        BitWriter payload = new BitWriter();
        payload.writeEncodedUint32(0);
        payload.writeEncodedUint32(3);
        payload.writeEncodedUint32(program.length);
        for (byte value : program) payload.write(value & 0xff, 8);

        byte[] encodedPayload = payload.toByteArray();
        byte[] descriptorBytes = new byte[encodedPayload.length + 1];
        descriptorBytes[0] = 0x20;
        System.arraycopy(encodedPayload, 0, descriptorBytes, 1, encodedPayload.length);

        Rar3FilterManager manager = new Rar3FilterManager();
        Rar3FilterManager.Descriptor descriptor = manager.parse(descriptorBytes);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Rar3OutputPipeline pipeline = new Rar3OutputPipeline(output, 2);
        pipeline.schedule(descriptor);
        pipeline.accept(10);
        pipeline.accept(20);
        pipeline.accept(30);
        pipeline.finish();

        assertArrayEquals(new byte[]{20, 30}, output.toByteArray());
        assertTrue(pipeline.isComplete());
    }

    /// Parses every optional descriptor field and preserves immutable register and global-data values.
    @Test
    public void parsesCompleteFilterDescriptor() throws IOException {
        byte[] program = sliceProgram();
        BitWriter payload = new BitWriter();
        payload.writeEncodedUint32(0);
        payload.writeEncodedUint32(5);
        payload.writeEncodedUint32(3);
        payload.write(0x45, 7);
        payload.writeEncodedUint32(11);
        payload.writeEncodedUint32(22);
        payload.writeEncodedUint32(33);
        payload.writeEncodedUint32(program.length);
        payload.writeBytes(program);
        payload.writeEncodedUint32(3);
        payload.writeBytes(new byte[]{9, 8, 7});

        Rar3FilterManager.Descriptor descriptor =
                new Rar3FilterManager().parse(descriptor(0xf8, payload));

        assertTrue(descriptor.resetQueuedFilters());
        assertEquals(263, descriptor.relativeOffset());
        assertEquals(3, descriptor.blockLength());
        assertEquals(0x45, descriptor.initialRegisterMask());
        assertArrayEquals(new int[]{11, 0, 22, 0, 0, 0, 33}, descriptor.initialRegisters());
        assertArrayEquals(new byte[]{9, 8, 7}, descriptor.initialGlobal());

        int[] registers = {1, 2, 3, 4, 5, 6, 7};
        byte[] global = {4, 5, 6};
        Rar3FilterManager.Descriptor copied = new Rar3FilterManager.Descriptor(
                false,
                0,
                1,
                descriptor.program(),
                registers,
                0x7f,
                global
        );
        registers[0] = 0;
        global[0] = 0;
        assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6, 7}, copied.initialRegisters());
        assertArrayEquals(new byte[]{4, 5, 6}, copied.initialGlobal());
    }

    /// Verifies filter definitions and block lengths can be selected and reused until an explicit reset.
    @Test
    public void reusesAndResetsFilterDefinitions() throws IOException {
        Rar3FilterManager manager = new Rar3FilterManager();
        Rar3FilterManager.Descriptor first = manager.parse(newProgramDescriptor(0, 3, sliceProgram()));

        Rar3FilterManager.Descriptor second = manager.parse(newProgramDescriptor(1, 4, sliceProgram()));
        assertEquals(4, second.blockLength());
        assertFalse(second.resetQueuedFilters());

        BitWriter selectFirst = new BitWriter();
        selectFirst.writeEncodedUint32(1);
        selectFirst.writeEncodedUint32(2);
        Rar3FilterManager.Descriptor reused = manager.parse(descriptor(0x80, selectFirst));
        assertSame(first.program(), reused.program());
        assertEquals(3, reused.blockLength());
        assertEquals(2, reused.relativeOffset());

        manager.reset();
        BitWriter missingLength = new BitWriter();
        missingLength.writeEncodedUint32(0);
        assertThrows(IOException.class, () -> manager.parse(descriptor(0, missingLength)));
    }

    /// Verifies malformed filter descriptors fail at each bounded numeric field.
    @Test
    public void rejectsMalformedFilterDescriptors() throws IOException {
        assertThrows(NullPointerException.class, () -> new Rar3FilterManager().parse(null));
        assertThrows(IOException.class, () -> new Rar3FilterManager().parse(new byte[0]));
        assertThrows(IOException.class, () -> new Rar3FilterManager().parse(new byte[]{0x20}));

        BitWriter invalidFilter = new BitWriter();
        invalidFilter.writeEncodedUint32(2);
        assertThrows(
                IOException.class,
                () -> new Rar3FilterManager().parse(descriptor(0x80, invalidFilter))
        );

        BitWriter largeOffset = new BitWriter();
        largeOffset.writeEncodedUint32(0x8000_0000);
        assertThrows(IOException.class, () -> new Rar3FilterManager().parse(descriptor(0, largeOffset)));

        assertThrows(IOException.class, () -> new Rar3FilterManager().parse(blockLengthDescriptor(0)));
        assertThrows(
                IOException.class,
                () -> new Rar3FilterManager().parse(blockLengthDescriptor(Rar3Vm.MEMORY_MASK + 1))
        );
        assertThrows(IOException.class, () -> new Rar3FilterManager().parse(codeSizeDescriptor(0)));
        assertThrows(IOException.class, () -> new Rar3FilterManager().parse(codeSizeDescriptor(0x10001)));

        Rar3FilterManager manager = new Rar3FilterManager();
        manager.parse(newProgramDescriptor(0, 1, sliceProgram()));
        BitWriter largeGlobal = new BitWriter();
        largeGlobal.writeEncodedUint32(0);
        largeGlobal.writeEncodedUint32(Rar3Vm.GLOBAL_SIZE - Rar3Vm.FIXED_GLOBAL_SIZE + 1);
        assertThrows(IOException.class, () -> manager.parse(descriptor(0x08, largeGlobal)));
    }

    /// Builds a checksummed program that returns two bytes beginning at VM address one.
    private static byte[] sliceProgram() {
        BitWriter writer = new BitWriter();
        writer.write(0, 1);
        writer.writeMoveDirect(Rar3Vm.GLOBAL_ADDRESS + 0x1c, 2);
        writer.writeMoveDirect(Rar3Vm.GLOBAL_ADDRESS + 0x20, 1);
        writer.write(0b101110, 6);
        byte[] body = writer.toByteArray();
        byte[] code = new byte[body.length + 1];
        int checksum = 0;
        for (byte value : body) checksum ^= value & 0xff;
        code[0] = (byte) checksum;
        System.arraycopy(body, 0, code, 1, body.length);
        return code;
    }

    /// Builds a descriptor that defines a new filter and its remembered block length.
    private static byte[] newProgramDescriptor(int filter, int blockLength, byte[] program) {
        BitWriter payload = new BitWriter();
        int flags = 0x20;
        if (filter != 0) {
            flags |= 0x80;
            payload.writeEncodedUint32(filter + 1);
        }
        payload.writeEncodedUint32(0);
        payload.writeEncodedUint32(blockLength);
        payload.writeEncodedUint32(program.length);
        payload.writeBytes(program);
        return descriptor(flags, payload);
    }

    /// Builds a descriptor that reaches the code-size field with the supplied value.
    private static byte[] codeSizeDescriptor(int codeSize) {
        BitWriter payload = new BitWriter();
        payload.writeEncodedUint32(0);
        payload.writeEncodedUint32(1);
        payload.writeEncodedUint32(codeSize);
        return descriptor(0x20, payload);
    }

    /// Builds a descriptor that carries the supplied explicit block length.
    private static byte[] blockLengthDescriptor(int blockLength) {
        BitWriter payload = new BitWriter();
        payload.writeEncodedUint32(0);
        payload.writeEncodedUint32(blockLength);
        return descriptor(0x20, payload);
    }

    /// Prefixes one encoded descriptor payload with its flags byte.
    private static byte[] descriptor(int flags, BitWriter payload) {
        byte[] encodedPayload = payload.toByteArray();
        byte[] descriptor = new byte[encodedPayload.length + 1];
        descriptor[0] = (byte) flags;
        System.arraycopy(encodedPayload, 0, descriptor, 1, encodedPayload.length);
        return descriptor;
    }

    /// Writes the bit-oriented fields used by synthetic RAR3 descriptors and VM programs.
    @NotNullByDefault
    private static final class BitWriter {
        /// The completed bytes.
        private byte[] bytes = new byte[32];
        /// The number of bits written.
        private int bitCount;

        /// Creates an empty writer.
        private BitWriter() {
        }

        /// Writes one dword MOV from an immediate to a direct memory address.
        private void writeMoveDirect(int address, int value) {
            write(0, 4);
            write(0, 1);
            write(0b0111, 4);
            writeEncodedUint32(address);
            write(0, 2);
            writeEncodedUint32(value);
        }

        /// Writes one RAR3 variable-width integer using its shortest ordinary representation.
        private void writeEncodedUint32(int value) {
            if (value >= 0 && value <= 0x0f) {
                write(0, 2);
                write(value, 4);
            } else if (value >= 0 && value <= 0xff) {
                write(1, 2);
                write(value, 8);
            } else if (value >= 0 && value <= 0xffff) {
                write(2, 2);
                write(value, 16);
            } else {
                write(3, 2);
                write(value, 32);
            }
        }

        /// Writes complete bytes without adding alignment between values.
        private void writeBytes(byte[] values) {
            for (byte value : values) {
                write(value & 0xff, 8);
            }
        }

        /// Appends the requested most-significant bits of one integer.
        private void write(int value, int count) {
            ensureCapacity(bitCount + count);
            for (int bit = count - 1; bit >= 0; bit--) {
                if (((value >>> bit) & 1) != 0) bytes[bitCount >>> 3] |= (byte) (1 << (7 - (bitCount & 7)));
                bitCount++;
            }
        }

        /// Returns the byte-aligned representation with zero padding.
        private byte[] toByteArray() {
            return Arrays.copyOf(bytes, (bitCount + 7) >>> 3);
        }

        /// Expands the backing storage for a requested bit count.
        private void ensureCapacity(int requiredBits) {
            int requiredBytes = (requiredBits + 7) >>> 3;
            if (requiredBytes > bytes.length) bytes = Arrays.copyOf(bytes, Math.max(requiredBytes, bytes.length * 2));
        }
    }
}
