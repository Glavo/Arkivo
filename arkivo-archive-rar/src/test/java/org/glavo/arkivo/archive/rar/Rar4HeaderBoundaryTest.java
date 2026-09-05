// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies unsigned RAR4 large-file size composition at the signed Java `long` boundary.
@NotNullByDefault
final class Rar4HeaderBoundaryTest {
    /// Decodes independent nonzero high words without truncating either declared size.
    @Test
    void decodesLargePackedAndUnpackedSizes() throws IOException {
        byte[] archive = RarTestArchiveFixtures.rar4LargeFileHeaderArchive(
                0xffff_fffeL,
                0x0000_0003L,
                0x0000_0001L,
                0x0000_0002L
        );

        RarArkivoEntryAttributes attributes = readFirstAttributes(archive);
        assertEquals(0x0000_0001_ffff_fffeL, attributes.packedSize());
        assertEquals(0x0000_0002_0000_0003L, attributes.unpackedSize());
    }

    /// Accepts the largest 64-bit size representable by Arkivo's signed size contract.
    @Test
    void decodesMaximumSignedLongSizes() throws IOException {
        byte[] archive = RarTestArchiveFixtures.rar4LargeFileHeaderArchive(
                0xffff_ffffL,
                0xffff_ffffL,
                0x7fff_ffffL,
                0x7fff_ffffL
        );

        RarArkivoEntryAttributes attributes = readFirstAttributes(archive);
        assertEquals(Long.MAX_VALUE, attributes.packedSize());
        assertEquals(Long.MAX_VALUE, attributes.unpackedSize());
    }

    /// Rejects packed and unpacked unsigned sizes outside the non-negative Java `long` range.
    @Test
    void rejectsSizesAboveMaximumSignedLong() throws IOException {
        byte[] oversizedPacked = RarTestArchiveFixtures.rar4LargeFileHeaderArchive(
                0L,
                0L,
                0x8000_0000L,
                0L
        );
        byte[] oversizedUnpacked = RarTestArchiveFixtures.rar4LargeFileHeaderArchive(
                0L,
                0L,
                0L,
                0x8000_0000L
        );

        assertHeaderRejected(oversizedPacked, "RAR4 packed size is too large");
        assertHeaderRejected(oversizedUnpacked, "RAR4 unpacked size is too large");
    }

    /// Reads the first entry attributes without consuming its intentionally omitted data area.
    private static RarArkivoEntryAttributes readFirstAttributes(byte[] archive) throws IOException {
        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertTrue(reader.next());
            return reader.readAttributes(RarArkivoEntryAttributes.class);
        }
    }

    /// Requires parsing the first generated file header to fail with the expected diagnostic.
    private static void assertHeaderRejected(byte[] archive, String expectedMessage) throws IOException {
        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            IOException exception = assertThrows(IOException.class, reader::next);
            assertEquals(expectedMessage, exception.getMessage());
        }
    }
}
