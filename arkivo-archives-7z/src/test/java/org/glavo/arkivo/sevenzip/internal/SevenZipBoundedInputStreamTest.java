// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests bounded 7z entry input stream behavior.
@NotNullByDefault
public final class SevenZipBoundedInputStreamTest {
    /// Verifies that bounded reads stop exactly after the configured byte count.
    @Test
    public void readsBoundedBody() throws IOException {
        byte[] content = "hello bounded 7z".getBytes(StandardCharsets.UTF_8);

        try (SevenZipBoundedInputStream input =
                     new SevenZipBoundedInputStream(new ByteArrayInputStream(content), content.length)) {
            byte[] output = input.readAllBytes();

            assertArrayEquals(content, output);
            assertEquals(0, input.read(new byte[0]));
            assertEquals(-1, input.read());
        }
    }

    /// Verifies that early EOF while reading one byte is reported as archive truncation.
    @Test
    public void rejectsTruncatedSingleByteRead() {
        try (SevenZipBoundedInputStream input =
                     new SevenZipBoundedInputStream(new ByteArrayInputStream(new byte[0]), 1)) {
            EOFException exception = assertThrows(EOFException.class, input::read);

            assertEquals(true, exception.getMessage().contains("Unexpected end of 7z entry body"));
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    /// Verifies that early EOF while reading a byte array is reported as archive truncation.
    @Test
    public void rejectsTruncatedArrayRead() {
        byte[] content = "short".getBytes(StandardCharsets.UTF_8);

        try (SevenZipBoundedInputStream input =
                     new SevenZipBoundedInputStream(new ByteArrayInputStream(content), content.length + 1L)) {
            byte[] buffer = new byte[content.length + 1];
            assertEquals(content.length, input.read(buffer));

            EOFException exception = assertThrows(EOFException.class, () -> input.read(buffer));
            assertEquals(true, exception.getMessage().contains("Unexpected end of 7z entry body"));
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }
}
