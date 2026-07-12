// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
