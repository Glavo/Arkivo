// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies ordering, buffering, chaining, reset, and size enforcement in the RAR3 filter pipeline.
@NotNullByDefault
final class Rar3OutputPipelineTest {
    /// Verifies an unfiltered stream is emitted immediately and repeated completion checks are stable.
    @Test
    void writesUnfilteredOutput() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Rar3OutputPipeline pipeline = new Rar3OutputPipeline(output, 3);

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
        Rar3OutputPipeline pipeline = new Rar3OutputPipeline(output, 5);
        pipeline.schedule(descriptor(false, 2, 3));

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
        Rar3OutputPipeline pipeline = new Rar3OutputPipeline(output, 3);
        pipeline.schedule(descriptor(false, 0, 3));
        pipeline.schedule(descriptor(false, 0, 3));
        pipeline.accept(1);
        pipeline.accept(2);
        pipeline.accept(3);
        pipeline.finish();
        assertArrayEquals(new byte[]{1, 2, 3}, output.toByteArray());

        Rar3OutputPipeline mismatched = new Rar3OutputPipeline(new ByteArrayOutputStream(), 3);
        mismatched.schedule(descriptor(false, 0, 3));
        mismatched.schedule(descriptor(false, 0, 2));
        mismatched.accept(1);
        mismatched.accept(2);
        assertThrows(IOException.class, () -> mismatched.accept(3));
    }

    /// Verifies reset descriptors discard old invocations and decreasing raw positions are rejected.
    @Test
    void resetsAndOrdersPendingFilters() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Rar3OutputPipeline pipeline = new Rar3OutputPipeline(output, 1);
        pipeline.schedule(descriptor(false, 10, 1));
        assertThrows(IOException.class, () -> pipeline.schedule(descriptor(false, 9, 1)));
        pipeline.schedule(descriptor(true, 0, 1));
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
        Rar3OutputPipeline pipeline = new Rar3OutputPipeline(output, size);
        pipeline.schedule(descriptor(false, 0, size));
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
        assertThrows(NullPointerException.class, () -> new Rar3OutputPipeline(null, 0));

        Rar3OutputPipeline incompleteFilter = new Rar3OutputPipeline(new ByteArrayOutputStream(), 2);
        incompleteFilter.schedule(descriptor(false, 0, 2));
        incompleteFilter.accept(1);
        assertThrows(IOException.class, incompleteFilter::finish);

        Rar3OutputPipeline shortOutput = new Rar3OutputPipeline(new ByteArrayOutputStream(), 2);
        shortOutput.accept(1);
        assertThrows(IOException.class, shortOutput::finish);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Rar3OutputPipeline overflowing = new Rar3OutputPipeline(output, 1);
        overflowing.accept(1);
        assertThrows(IOException.class, () -> overflowing.accept(2));
        assertArrayEquals(new byte[]{1}, output.toByteArray());

        Rar3OutputPipeline empty = new Rar3OutputPipeline(new ByteArrayOutputStream(), 0);
        assertTrue(empty.isComplete());
        empty.finish();
    }

    /// Creates an identity-filter descriptor with the supplied scheduling fields.
    private static Rar3FilterManager.Descriptor descriptor(
            boolean resetQueuedFilters,
            int relativeOffset,
            int blockLength
    ) throws IOException {
        return new Rar3FilterManager.Descriptor(
                resetQueuedFilters,
                relativeOffset,
                blockLength,
                identityProgram(),
                new int[7],
                0,
                new byte[0]
        );
    }

    /// Compiles the minimal checksummed VM program whose sole instruction is `RET`.
    private static Rar3FilterProgram identityProgram() throws IOException {
        return new Rar3FilterProgram(new byte[]{0x5c, 0x5c});
    }
}
