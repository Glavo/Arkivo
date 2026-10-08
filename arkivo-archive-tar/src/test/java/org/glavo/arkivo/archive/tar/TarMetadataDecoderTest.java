// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies TAR-specific metadata decoding contexts.
@NotNullByDefault
public final class TarMetadataDecoderTest {
    /// Verifies basic decoder calls receive unknown TAR metadata without aliasing buffer state.
    @Test
    public void basicInvocationSuppliesUnknownContext() throws Exception {
        ByteBuffer source = ByteBuffer.wrap(new byte[]{0, 1, 2});
        source.position(1);
        source.mark();
        TarMetadataDecoder metadataDecoder = context -> {
            assertTrue(context.bytes().isReadOnly());
            assertEquals(TarMetadataDecoder.MetadataKind.UNKNOWN, context.metadataKind());
            assertEquals(TarMetadataDecoder.Source.UNKNOWN, context.source());
            assertEquals(TarMetadataDecoder.HeaderDialect.UNKNOWN, context.headerDialect());
            assertEquals(TarMetadataDecoder.UNKNOWN_TYPE_FLAG, context.typeFlag());
            assertNull(context.paxKey());
            context.bytes().position(context.bytes().limit());
            return "decoded";
        };

        assertEquals("decoded", metadataDecoder.decode(source));
        assertEquals(1, source.position());
        source.reset();
        assertEquals(1, source.position());
    }

    /// Verifies contextual fields and the complete unsigned type-flag range.
    @Test
    public void contextValidatesTypeFlag() {
        ByteBuffer source = ByteBuffer.wrap(new byte[]{3, 4});
        TarMetadataDecoder.Context context = new TarMetadataDecoder.Context(
                source,
                TarMetadataDecoder.MetadataKind.LINK_NAME,
                TarMetadataDecoder.Source.PAX_EXTENDED_HEADER,
                TarMetadataDecoder.HeaderDialect.USTAR,
                0xff,
                "linkpath"
        );

        assertTrue(context.bytes().isReadOnly());
        context.bytes().position(1);
        assertEquals(0, source.position());
        assertEquals(TarMetadataDecoder.MetadataKind.LINK_NAME, context.metadataKind());
        assertEquals(TarMetadataDecoder.Source.PAX_EXTENDED_HEADER, context.source());
        assertEquals(TarMetadataDecoder.HeaderDialect.USTAR, context.headerDialect());
        assertEquals(0xff, context.typeFlag());
        assertEquals("linkpath", context.paxKey());

        assertInvalidTypeFlag(TarMetadataDecoder.UNKNOWN_TYPE_FLAG - 1);
        assertInvalidTypeFlag(0x100);
    }

    /// Requires a context constructor to reject one out-of-range type flag.
    private static void assertInvalidTypeFlag(int typeFlag) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TarMetadataDecoder.Context(
                        ByteBuffer.allocate(0),
                        TarMetadataDecoder.MetadataKind.ENTRY_NAME,
                        TarMetadataDecoder.Source.HEADER,
                        TarMetadataDecoder.HeaderDialect.USTAR,
                        typeFlag,
                        null
                )
        );
    }
}
