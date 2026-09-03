// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies CPIO-specific metadata charset detection contexts.
@NotNullByDefault
public final class CPIOMetadataCharsetDetectorTest {
    /// Verifies basic detector calls receive unknown CPIO metadata in an independent read-only view.
    @Test
    public void basicInvocationSuppliesUnknownContext() throws Exception {
        ByteBuffer source = ByteBuffer.wrap(new byte[]{0, 1, 2});
        source.position(1);
        source.mark();
        CPIOMetadataCharsetDetector detector = context -> {
            assertTrue(context.bytes().isReadOnly());
            assertEquals(CPIOMetadataCharsetDetector.MetadataKind.UNKNOWN, context.metadataKind());
            assertNull(context.dialect());
            assertNull(context.binaryByteOrder());
            assertEquals(CPIOMetadataCharsetDetector.UNKNOWN_INODE, context.inode());
            assertEquals(CPIOMetadataCharsetDetector.UNKNOWN_MODE, context.mode());
            assertEquals(CPIOMetadataCharsetDetector.UNKNOWN_ENTRY_SIZE, context.entrySize());
            context.bytes().position(context.bytes().limit());
            return null;
        };

        assertNull(detector.detect(source));
        assertEquals(1, source.position());
        source.reset();
        assertEquals(1, source.position());
    }

    /// Verifies old-binary contexts require a byte order and preserve all surrounding header fields.
    @Test
    public void contextValidatesDialectAndByteOrder() {
        ByteBuffer source = ByteBuffer.wrap(new byte[]{3, 4});
        CPIOMetadataCharsetDetector.Context context = new CPIOMetadataCharsetDetector.Context(
                source,
                CPIOMetadataCharsetDetector.MetadataKind.ENTRY_NAME,
                CPIODialect.OLD_BINARY,
                CPIOBinaryByteOrder.LITTLE_ENDIAN,
                7L,
                0100644,
                9L
        );

        assertTrue(context.bytes().isReadOnly());
        context.bytes().position(1);
        assertEquals(0, source.position());
        assertEquals(CPIODialect.OLD_BINARY, context.dialect());
        assertEquals(CPIOBinaryByteOrder.LITTLE_ENDIAN, context.binaryByteOrder());
        assertEquals(7L, context.inode());
        assertEquals(0100644, context.mode());
        assertEquals(9L, context.entrySize());

        assertThrows(
                IllegalArgumentException.class,
                () -> context(CPIODialect.OLD_BINARY, null, 0L, 0, 0L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> context(CPIODialect.NEW_ASCII, CPIOBinaryByteOrder.BIG_ENDIAN, 0L, 0, 0L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> context(null, CPIOBinaryByteOrder.BIG_ENDIAN, 0L, 0, 0L)
        );
    }

    /// Verifies numeric fields reject values below their unknown sentinels.
    @Test
    public void contextValidatesNumericSentinels() {
        assertThrows(IllegalArgumentException.class, () -> context(null, null, -2L, -1, -1L));
        assertThrows(IllegalArgumentException.class, () -> context(null, null, -1L, -2, -1L));
        assertThrows(IllegalArgumentException.class, () -> context(null, null, -1L, -1, -2L));
    }

    /// Creates a CPIO context with the supplied structural fields.
    private static CPIOMetadataCharsetDetector.Context context(
            @Nullable CPIODialect dialect,
            @Nullable CPIOBinaryByteOrder binaryByteOrder,
            long inode,
            int mode,
            long entrySize
    ) {
        return new CPIOMetadataCharsetDetector.Context(
                ByteBuffer.allocate(0),
                CPIOMetadataCharsetDetector.MetadataKind.ENTRY_NAME,
                dialect,
                binaryByteOrder,
                inode,
                mode,
                entrySize
        );
    }
}
