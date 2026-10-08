// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies RAR4-specific legacy metadata decoding contexts.
@NotNullByDefault
public final class RarLegacyMetadataDecoderTest {
    /// Verifies basic decoder calls receive unknown RAR4 metadata without aliasing buffer state.
    @Test
    public void basicInvocationSuppliesUnknownContext() throws Exception {
        ByteBuffer source = ByteBuffer.wrap(new byte[]{0, 1, 2});
        source.position(1);
        source.mark();
        RarLegacyMetadataDecoder metadataDecoder = context -> {
            assertTrue(context.bytes().isReadOnly());
            assertEquals(RarLegacyMetadataDecoder.MetadataKind.UNKNOWN, context.metadataKind());
            assertEquals(RarLegacyMetadataDecoder.UNKNOWN_HEADER_VALUE, context.hostOperatingSystem());
            assertEquals(RarLegacyMetadataDecoder.UNKNOWN_HEADER_VALUE, context.extractionVersion());
            assertEquals(RarLegacyMetadataDecoder.UNKNOWN_HEADER_VALUE, context.headerFlags());
            assertEquals(RarLegacyMetadataDecoder.UNKNOWN_FILE_ATTRIBUTES, context.fileAttributes());
            context.bytes().position(context.bytes().limit());
            return "decoded";
        };

        assertEquals("decoded", metadataDecoder.decode(source));
        assertEquals(1, source.position());
        source.reset();
        assertEquals(1, source.position());
    }

    /// Verifies contextual fields accept their complete unsigned ranges.
    @Test
    public void contextAcceptsUnsignedMaximums() {
        ByteBuffer source = ByteBuffer.wrap(new byte[]{3, 4});
        RarLegacyMetadataDecoder.Context context = new RarLegacyMetadataDecoder.Context(
                source,
                RarLegacyMetadataDecoder.MetadataKind.ENTRY_NAME,
                0xff,
                0xff,
                0xffff,
                0xffff_ffffL
        );

        assertTrue(context.bytes().isReadOnly());
        context.bytes().position(1);
        assertEquals(0, source.position());
        assertEquals(0xff, context.hostOperatingSystem());
        assertEquals(0xff, context.extractionVersion());
        assertEquals(0xffff, context.headerFlags());
        assertEquals(0xffff_ffffL, context.fileAttributes());
    }

    /// Verifies contextual integer fields reject values outside their unsigned ranges and sentinels.
    @Test
    public void contextRejectsOutOfRangeValues() {
        assertInvalid(-2, -1, -1, -1L);
        assertInvalid(0x100, -1, -1, -1L);
        assertInvalid(-1, -2, -1, -1L);
        assertInvalid(-1, 0x100, -1, -1L);
        assertInvalid(-1, -1, -2, -1L);
        assertInvalid(-1, -1, 0x1_0000, -1L);
        assertInvalid(-1, -1, -1, -2L);
        assertInvalid(-1, -1, -1, 0x1_0000_0000L);
    }

    /// Requires a context constructor to reject one invalid header-field combination.
    private static void assertInvalid(
            int hostOperatingSystem,
            int extractionVersion,
            int headerFlags,
            long fileAttributes
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RarLegacyMetadataDecoder.Context(
                        ByteBuffer.allocate(0),
                        RarLegacyMetadataDecoder.MetadataKind.ENTRY_NAME,
                        hostOperatingSystem,
                        extractionVersion,
                        headerFlags,
                        fileAttributes
                )
        );
    }
}
