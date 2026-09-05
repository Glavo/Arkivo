// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies CPIO-specific metadata charset detection contexts.
@NotNullByDefault
public final class CPIOMetadataCharsetDetectorTest {
    /// Verifies archive reads supply parsed CPIO header context to the detector.
    @Test
    public void archiveReadSuppliesHeaderContext() throws IOException {
        byte[] content = {4, 5, 6};
        byte[] archive = writeArchive(content);
        AtomicBoolean observed = new AtomicBoolean();
        CPIOMetadataCharsetDetector detector = context -> {
            assertEquals(CPIOMetadataCharsetDetector.MetadataKind.ENTRY_NAME, context.metadataKind());
            assertEquals(CPIODialect.NEW_ASCII_CRC, context.dialect());
            assertNull(context.binaryByteOrder());
            assertEquals(1L, context.inode());
            assertEquals(0100644, context.mode());
            assertEquals(content.length, context.entrySize());
            assertTrue(context.bytes().isReadOnly());
            byte[] name = new byte[context.bytes().remaining()];
            context.bytes().get(name);
            assertArrayEquals("payload.bin".getBytes(StandardCharsets.UTF_8), name);
            observed.set(true);
            return StandardCharsets.UTF_8;
        };

        try (CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(
                new ByteArrayInputStream(archive),
                CPIOArchiveOptions.READ_DEFAULTS.withMetadataCharsetDetector(detector)
        )) {
            assertTrue(reader.next());
        }

        assertTrue(observed.get());
    }

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

    /// Writes one CRC-protected regular file for detector integration checks.
    private static byte[] writeArchive(byte @Unmodifiable [] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(
                output,
                CPIOArchiveOptions.CREATE_DEFAULTS.withDialect(CPIODialect.NEW_ASCII_CRC)
        )) {
            try (OutputStream body = writer.beginFile("payload.bin").openOutputStream()) {
                body.write(content);
            }
        }
        return output.toByteArray();
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
