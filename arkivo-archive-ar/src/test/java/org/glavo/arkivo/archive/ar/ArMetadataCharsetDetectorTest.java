// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies AR-specific metadata charset detection contexts.
@NotNullByDefault
public final class ArMetadataCharsetDetectorTest {
    /// Verifies basic detector calls receive an unknown context and an independent read-only view.
    @Test
    public void basicInvocationSuppliesUnknownContext() throws Exception {
        ByteBuffer source = ByteBuffer.wrap(new byte[]{0, 1, 2, 3});
        source.position(1);
        source.mark();
        source.limit(3);
        ArMetadataCharsetDetector detector = context -> {
            assertTrue(context.bytes().isReadOnly());
            assertEquals(2, context.bytes().remaining());
            assertEquals(ArMetadataCharsetDetector.MetadataKind.UNKNOWN, context.metadataKind());
            assertEquals(ArMetadataCharsetDetector.Source.UNKNOWN, context.source());
            assertNull(context.headerIdentifier());
            assertEquals(ArMetadataCharsetDetector.UNKNOWN_MEMBER_SIZE, context.memberSize());
            context.bytes().position(context.bytes().limit());
            return null;
        };

        assertNull(detector.detect(source));
        assertEquals(1, source.position());
        assertEquals(3, source.limit());
        source.reset();
        assertEquals(1, source.position());
    }

    /// Verifies known contexts preserve fields and reject member sizes below the unknown sentinel.
    @Test
    public void contextValidatesMemberSize() {
        ByteBuffer source = ByteBuffer.wrap(new byte[]{4, 5, 6});
        ArMetadataCharsetDetector.Context context = new ArMetadataCharsetDetector.Context(
                source,
                ArMetadataCharsetDetector.MetadataKind.ENTRY_NAME,
                ArMetadataCharsetDetector.Source.BSD_LONG_NAME,
                "#1/3",
                6L
        );

        assertTrue(context.bytes().isReadOnly());
        context.bytes().position(1);
        assertEquals(0, source.position());
        assertEquals(ArMetadataCharsetDetector.MetadataKind.ENTRY_NAME, context.metadataKind());
        assertEquals(ArMetadataCharsetDetector.Source.BSD_LONG_NAME, context.source());
        assertEquals("#1/3", context.headerIdentifier());
        assertEquals(6L, context.memberSize());

        assertThrows(
                IllegalArgumentException.class,
                () -> new ArMetadataCharsetDetector.Context(
                        ByteBuffer.allocate(0),
                        ArMetadataCharsetDetector.MetadataKind.ENTRY_NAME,
                        ArMetadataCharsetDetector.Source.HEADER_IDENTIFIER,
                        null,
                        ArMetadataCharsetDetector.UNKNOWN_MEMBER_SIZE - 1L
                )
        );
    }
}
