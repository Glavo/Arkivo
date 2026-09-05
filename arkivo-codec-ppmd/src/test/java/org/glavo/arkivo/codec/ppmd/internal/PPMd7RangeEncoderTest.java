// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies PPMd7 range-encoder interval validation and terminal state transitions.
@NotNullByDefault
final class PPMd7RangeEncoderTest {
    /// Rejects every invalid general and binary arithmetic interval before accepting valid symbols.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void validatesArithmeticIntervals() throws IOException {
        assertThrows(NullPointerException.class, () -> new PPMd7RangeEncoder(null));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PPMd7RangeEncoder encoder = new PPMd7RangeEncoder(Channels.newChannel(output));
        assertFailureMessage(
                () -> encoder.encode(0, 1, 0),
                "Invalid PPMd arithmetic interval"
        );
        assertFailureMessage(
                () -> encoder.encode(-1, 1, 2),
                "Invalid PPMd arithmetic interval"
        );
        assertFailureMessage(
                () -> encoder.encode(1, 1, 2),
                "Invalid PPMd arithmetic interval"
        );
        assertFailureMessage(
                () -> encoder.encode(0, 3, 2),
                "Invalid PPMd arithmetic interval"
        );
        assertFailureMessage(
                () -> encoder.encodeBit(false, 1, 1 << 13),
                "Invalid PPMd7 binary arithmetic interval"
        );
        assertFailureMessage(
                () -> encoder.encodeBit(false, 0, 1 << 14),
                "Invalid PPMd7 binary arithmetic interval"
        );
        assertFailureMessage(
                () -> encoder.encodeBit(false, 1 << 14, 1 << 14),
                "Invalid PPMd7 binary arithmetic interval"
        );

        encodeSequence(encoder);
        encoder.finish();
        assertTrue(output.size() >= 5);
    }

    /// Verifies finish is idempotent, terminal encodes fail, and reset restores deterministic output.
    @Test
    void finishesAndResetsDeterministically() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PPMd7RangeEncoder encoder = new PPMd7RangeEncoder(Channels.newChannel(output));

        encoder.flushOutput();
        assertEquals(0, output.size());
        encodeSequence(encoder);
        encoder.finish();
        byte[] first = output.toByteArray();

        encoder.finish();
        assertEquals(first.length, output.size());
        assertFailureMessage(
                () -> encoder.encode(0, 1, 2),
                "PPMd7 range encoder is already finished"
        );
        assertFailureMessage(
                () -> encoder.encodeBit(false, 1 << 13, 1 << 14),
                "PPMd7 range encoder is already finished"
        );

        encoder.reset();
        encodeSequence(encoder);
        encoder.finish();
        assertArrayEquals(first, Arrays.copyOfRange(output.toByteArray(), first.length, output.size()));
    }

    /// Encodes a deterministic mix of general, zero-bit, and one-bit intervals.
    private static void encodeSequence(PPMd7RangeEncoder encoder) throws IOException {
        encoder.encode(0, 1, 3);
        encoder.encode(2, 3, 3);
        encoder.encodeBit(false, 5_000, 1 << 14);
        encoder.encodeBit(true, 9_000, 1 << 14);
    }

    /// Requires an operation to report one exact format-specific diagnostic.
    private static void assertFailureMessage(Executable operation, String expectedMessage) {
        IOException exception = assertThrows(IOException.class, operation);
        assertEquals(expectedMessage, exception.getMessage());
    }
}
