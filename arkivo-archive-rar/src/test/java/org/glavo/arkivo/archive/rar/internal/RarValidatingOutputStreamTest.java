// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies declared-size enforcement and CRC accounting for decoded RAR output.
@NotNullByDefault
final class RarValidatingOutputStreamTest {
    /// Verifies single-byte, ranged, and empty writes contribute the expected bytes and CRC32 value.
    @Test
    void validatesWrittenBytesAndCrc32() throws IOException {
        byte[] source = "xBCD".getBytes(StandardCharsets.US_ASCII);
        byte[] expectedBytes = "ABCD".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        RarValidatingOutputStream output = new RarValidatingOutputStream("RAR", target, expectedBytes.length);

        output.write('A');
        output.write(source, 1, 3);
        output.write(source, 0, 0);

        CRC32 expectedCrc32 = new CRC32();
        expectedCrc32.update(expectedBytes);
        assertArrayEquals(expectedBytes, target.toByteArray());
        assertEquals(expectedCrc32.getValue(), output.validatedCrc32());
        assertEquals(expectedCrc32.getValue(), output.validatedCrc32());
    }

    /// Verifies oversized writes fail before forwarding bytes or changing the accumulated CRC state.
    @Test
    void rejectsOutputBeyondDeclaredSizeAtomically() throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        RarValidatingOutputStream output = new RarValidatingOutputStream("RAR5", target, 2L);

        output.write('A');
        IOException bulkFailure = assertThrows(
                IOException.class,
                () -> output.write(new byte[]{'B', 'C'}, 0, 2)
        );
        assertTrue(bulkFailure.getMessage().contains("RAR5 decompressor exceeded"));
        assertArrayEquals(new byte[]{'A'}, target.toByteArray());

        output.write('B');
        long completedCrc32 = output.validatedCrc32();
        IOException singleFailure = assertThrows(IOException.class, () -> output.write('C'));
        assertTrue(singleFailure.getMessage().contains("RAR5 decompressor exceeded"));
        assertArrayEquals(new byte[]{'A', 'B'}, target.toByteArray());
        assertEquals(completedCrc32, output.validatedCrc32());
    }

    /// Verifies incomplete output reports both the actual and declared byte counts.
    @Test
    void rejectsOutputShorterThanDeclaredSize() throws IOException {
        RarValidatingOutputStream output =
                new RarValidatingOutputStream("RAR4", new ByteArrayOutputStream(), 3L);
        output.write(new byte[]{1, 2});

        IOException exception = assertThrows(IOException.class, output::validatedCrc32);
        assertEquals("RAR4 decompressor produced 2 bytes; expected 3", exception.getMessage());
    }

    /// Verifies construction, source-range, and zero-sized-output boundaries.
    @Test
    void validatesArgumentsAndEmptyOutput() throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        assertThrows(NullPointerException.class, () -> new RarValidatingOutputStream(null, target, 0L));
        assertThrows(NullPointerException.class, () -> new RarValidatingOutputStream("RAR", null, 0L));
        assertThrows(IllegalArgumentException.class, () -> new RarValidatingOutputStream("RAR", target, -1L));

        RarValidatingOutputStream output = new RarValidatingOutputStream("RAR", target, 0L);
        output.write(new byte[0]);
        assertEquals(0L, output.validatedCrc32());
        assertThrows(NullPointerException.class, () -> output.write(null, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> output.write(new byte[1], -1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> output.write(new byte[1], 1, 1));
        assertThrows(IOException.class, () -> output.write(0));
    }
}
