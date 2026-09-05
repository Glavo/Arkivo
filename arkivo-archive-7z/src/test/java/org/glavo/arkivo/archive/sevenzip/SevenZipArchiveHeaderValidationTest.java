// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies fixed 7z archive-header parsing and next-header integrity checks.
@NotNullByDefault
final class SevenZipArchiveHeaderValidationTest {
    /// Verifies valid non-empty next-header metadata is exposed unchanged.
    @Test
    void readsNonEmptyNextHeaderMetadata() throws IOException {
        byte[] nextHeader = new byte[]{0};
        byte[] archive = SevenZipTestArchiveFixtures.archiveWithNextHeader(nextHeader);
        CRC32 crc32 = new CRC32();
        crc32.update(nextHeader);

        try (SeekableInMemoryByteChannel channel = new SeekableInMemoryByteChannel(archive);
             SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(channel)) {
            assertEquals(0L, fileSystem.nextHeaderOffset());
            assertEquals(1L, fileSystem.nextHeaderSize());
            assertEquals(crc32.getValue(), fileSystem.nextHeaderCrc32());
        }
    }

    /// Verifies an invalid 7z signature is rejected before a file system is exposed.
    @Test
    void rejectsInvalidSignature() {
        assertOpenRejected(new byte[32]);
    }

    /// Verifies a next-header CRC mismatch is rejected before a file system is exposed.
    @Test
    void rejectsInvalidNextHeaderCrc() {
        assertOpenRejected(SevenZipTestArchiveFixtures.archiveWithNextHeader(new byte[]{0}, 1L));
    }

    /// Verifies unsigned next-header values beyond the supported signed range fail as I/O errors.
    ///
    /// @param description the case name used in parameterized-test output
    /// @param nextHeaderOffset the raw next-header offset field
    /// @param nextHeaderSize the raw next-header size field
    /// @param expectedMessage the diagnostic fragment required from the indexed reader
    @ParameterizedTest(name = "{0}")
    @MethodSource("oversizedNextHeaderFields")
    void rejectsOversizedUnsignedNextHeaderFields(
            String description,
            long nextHeaderOffset,
            long nextHeaderSize,
            String expectedMessage
    ) {
        byte[] archive = SevenZipTestArchiveFixtures.rawSignatureHeader(nextHeaderOffset, nextHeaderSize, 0L);
        IOException failure = assertOpenRejected(archive);
        assertTrue(failure.getMessage().contains(expectedMessage), description);
    }

    /// Returns unsupported unsigned next-header field values and their diagnostics.
    private static Stream<Arguments> oversizedNextHeaderFields() {
        return Stream.of(
                Arguments.of("offset", Long.MIN_VALUE, 0L, "next header offset is too large"),
                Arguments.of("size", 0L, Long.MIN_VALUE, "next header size is too large")
        );
    }

    /// Opens an in-memory archive and returns the resulting validation failure.
    private static IOException assertOpenRejected(byte[] archive) {
        return assertThrows(IOException.class, () -> {
            try (SeekableInMemoryByteChannel channel = new SeekableInMemoryByteChannel(archive);
                 SevenZipArkivoFileSystem ignored = SevenZipArkivoFileSystem.open(channel)) {
                // Opening a 7z file system validates its fixed and next headers.
            }
        });
    }
}
