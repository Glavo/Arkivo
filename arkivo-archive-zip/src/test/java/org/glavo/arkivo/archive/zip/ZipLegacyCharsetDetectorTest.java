// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests legacy ZIP metadata charset detector factories and the functional contract.
@NotNullByDefault
public final class ZipLegacyCharsetDetectorTest {
    /// Verifies that the standard detector selects the ZIP-standard CP437 charset.
    @Test
    public void standardDetector() throws Exception {
        assertEquals(
                Charset.forName("IBM437"),
                ZipLegacyCharsetDetector.standard().detect(ByteBuffer.wrap(new byte[]{(byte) 0x81}))
        );
    }

    /// Verifies that a fixed detector always returns its configured charset.
    @Test
    public void fixedDetector() throws Exception {
        assertEquals(
                StandardCharsets.UTF_8,
                ZipLegacyCharsetDetector.fixed(StandardCharsets.UTF_8).detect(ByteBuffer.allocate(0))
        );
    }

    /// Verifies that custom detectors can inspect a read-only byte view and report an unknown charset.
    @Test
    public void customDetector() throws Exception {
        ZipLegacyCharsetDetector detector = bytes -> {
            assertTrue(bytes.isReadOnly());
            assertEquals(2, bytes.remaining());
            return null;
        };

        assertNull(detector.detect(ByteBuffer.wrap(new byte[]{1, 2}).asReadOnlyBuffer()));
    }
}