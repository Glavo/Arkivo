// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies RAR5 post-processing filters, scheduling, buffering, and range validation.
@NotNullByDefault
final class Rar5OutputPipelineTest {
    /// Verifies the delta filter reconstructs interleaved channels without modifying its source.
    @Test
    void appliesDeltaFilter() throws IOException {
        byte[] source = {1, 2, 3, 4};
        assertArrayEquals(
                new byte[]{-1, -3, -3, -7},
                Rar5OutputPipeline.applyFilter(Rar5OutputPipeline.FILTER_DELTA, 2, 0L, source)
        );
        assertArrayEquals(new byte[]{1, 2, 3, 4}, source);
        assertArrayEquals(
                new byte[0],
                Rar5OutputPipeline.applyFilter(Rar5OutputPipeline.FILTER_DELTA, 32, 0L, new byte[0])
        );
    }

    /// Verifies x86 CALL and JMP filters rewrite only eligible 24-bit relative addresses.
    @Test
    void appliesX86Filters() throws IOException {
        byte[] call = x86Instruction(0xe8, 5);
        byte[] originalCall = call.clone();
        assertArrayEquals(
                x86Instruction(0xe8, 4),
                Rar5OutputPipeline.applyFilter(Rar5OutputPipeline.FILTER_X86_E8, 0, 0L, call)
        );
        assertArrayEquals(originalCall, call);

        byte[] jump = x86Instruction(0xe9, 5);
        assertArrayEquals(
                jump,
                Rar5OutputPipeline.applyFilter(Rar5OutputPipeline.FILTER_X86_E8, 0, 0L, jump)
        );
        assertArrayEquals(
                x86Instruction(0xe9, 4),
                Rar5OutputPipeline.applyFilter(Rar5OutputPipeline.FILTER_X86_E8_E9, 0, 0L, jump)
        );
        assertArrayEquals(
                x86Instruction(0xe8, 0x00ff_ffff),
                Rar5OutputPipeline.applyFilter(
                        Rar5OutputPipeline.FILTER_X86_E8,
                        0,
                        0L,
                        x86Instruction(0xe8, -1)
                )
        );
        assertArrayEquals(
                x86Instruction(0xe8, -2),
                Rar5OutputPipeline.applyFilter(
                        Rar5OutputPipeline.FILTER_X86_E8,
                        0,
                        0L,
                        x86Instruction(0xe8, -2)
                )
        );
        assertArrayEquals(
                x86Instruction(0xe8, 0x0100_0000),
                Rar5OutputPipeline.applyFilter(
                        Rar5OutputPipeline.FILTER_X86_E8,
                        0,
                        0L,
                        x86Instruction(0xe8, 0x0100_0000)
                )
        );
    }

    /// Verifies ARM branch immediates are adjusted while ordinary and trailing bytes remain unchanged.
    @Test
    void appliesArmFilter() throws IOException {
        byte[] source = new byte[10];
        ByteArrayAccess.writeIntLittleEndian(source, 0, 0xeb00_0005);
        ByteArrayAccess.writeIntLittleEndian(source, 4, 0xea00_0007);
        source[8] = 9;
        source[9] = 10;
        byte[] original = source.clone();

        byte[] result = Rar5OutputPipeline.applyFilter(Rar5OutputPipeline.FILTER_ARM, 0, 4L, source);

        assertEquals(0xeb00_0004, ByteArrayAccess.readIntLittleEndian(result, 0));
        assertEquals(0xea00_0007, ByteArrayAccess.readIntLittleEndian(result, 4));
        assertEquals(9, result[8]);
        assertEquals(10, result[9]);
        assertArrayEquals(original, source);
    }

    /// Verifies a future filter buffers its raw block and emits any preceding bytes first.
    @Test
    void schedulesAndAppliesFutureFilter() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Rar5OutputPipeline pipeline = new Rar5OutputPipeline(output, 100L, 5L);
        assertEquals(100L, pipeline.rawPosition());
        pipeline.registerFilter(2L, 3L, Rar5OutputPipeline.FILTER_DELTA, 1);

        pipeline.accept(10);
        pipeline.accept(20);
        pipeline.accept(1);
        pipeline.accept(2);
        assertEquals(104L, pipeline.rawPosition());
        assertArrayEquals(new byte[0], output.toByteArray());
        pipeline.accept(3);
        pipeline.finish();

