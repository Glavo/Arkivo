// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.glavo.arkivo.codec.DecompressionMemoryLimitException;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies ordering, buffering, chaining, reset, and size enforcement in the RAR3 filter pipeline.
@NotNullByDefault
final class Rar3OutputPipelineTest {
    /// Workspace budget isolated to this test invocation.
    private final RarDecoderMemoryBudget memory = new RarDecoderMemoryBudget(-1);

    /// Releases the removed invocation and its input when VM setup, chaining, or output fails.
    @Test
    void releasesFilterWorkspaceAfterFailures() throws IOException {
        for (int mode = 0; mode < 4; mode++) {
            RarDecoderMemoryBudget budget = new RarDecoderMemoryBudget(mode == 0 ? 327_774 : 327_778);
            int failureMode = mode;
            OutputStream sink = mode == 2 ? new OutputStream() {
                /// Rejects the transformed output after both invocations have completed.
                @Override
                public void write(int value) throws IOException {
                    throw new IOException("test output failure");
                }
            } : new ByteArrayOutputStream();
            Rar3FilterProgram program = new Rar3FilterProgram(new byte[]{0x5c, 0x5c}, budget);
            Rar3OutputPipeline pipeline = new Rar3OutputPipeline(sink, 3, budget);
            try {
                for (int index = 0; index < 2; index++) {
                    budget.reserve(28);
                    pipeline.schedule(new Rar3FilterManager.Descriptor(0, mode == 1 && index == 1 ? 2 : 3,
                            program, new int[7], 0, new byte[0]));
                }
                pipeline.accept(1);
                pipeline.accept(2);
                if (mode == 3) {
                    pipeline.accept(3);
                    pipeline.finish();
                    assertArrayEquals(new byte[]{1, 2, 3}, ((ByteArrayOutputStream) sink).toByteArray());
                } else {
                    IOException failure = assertThrows(IOException.class, () -> pipeline.accept(3));
                    if (failureMode == 0) assertEquals(327_775,
                            ((DecompressionMemoryLimitException) failure).requiredMemorySize());
                }
                assertEquals(mode == 0 ? 65_564 : 65_536, budget.used());
            } finally {
                pipeline.close();
                pipeline.close();
                program.close();
            }
            assertEquals(0, budget.used());
        }
    }

    /// Reserves the entire replacement buffer while the previous raw buffer is still retained.
    @Test
    void enforcesRawBufferGrowthPeak() throws IOException {
        for (long maximum : new long[]{196_635, 196_636}) {
            RarDecoderMemoryBudget budget = new RarDecoderMemoryBudget(maximum);
            try (Rar3FilterProgram program = new Rar3FilterProgram(new byte[]{0x5c, 0x5c}, budget);
                 Rar3OutputPipeline pipeline = new Rar3OutputPipeline(OutputStream.nullOutputStream(), 70_000, budget)) {
                budget.reserve(28);
                pipeline.schedule(new Rar3FilterManager.Descriptor(0, 70_000, program, new int[7], 0, new byte[0]));
                for (int index = 0; index < 65_536; index++) pipeline.accept(0);
                if (maximum == 196_635) {
                    DecompressionMemoryLimitException failure = assertThrows(DecompressionMemoryLimitException.class,
                            () -> pipeline.accept(0));
                    assertEquals(196_636, failure.requiredMemorySize());
                    assertEquals(65_564, budget.used());
                } else {
                    pipeline.accept(0);
                    assertEquals(131_100, budget.used());
                }
            }
            assertEquals(0, budget.used());
        }
    }

    /// Verifies an unfiltered stream is emitted immediately and repeated completion checks are stable.
    @Test
    void writesUnfilteredOutput() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Rar3OutputPipeline pipeline = new Rar3OutputPipeline(output, 3, memory);

