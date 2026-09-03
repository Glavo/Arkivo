// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests validation and structured values exposed by the core codec API.
@NotNullByDefault
public final class CodecValueContractsTest {
    /// Verifies incremental results accept every state and reject either negative counter.
    @Test
    public void codecResultsValidateCountersAndStatus() {
        for (CodecResult.Status status : CodecResult.Status.values()) {
            assertEquals(status, new CodecResult(0L, Long.MAX_VALUE, status).status());
        }

        assertThrows(IllegalArgumentException.class, () -> new CodecResult(-1L, 0L, CodecResult.Status.ACTIVE));
        assertThrows(IllegalArgumentException.class, () -> new CodecResult(0L, -1L, CodecResult.Status.ACTIVE));
        assertThrows(NullPointerException.class, () -> new CodecResult(0L, 0L, null));
    }

    /// Verifies completed-transfer results accept boundary counters and reject either negative counter.
    @Test
    public void transferResultsValidateCounters() {
        CodecTransferResult result = new CodecTransferResult(0L, Long.MAX_VALUE);
        assertEquals(0L, result.inputBytes());
        assertEquals(Long.MAX_VALUE, result.outputBytes());

        assertThrows(IllegalArgumentException.class, () -> new CodecTransferResult(-1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> new CodecTransferResult(0L, -1L));
    }

    /// Verifies decoded-output failures expose their category, exact values, and first-disallowed-size shortcut.
    @Test
    public void outputLimitFailuresAreStructured() {
        DecompressionOutputLimitException first = new DecompressionOutputLimitException(10L);
        assertEquals(DecompressionLimitException.Kind.OUTPUT_SIZE, first.kind());
        assertEquals(10L, first.maximum());
        assertEquals(11L, first.actual());
        assertEquals(10L, first.maximumOutputSize());
        assertEquals(11L, first.actualOutputSize());
        assertTrue(first.getMessage().contains("11 bytes"));
        assertTrue(first.getMessage().contains("10 bytes"));

        DecompressionOutputLimitException measured = new DecompressionOutputLimitException(10L, 25L);
        assertEquals(25L, measured.actualOutputSize());

        assertThrows(IllegalArgumentException.class, () -> new DecompressionOutputLimitException(-1L));
        assertThrows(IllegalArgumentException.class, () -> new DecompressionOutputLimitException(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new DecompressionOutputLimitException(-1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> new DecompressionOutputLimitException(10L, 10L));
        assertThrows(IllegalArgumentException.class, () -> new DecompressionOutputLimitException(10L, 9L));
    }

    /// Verifies required-window failures expose the generic and specialized views of their values.
    @Test
    public void windowLimitFailuresAreStructured() {
        DecompressionWindowLimitException exception = new DecompressionWindowLimitException(32L, 64L);
        assertEquals(DecompressionLimitException.Kind.WINDOW_SIZE, exception.kind());
        assertEquals(32L, exception.maximum());
        assertEquals(64L, exception.actual());
        assertEquals(32L, exception.maximumWindowSize());
        assertEquals(64L, exception.requiredWindowSize());
        assertTrue(exception.getMessage().contains("64 bytes"));
        assertTrue(exception.getMessage().contains("32 bytes"));

        assertThrows(IllegalArgumentException.class, () -> new DecompressionWindowLimitException(-1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> new DecompressionWindowLimitException(64L, 64L));
    }

    /// Verifies required-memory failures expose the generic and specialized views of their values.
    @Test
    public void memoryLimitFailuresAreStructured() {
        DecompressionMemoryLimitException exception = new DecompressionMemoryLimitException(128L, 256L);
        assertEquals(DecompressionLimitException.Kind.MEMORY_SIZE, exception.kind());
        assertEquals(128L, exception.maximum());
        assertEquals(256L, exception.actual());
        assertEquals(128L, exception.maximumMemorySize());
        assertEquals(256L, exception.requiredMemorySize());
        assertTrue(exception.getMessage().contains("256 bytes"));
        assertTrue(exception.getMessage().contains("128 bytes"));

        assertThrows(IllegalArgumentException.class, () -> new DecompressionMemoryLimitException(-1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> new DecompressionMemoryLimitException(256L, 255L));
    }
}