        assertEquals(105L, pipeline.rawPosition());
        assertArrayEquals(new byte[]{10, 20, -1, -3, -6}, output.toByteArray());
    }

    /// Verifies unfiltered output is bulk-buffered and a zero-size filter consumes no bytes.
    @Test
    void buffersUnfilteredOutput() throws IOException {
        int size = 64 * 1024 + 1;
        byte[] expected = new byte[size];
        ByteArrayOutputStream output = new ByteArrayOutputStream(size);
        Rar5OutputPipeline pipeline = new Rar5OutputPipeline(output, 0L, size);
        pipeline.registerFilter(0L, 0L, Rar5OutputPipeline.FILTER_ARM, 0);
        for (int index = 0; index < size; index++) {
            int value = index * 17;
            expected[index] = (byte) value;
            pipeline.accept(value);
        }
        assertEquals(64 * 1024, output.size());
        pipeline.finish();
        assertArrayEquals(expected, output.toByteArray());
    }

    /// Verifies filter field bounds, ordering, entry bounds, and position overflow checks.
    @Test
    void validatesFilterRegistration() throws IOException {
        Rar5OutputPipeline pipeline = new Rar5OutputPipeline(new ByteArrayOutputStream(), 0L, 10L);
        assertThrows(
                IOException.class,
                () -> pipeline.registerFilter(-1L, 1L, Rar5OutputPipeline.FILTER_ARM, 0)
        );
        assertThrows(
                IOException.class,
                () -> pipeline.registerFilter(0x1_0000_0000L, 1L, Rar5OutputPipeline.FILTER_ARM, 0)
        );
        assertThrows(
                IOException.class,
                () -> pipeline.registerFilter(0L, -1L, Rar5OutputPipeline.FILTER_ARM, 0)
        );
        assertThrows(
                IOException.class,
                () -> pipeline.registerFilter(
                        0L,
                        Rar5OutputPipeline.MAX_FILTER_SIZE + 1L,
                        Rar5OutputPipeline.FILTER_ARM,
                        0
                )
        );
        assertThrows(IOException.class, () -> pipeline.registerFilter(0L, 1L, -1, 0));
        assertThrows(IOException.class, () -> pipeline.registerFilter(0L, 1L, 4, 0));
        assertThrows(
                IOException.class,
                () -> pipeline.registerFilter(0L, 1L, Rar5OutputPipeline.FILTER_DELTA, 0)
        );
        assertThrows(
                IOException.class,
                () -> pipeline.registerFilter(0L, 1L, Rar5OutputPipeline.FILTER_DELTA, 33)
        );
        assertThrows(
                IOException.class,
                () -> pipeline.registerFilter(9L, 2L, Rar5OutputPipeline.FILTER_ARM, 0)
        );

        pipeline.registerFilter(2L, 3L, Rar5OutputPipeline.FILTER_ARM, 0);
        assertThrows(
                IOException.class,
                () -> pipeline.registerFilter(4L, 1L, Rar5OutputPipeline.FILTER_ARM, 0)
        );

        Rar5OutputPipeline startOverflow =
                new Rar5OutputPipeline(new ByteArrayOutputStream(), Long.MAX_VALUE, 0L);
        assertThrows(
                IOException.class,
                () -> startOverflow.registerFilter(1L, 0L, Rar5OutputPipeline.FILTER_ARM, 0)
        );
        Rar5OutputPipeline endOverflow =
                new Rar5OutputPipeline(new ByteArrayOutputStream(), Long.MAX_VALUE, 1L);
        assertThrows(
                IOException.class,
                () -> endOverflow.registerFilter(0L, 0L, Rar5OutputPipeline.FILTER_ARM, 0)
        );
    }

    /// Verifies the per-entry filter-count bound includes zero-size descriptors.
    @Test
    void limitsFilterCount() throws IOException {
        Rar5OutputPipeline pipeline = new Rar5OutputPipeline(new ByteArrayOutputStream(), 0L, 0L);
        for (int index = 0; index < 8192; index++) {
            pipeline.registerFilter(0L, 0L, Rar5OutputPipeline.FILTER_ARM, 0);
        }
        assertThrows(
                IOException.class,
                () -> pipeline.registerFilter(0L, 0L, Rar5OutputPipeline.FILTER_ARM, 0)
        );
        pipeline.finish();
    }

    /// Verifies constructor, filter application, accepted-size, and final-size validation.
    @Test
    void validatesPipelineBoundaries() throws IOException {
        assertThrows(NullPointerException.class, () -> new Rar5OutputPipeline(null, 0L, 0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Rar5OutputPipeline(new ByteArrayOutputStream(), -1L, 0L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new Rar5OutputPipeline(new ByteArrayOutputStream(), 0L, -1L)
        );
        assertThrows(
                NullPointerException.class,
                () -> Rar5OutputPipeline.applyFilter(Rar5OutputPipeline.FILTER_ARM, 0, 0L, null)
        );
        assertThrows(
                IOException.class,
                () -> Rar5OutputPipeline.applyFilter(Rar5OutputPipeline.FILTER_DELTA, 0, 0L, new byte[0])
        );
        assertThrows(
                IOException.class,
                () -> Rar5OutputPipeline.applyFilter(Rar5OutputPipeline.FILTER_DELTA, 33, 0L, new byte[0])
        );
        assertThrows(
                IOException.class,
                () -> Rar5OutputPipeline.applyFilter(4, 0, 0L, new byte[0])
        );

        Rar5OutputPipeline empty = new Rar5OutputPipeline(new ByteArrayOutputStream(), 0L, 0L);
        assertThrows(IOException.class, () -> empty.accept(1));
        empty.finish();

        Rar5OutputPipeline shortOutput = new Rar5OutputPipeline(new ByteArrayOutputStream(), 0L, 2L);
        shortOutput.accept(1);
        assertThrows(IOException.class, shortOutput::finish);
    }

    /// Creates one five-byte x86 relative-address instruction.
    private static byte[] x86Instruction(int opcode, int address) {
        byte[] instruction = new byte[5];
        instruction[0] = (byte) opcode;
        ByteArrayAccess.writeIntLittleEndian(instruction, 1, address);
        return instruction;
    }
}
