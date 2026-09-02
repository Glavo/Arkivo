// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg;

import org.glavo.arkivo.codec.deflate.ZlibCodec;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.ADC_RUN;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.RAW_RUN;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.SECTOR_SIZE;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.ZLIB_RUN;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.compress;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.sector;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.writeImage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies deterministic rejection of malformed UDIF structures and compressed runs.
@NotNullByDefault
final class UDIFValidationTest {
    /// The generated test directory.
    @TempDir
    Path temporaryDirectory;

    /// Rejects invalid fixed trailer fields with a format-specific diagnostic.
    ///
    /// @param name the fixture name
    /// @param fieldOffset the field offset within the final trailer
    /// @param replacement the invalid big-endian field value
    /// @param expectedMessage the required diagnostic fragment
    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedTrailers")
    void rejectsMalformedTrailer(
            String name,
            int fieldOffset,
            int replacement,
            String expectedMessage
    ) throws IOException {
        Path valid = writeImage(
                temporaryDirectory.resolve(name + "-valid.dmg"),
                List.of(new DMGTestFixtures.Run(RAW_RUN, sector(61)))
        );
        byte[] bytes = Files.readAllBytes(valid);
        ByteArrayAccess.writeIntBigEndian(bytes, bytes.length - SECTOR_SIZE + fieldOffset, replacement);
        Path malformed = temporaryDirectory.resolve(name + ".dmg");
        Files.write(malformed, bytes);

        IOException exception = assertThrows(IOException.class, () -> DMGImage.open(malformed));
        assertTrue(exception.getMessage().contains(expectedMessage), exception::getMessage);
    }

    /// Supplies invalid UDIF trailer fields and their expected diagnostics.
    private static Stream<Arguments> malformedTrailers() {
        return Stream.of(
                Arguments.of("missing-signature", 0, 0, "Missing UDIF koly trailer signature"),
                Arguments.of("unsupported-version", 4, 3, "Unsupported UDIF trailer version or size"),
                Arguments.of("invalid-header-size", 8, 511, "Unsupported UDIF trailer version or size"),
                Arguments.of("not-flattened", 12, 0, "Only flattened UDIF images are supported"),
                Arguments.of("multiple-segments", 60, 2, "Multi-segment UDIF images are not supported")
        );
    }

    /// Rejects an encoded source shorter than the fixed UDIF trailer.
    @Test
    void rejectsTruncatedImage() throws IOException {
        Path path = temporaryDirectory.resolve("truncated.dmg");
        Files.write(path, new byte[SECTOR_SIZE - 1]);

        IOException exception = assertThrows(IOException.class, () -> DMGImage.open(path));
        assertEquals("Disk image is too short to contain a UDIF trailer", exception.getMessage());
    }

    /// Rejects malformed XML instead of accepting a partial block layout.
    @Test
    void rejectsMalformedPropertyList() throws IOException {
        Path path = writeImage(
                temporaryDirectory.resolve("malformed-plist.dmg"),
                List.of(new DMGTestFixtures.Run(RAW_RUN, sector(62)))
        );
        byte[] bytes = Files.readAllBytes(path);
        bytes[SECTOR_SIZE] = 0;
        Files.write(path, bytes);

        IOException exception = assertThrows(IOException.class, () -> DMGImage.open(path));
        assertEquals("Invalid UDIF XML property list", exception.getMessage());
    }

    /// Rejects an ADC literal that extends beyond the encoded input.
    @Test
    void rejectsMalformedADCData() throws IOException {
        Path path = writeImage(
                temporaryDirectory.resolve("malformed-adc.dmg"),
                List.of(new DMGTestFixtures.Run(ADC_RUN, new byte[]{(byte) 0xff}))
        );

        try (DMGImage image = DMGImage.open(path);
             SeekableByteChannel channel = image.openChannel()) {
            IOException exception = assertThrows(IOException.class, () -> channel.read(ByteBuffer.allocate(1)));
            assertEquals("Invalid ADC literal range", exception.getMessage());
        }
    }

    /// Rejects a compressed run that produces fewer bytes than its declared logical sector.
    @Test
    void rejectsCompressedSizeMismatch() throws IOException {
        byte[] shortSector = new byte[SECTOR_SIZE - 1];
        Path path = writeImage(
                temporaryDirectory.resolve("short-zlib-output.dmg"),
                List.of(new DMGTestFixtures.Run(ZLIB_RUN, compress(ZlibCodec.DEFAULT, shortSector)))
        );

        try (DMGImage image = DMGImage.open(path);
             SeekableByteChannel channel = image.openChannel()) {
            assertThrows(IOException.class, () -> channel.read(ByteBuffer.allocate(1)));
        }
    }
}