        assertFalse(pipeline.isComplete());
        pipeline.accept(1);
        pipeline.accept(2);
        assertArrayEquals(new byte[]{1, 2}, output.toByteArray());
        assertFalse(pipeline.isComplete());
        pipeline.accept(3);
        assertTrue(pipeline.isComplete());
        pipeline.finish();
        pipeline.finish();
        assertArrayEquals(new byte[]{1, 2, 3}, output.toByteArray());
    }

    /// Verifies a future filter preserves prefix order and waits for its complete raw block.
    @Test
    void buffersFutureFilterBlock() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Rar3OutputPipeline pipeline = new Rar3OutputPipeline(output, 5, memory);
        pipeline.schedule(descriptor(2, 3));

        pipeline.accept(10);
        pipeline.accept(20);
        assertArrayEquals(new byte[]{10, 20}, output.toByteArray());
        pipeline.accept(30);
        pipeline.accept(40);
        assertArrayEquals(new byte[]{10, 20}, output.toByteArray());
        pipeline.accept(50);

        assertArrayEquals(new byte[]{10, 20, 30, 40, 50}, output.toByteArray());
        assertTrue(pipeline.isComplete());
    }

    /// Verifies filters at one raw position are chained and their intermediate lengths must agree.
    @Test
    void chainsFiltersAtOneRawPosition() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Rar3OutputPipeline pipeline = new Rar3OutputPipeline(output, 3, memory);
        pipeline.schedule(descriptor(0, 3));
        pipeline.schedule(descriptor(0, 3));
        pipeline.accept(1);
        pipeline.accept(2);
        pipeline.accept(3);
        pipeline.finish();
        assertArrayEquals(new byte[]{1, 2, 3}, output.toByteArray());

        Rar3OutputPipeline mismatched = new Rar3OutputPipeline(new ByteArrayOutputStream(), 3, memory);
        mismatched.schedule(descriptor(0, 3));
        mismatched.schedule(descriptor(0, 2));
        mismatched.accept(1);
        mismatched.accept(2);
        assertThrows(IOException.class, () -> mismatched.accept(3));
    }

    /// Verifies clearing the queue discards old invocations and decreasing raw positions are rejected.
    @Test
    void resetsAndOrdersPendingFilters() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Rar3OutputPipeline pipeline = new Rar3OutputPipeline(output, 1, memory);
        pipeline.schedule(descriptor(10, 1));
        assertThrows(IOException.class, () -> pipeline.schedule(descriptor(9, 1)));
        pipeline.clearPending();
        pipeline.schedule(descriptor(0, 1));
        pipeline.accept(42);
        pipeline.finish();

        assertArrayEquals(new byte[]{42}, output.toByteArray());
        assertTrue(pipeline.isComplete());
    }

    /// Verifies the staging buffer grows beyond its initial capacity without losing pending bytes.
    @Test
    void growsRawStagingBuffer() throws IOException {
        int size = 64 * 1024 + 1;
        byte[] expected = new byte[size];
        ByteArrayOutputStream output = new ByteArrayOutputStream(size);
        Rar3OutputPipeline pipeline = new Rar3OutputPipeline(output, size, memory);
        pipeline.schedule(descriptor(0, size));
        for (int index = 0; index < size; index++) {
            int value = index * 31;
            expected[index] = (byte) value;
            pipeline.accept(value);
        }
        pipeline.finish();

        assertArrayEquals(expected, output.toByteArray());
        assertTrue(pipeline.isComplete());
    }

    /// Verifies premature filter endings and final-size underflow and overflow are rejected.
    @Test
    void validatesPendingAndFinalSizes() throws IOException {
        assertThrows(NullPointerException.class, () -> new Rar3OutputPipeline(null, 0, memory));

        Rar3OutputPipeline incompleteFilter = new Rar3OutputPipeline(new ByteArrayOutputStream(), 2, memory);
        incompleteFilter.schedule(descriptor(0, 2));
        incompleteFilter.accept(1);
        assertThrows(IOException.class, incompleteFilter::finish);

        Rar3OutputPipeline shortOutput = new Rar3OutputPipeline(new ByteArrayOutputStream(), 2, memory);
        shortOutput.accept(1);
        assertThrows(IOException.class, shortOutput::finish);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Rar3OutputPipeline overflowing = new Rar3OutputPipeline(output, 1, memory);
        overflowing.accept(1);
        assertThrows(IOException.class, () -> overflowing.accept(2));
        assertArrayEquals(new byte[]{1}, output.toByteArray());

        Rar3OutputPipeline empty = new Rar3OutputPipeline(new ByteArrayOutputStream(), 0, memory);
        assertTrue(empty.isComplete());
        empty.finish();
    }

    /// Creates an identity-filter descriptor with the supplied scheduling fields.
    private Rar3FilterManager.Descriptor descriptor(
            int relativeOffset,
            int blockLength
    ) throws IOException {
        memory.reserve(7L * Integer.BYTES);
        return new Rar3FilterManager.Descriptor(
                relativeOffset,
                blockLength,
                identityProgram(),
                new int[7],
                0,
                new byte[0]
        );
    }

    /// Compiles the minimal checksummed VM program whose sole instruction is `RET`.
    private Rar3FilterProgram identityProgram() throws IOException {
        return new Rar3FilterProgram(new byte[]{0x5c, 0x5c}, memory);
    }
}
