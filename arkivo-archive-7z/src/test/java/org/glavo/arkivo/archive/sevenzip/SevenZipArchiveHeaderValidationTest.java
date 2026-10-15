// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies fixed 7z archive-header parsing and next-header integrity checks.
@NotNullByDefault
final class SevenZipArchiveHeaderValidationTest {
    /// Rejects an oversized declared header before reading its payload for CRC verification.
    @Test
    void checksMetadataBudgetBeforeReadingNextHeader() throws IOException {
        byte[] archive = SevenZipTestArchiveFixtures.emptyFileArchive("entry-" + "x".repeat(4096));
        for (long maximum : new long[]{0, 31, 32, archive.length - 1}) {
            CountingChannel channel = new CountingChannel(archive);
            SevenZipArchiveOptions.Read options = SevenZipArchiveOptions.READ_DEFAULTS.withCommon(
                    ArchiveReadOptions.DEFAULT.withLimits(
                            ArchiveReadLimits.builder().maximumMetadataSize(maximum).build()));
            ArkivoReadLimitException failure = assertThrows(ArkivoReadLimitException.class, () -> {
                try (SevenZipArkivoFileSystem ignored = SevenZipArkivoFileSystem.open(channel, options)) {
                    // Opening must stop before the next header is read.
                }
            });
            assertEquals(ArkivoReadLimitKind.METADATA_SIZE, failure.kind());
            assertEquals(maximum, failure.maximum());
            assertEquals(maximum < 32 ? 32 : archive.length, failure.actual());
            assertEquals(32, channel.bytesRead);
        }

        SevenZipArchiveOptions.Read options = SevenZipArchiveOptions.READ_DEFAULTS.withCommon(
                ArchiveReadOptions.DEFAULT.withLimits(
                        ArchiveReadLimits.builder().maximumMetadataSize(archive.length).build()));
        try (CountingChannel channel = new CountingChannel(archive);
             SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(channel, options)) {
            assertEquals(archive.length - 32, fileSystem.nextHeaderSize());
            assertTrue(channel.bytesRead >= archive.length);
        }
    }

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

    /// Counts bytes returned to the archive reader without changing channel behavior.
    @NotNullByDefault
    private static final class CountingChannel extends SeekableInMemoryByteChannel {
        /// The cumulative number of returned bytes.
        private long bytesRead;

        /// Creates a channel containing one complete archive.
        private CountingChannel(byte[] archive) {
            super(archive);
        }

        /// Adds successful read progress to the counter.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            int count = super.read(destination);
            if (count > 0) {
                bytesRead += count;
            }
            return count;
        }
    }
}
