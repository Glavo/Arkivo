// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the ZIP-specific legacy metadata charset detector contract.
@NotNullByDefault
public final class ZipLegacyCharsetDetectorTest {
    /// Verifies that basic invocations supply an unknown ZIP context with a read-only byte view.
    @Test
    public void basicInvocation() throws Exception {
        ByteBuffer source = ByteBuffer.wrap(new byte[]{0, 1, 2});
        source.position(1);
        source.mark();
        ZipLegacyCharsetDetector detector = context -> {
            assertTrue(context.bytes().isReadOnly());
            assertEquals(2, context.bytes().remaining());
            assertEquals(ZipLegacyCharsetDetector.MetadataKind.UNKNOWN, context.metadataKind());
            assertEquals(ZipLegacyCharsetDetector.HeaderSource.UNKNOWN, context.headerSource());
            assertEquals(ZipLegacyCharsetDetector.UNKNOWN_HEADER_VALUE, context.generalPurposeFlags());
            assertEquals(ZipLegacyCharsetDetector.UNKNOWN_HEADER_VALUE, context.versionNeededToExtract());
            assertEquals(ZipLegacyCharsetDetector.UNKNOWN_HEADER_VALUE, context.versionMadeBy());
            assertEquals(0, context.extraData().remaining());
            context.bytes().position(context.bytes().limit());
            return null;
        };

        assertNull(detector.detect(source));
        assertEquals(1, source.position());
        source.reset();
        assertEquals(1, source.position());
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

    /// Verifies unavailable creator metadata and validates every unsigned-short header field.
    @Test
    public void validatesHeaderValueRanges() {
        ZipLegacyCharsetDetector.Context unknown = context(-1, -1, -1);
        assertEquals(ZipLegacyCharsetDetector.UNKNOWN_HEADER_VALUE, unknown.creatorSystem());
        assertEquals(ZipLegacyCharsetDetector.UNKNOWN_HEADER_VALUE, unknown.creatorVersion());

        ZipLegacyCharsetDetector.Context maximum = context(0xffff, 0xffff, 0xffff);
        assertEquals(0xff, maximum.creatorSystem());
        assertEquals(0xff, maximum.creatorVersion());

        assertInvalidHeaderValues(-2, -1, -1);
        assertInvalidHeaderValues(0x1_0000, -1, -1);
        assertInvalidHeaderValues(-1, -2, -1);
        assertInvalidHeaderValues(-1, 0x1_0000, -1);
        assertInvalidHeaderValues(-1, -1, -2);
        assertInvalidHeaderValues(-1, -1, 0x1_0000);
    }

    /// Requires construction to reject one invalid ZIP header-value combination.
    private static void assertInvalidHeaderValues(
            int generalPurposeFlags,
            int versionNeededToExtract,
            int versionMadeBy
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> context(generalPurposeFlags, versionNeededToExtract, versionMadeBy)
        );
    }

    /// Creates a minimal ZIP legacy metadata context with the requested integer fields.
    private static ZipLegacyCharsetDetector.Context context(
            int generalPurposeFlags,
            int versionNeededToExtract,
            int versionMadeBy
    ) {
        return new ZipLegacyCharsetDetector.Context(
                ByteBuffer.allocate(0),
                ZipLegacyCharsetDetector.MetadataKind.ENTRY_NAME,
                ZipLegacyCharsetDetector.HeaderSource.CENTRAL_DIRECTORY,
                generalPurposeFlags,
                versionNeededToExtract,
                versionMadeBy,
                ByteBuffer.allocate(0)
        );
    }
}
