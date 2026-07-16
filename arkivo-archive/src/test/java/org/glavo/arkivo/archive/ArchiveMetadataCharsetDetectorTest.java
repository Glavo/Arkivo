// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the basic archive metadata charset detector contract.
@NotNullByDefault
public final class ArchiveMetadataCharsetDetectorTest {
    /// Verifies that array invocations delegate through an independent read-only buffer.
    @Test
    public void arrayDelegatesToReadOnlyBuffer() throws Exception {
        ArchiveMetadataCharsetDetector detector = bytes -> {
            assertTrue(bytes.isReadOnly());
            assertEquals(2, bytes.remaining());
            return StandardCharsets.UTF_8;
        };

        assertEquals(StandardCharsets.UTF_8, detector.detect(new byte[]{1, 2}));
    }

    /// Verifies that a fixed detector accepts both basic byte representations.
    @Test
    public void fixedDetector() throws Exception {
        ArchiveMetadataCharsetDetector detector =
                ArchiveMetadataCharsetDetector.fixed(StandardCharsets.UTF_16LE);

        assertEquals(StandardCharsets.UTF_16LE, detector.detect(new byte[]{1, 2}));
        assertEquals(StandardCharsets.UTF_16LE, detector.detect(ByteBuffer.allocate(0)));
    }
}
