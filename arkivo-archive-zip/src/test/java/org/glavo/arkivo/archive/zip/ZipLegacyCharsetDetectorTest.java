// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the ZIP-specific legacy metadata charset detector contract.
@NotNullByDefault
public final class ZipLegacyCharsetDetectorTest {
    /// Verifies that basic invocations supply an unknown ZIP context with a read-only byte view.
    @Test
    public void basicInvocation() throws Exception {
        ZipLegacyCharsetDetector detector = context -> {
            assertTrue(context.bytes().isReadOnly());
            assertEquals(2, context.bytes().remaining());
            assertEquals(ZipLegacyCharsetDetector.MetadataKind.UNKNOWN, context.metadataKind());
            assertEquals(ZipLegacyCharsetDetector.HeaderSource.UNKNOWN, context.headerSource());
            return null;
        };

        assertNull(detector.detect(ByteBuffer.wrap(new byte[]{1, 2}).asReadOnlyBuffer()));
    }

    /// Verifies that a central-directory context exposes creator and version fields without changing its buffers.
    @Test
    public void centralDirectoryContext() {
        ZipLegacyCharsetDetector.Context context = new ZipLegacyCharsetDetector.Context(
                ByteBuffer.wrap(new byte[]{1, 2}),
                ZipLegacyCharsetDetector.MetadataKind.ENTRY_NAME,
                ZipLegacyCharsetDetector.HeaderSource.CENTRAL_DIRECTORY,
                0x0002,
                20,
                3 << Byte.SIZE | 63,
                ByteBuffer.wrap(new byte[]{4, 5})
        );

        assertTrue(context.bytes().isReadOnly());
        assertTrue(context.extraData().isReadOnly());
        assertEquals(3, context.creatorSystem());
        assertEquals(63, context.creatorVersion());
    }
}
