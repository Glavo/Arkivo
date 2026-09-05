// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg;

import org.glavo.arkivo.codec.deflate.ZlibCodec;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.ADC_RUN;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.RAW_RUN;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.SECTOR_SIZE;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.ZLIB_RUN;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.adcRepeatedByte;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.compress;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.concatenate;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.sector;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.writeImage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies deterministic rejection of malformed UDIF structures and compressed runs.
@NotNullByDefault
final class UDIFValidationTest {
    /// The first run-descriptor offset in a generated `mish` table.
    private static final int FIRST_RUN_OFFSET = 204;

    /// The encoded `mish` run count field offset.
    private static final int RUN_COUNT_OFFSET = 200;

    /// The generated test directory.
    @TempDir
    Path temporaryDirectory;

    /// Rejects invalid fixed trailer fields with a format-specific diagnostic.
    ///
    /// @param testCase the deterministic trailer mutation and expected diagnostic
    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedTrailers")
    void rejectsMalformedTrailer(TrailerCase testCase) throws IOException {
        Path valid = writeImage(
                temporaryDirectory.resolve(testCase.name() + "-valid.dmg"),
                List.of(new DMGTestFixtures.Run(RAW_RUN, sector(61)))
        );
        byte[] bytes = Files.readAllBytes(valid);
        int trailerOffset = bytes.length - SECTOR_SIZE;
        testCase.mutation().mutate(bytes, trailerOffset);
        Path malformed = temporaryDirectory.resolve(testCase.name() + ".dmg");
        Files.write(malformed, bytes);

        IOException exception = assertThrows(IOException.class, () -> DMGImage.open(malformed));
        assertTrue(exception.getMessage().contains(testCase.expectedMessage()), exception::getMessage);
    }

    /// Supplies invalid UDIF trailer fields and their expected diagnostics.
    private static Stream<TrailerCase> malformedTrailers() {
        return Stream.of(
                new TrailerCase(
                        "missing-signature",
                        trailerInt(0, 0),
                        "Missing UDIF koly trailer signature"
                ),
                new TrailerCase(
                        "unsupported-version",
                        trailerInt(4, 3),
                        "Unsupported UDIF trailer version or size"
                ),
                new TrailerCase(
                        "invalid-header-size",
                        trailerInt(8, 511),
                        "Unsupported UDIF trailer version or size"
                ),
                new TrailerCase(
                        "not-flattened",
                        trailerInt(12, 0),
                        "Only flattened UDIF images are supported"
                ),
                new TrailerCase(
                        "multiple-segments",
                        trailerInt(60, 2),
                        "Multi-segment UDIF images are not supported"
                ),
                new TrailerCase(
                        "unsigned-data-fork-offset",
                        trailerLong(24, Long.MIN_VALUE),
                        "UDIF data-fork offset exceeds the supported signed 64-bit range"
                ),
                new TrailerCase(
                        "unsigned-data-fork-length",
                        trailerLong(32, Long.MIN_VALUE),
                        "UDIF data-fork length exceeds the supported signed 64-bit range"
                ),
                new TrailerCase(
                        "unsigned-xml-offset",
                        trailerLong(216, Long.MIN_VALUE),
                        "UDIF XML offset exceeds the supported signed 64-bit range"
                ),
                new TrailerCase(
                        "unsigned-xml-length",
                        trailerLong(224, Long.MIN_VALUE),
                        "UDIF XML length exceeds the supported signed 64-bit range"
                ),
                new TrailerCase(
                        "unsigned-sector-count",
                        trailerLong(492, Long.MIN_VALUE),
                        "UDIF sector count exceeds the supported signed 64-bit range"
                ),
                new TrailerCase(
                        "overflowing-data-fork-range",
                        trailerLongPair(24, Long.MAX_VALUE, 32, 1L),
                        "Invalid or overflowing UDIF data fork range"
                ),
                new TrailerCase(
                        "data-fork-outside-image",
                        trailerLongPair(24, 0L, 32, Long.MAX_VALUE),
                        "UDIF data fork range exceeds the disk image"
                ),
                new TrailerCase(
                        "overflowing-xml-range",
                        trailerLongPair(216, Long.MAX_VALUE, 224, 1L),
                        "Invalid or overflowing UDIF XML property list range"
                ),
                new TrailerCase(
                        "xml-outside-image",
                        trailerLongPair(216, 0L, 224, Long.MAX_VALUE),
                        "UDIF XML property list range exceeds the disk image"
                ),
                new TrailerCase(
                        "xml-overlaps-trailer",
                        (bytes, trailerOffset) -> {
                            ByteArrayAccess.writeLongBigEndian(bytes, trailerOffset + 216, trailerOffset);
                            ByteArrayAccess.writeLongBigEndian(bytes, trailerOffset + 224, 1L);
                        },
                        "UDIF XML property list overlaps the trailer"
                ),
                new TrailerCase(
                        "missing-xml-resource-fork",
                        trailerLong(224, 0L),
                        "UDIF image has no XML resource fork"
                ),
                new TrailerCase(
                        "overflowing-decoded-image-size",
                        trailerLong(492, Long.MAX_VALUE),
                        "Invalid or overflowing UDIF decoded image size"
                )
        );
    }

    /// Accepts the canonical one-based numbering used by a non-split single-segment image.
    @Test
    void acceptsOneBasedSingleSegmentTrailer() throws IOException {
        Path path = writeImage(
                temporaryDirectory.resolve("one-based-single-segment.dmg"),
                List.of(new DMGTestFixtures.Run(RAW_RUN, sector(60)))
        );
        byte[] bytes = Files.readAllBytes(path);
        int trailerOffset = bytes.length - SECTOR_SIZE;
        ByteArrayAccess.writeIntBigEndian(bytes, trailerOffset + 56, 1);
        ByteArrayAccess.writeIntBigEndian(bytes, trailerOffset + 60, 1);
        Files.write(path, bytes);

        try (DMGImage image = DMGImage.open(path)) {
            assertEquals(SECTOR_SIZE, image.size());
        }
    }

    /// Creates a mutation that writes one big-endian 32-bit trailer field.
    ///
    /// @param fieldOffset the field offset within the trailer
    /// @param value the replacement value
    private static TrailerMutation trailerInt(int fieldOffset, int value) {
        return (bytes, trailerOffset) ->
                ByteArrayAccess.writeIntBigEndian(bytes, trailerOffset + fieldOffset, value);
    }

    /// Creates a mutation that writes one big-endian 64-bit trailer field.
    ///
    /// @param fieldOffset the field offset within the trailer
    /// @param value the replacement value
    private static TrailerMutation trailerLong(int fieldOffset, long value) {
        return (bytes, trailerOffset) ->
                ByteArrayAccess.writeLongBigEndian(bytes, trailerOffset + fieldOffset, value);
    }

    /// Creates a mutation that writes two big-endian 64-bit trailer fields.
    ///
    /// @param firstOffset the first field offset within the trailer
    /// @param firstValue the first replacement value
    /// @param secondOffset the second field offset within the trailer
    /// @param secondValue the second replacement value
    private static TrailerMutation trailerLongPair(
            int firstOffset,
            long firstValue,
            int secondOffset,
            long secondValue
    ) {
        return (bytes, trailerOffset) -> {
            ByteArrayAccess.writeLongBigEndian(bytes, trailerOffset + firstOffset, firstValue);
            ByteArrayAccess.writeLongBigEndian(bytes, trailerOffset + secondOffset, secondValue);
        };
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

    /// Rejects well-formed property lists whose resource-fork structure cannot describe block tables.
    ///
    /// @param testCase the deterministic property-list mutation and expected diagnostic
    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedPropertyLists")
    void rejectsMalformedPropertyListStructures(PropertyListCase testCase) throws IOException {
        Path path = writeMutatedPropertyListImage(testCase.name(), testCase.mutation());

        IOException exception = assertThrows(IOException.class, () -> DMGImage.open(path));

        assertEquals(testCase.expectedMessage(), exception.getMessage());
    }

    /// Rejects malformed block-table fields before exposing a decoded image channel.
    ///
    /// @param testCase the deterministic block-table mutation and expected diagnostic
    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedBlockTables")
    void rejectsMalformedBlockTables(BlockTableCase testCase) throws IOException {
        Path path = writeMutatedBlockTableImage(
                testCase.name(),
                List.of(new DMGTestFixtures.Run(RAW_RUN, sector(63))),
                testCase.mutation()
        );

        IOException exception = assertThrows(IOException.class, () -> DMGImage.open(path));

        assertTrue(exception.getMessage().contains(testCase.expectedMessage()), exception::getMessage);
    }

    /// Rejects data runs whose decoded ranges overlap after block-table ordering is normalized.
    @Test
    void rejectsOverlappingDataRuns() throws IOException {
        Path path = writeMutatedBlockTableImage(
                "overlapping-runs",
                List.of(
                        new DMGTestFixtures.Run(RAW_RUN, sector(64)),
                        new DMGTestFixtures.Run(RAW_RUN, sector(65))
                ),
                table -> ByteArrayAccess.writeLongBigEndian(table, FIRST_RUN_OFFSET + 40 + 8, 0L)
        );

        IOException exception = assertThrows(IOException.class, () -> DMGImage.open(path));

        assertEquals("Overlapping UDIF data runs at decoded offset 0", exception.getMessage());
    }

    /// Rejects an encoded run that begins before the trailer-declared data-fork lower bound.
    @Test
    void rejectsPhysicalRunBeforeDataFork() throws IOException {
        Path path = writeImage(
                temporaryDirectory.resolve("run-before-data-fork.dmg"),
                List.of(new DMGTestFixtures.Run(RAW_RUN, sector(66)))
        );
        byte[] image = Files.readAllBytes(path);
        int trailerOffset = image.length - SECTOR_SIZE;
        ByteArrayAccess.writeLongBigEndian(image, trailerOffset + 24, 1L);
        ByteArrayAccess.writeLongBigEndian(image, trailerOffset + 32, SECTOR_SIZE - 1L);
        Files.write(path, image);

        IOException exception = assertThrows(IOException.class, () -> DMGImage.open(path));

        assertEquals("UDIF physical run precedes the data fork", exception.getMessage());
    }

    /// Rejects malformed ADC commands and payload termination states.
    ///
    /// @param testCase the malformed ADC payload and exact diagnostic
    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedADCRuns")
    void rejectsMalformedADCData(ADCFailureCase testCase) throws IOException {
        Path path = writeImage(
                temporaryDirectory.resolve("malformed-adc-" + testCase.name() + ".dmg"),
                List.of(new DMGTestFixtures.Run(ADC_RUN, testCase.bytes()))
        );

        try (DMGImage image = DMGImage.open(path);
             SeekableByteChannel channel = image.openChannel()) {
            IOException exception = assertThrows(IOException.class, () -> channel.read(ByteBuffer.allocate(1)));
            assertEquals(testCase.expectedMessage(), exception.getMessage());
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

    /// Supplies malformed `mish` block-table mutations and their required diagnostic fragments.
    private static Stream<BlockTableCase> malformedBlockTables() {
        return Stream.of(
                new BlockTableCase(
                        "invalid-signature",
                        table -> ByteArrayAccess.writeIntBigEndian(table, 0, 0),
                        "Invalid UDIF mish block table"
                ),
                new BlockTableCase(
                        "unsupported-version",
                        table -> ByteArrayAccess.writeIntBigEndian(table, 4, 2),
                        "Unsupported UDIF mish block-table version"
                ),
                new BlockTableCase(
                        "negative-first-sector",
                        table -> ByteArrayAccess.writeLongBigEndian(table, 8, Long.MIN_VALUE),
                        "UDIF block-table first sector exceeds the supported signed 64-bit range"
                ),
                new BlockTableCase(
                        "negative-table-sector-count",
                        table -> ByteArrayAccess.writeLongBigEndian(table, 16, Long.MIN_VALUE),
                        "UDIF block-table sector count exceeds the supported signed 64-bit range"
                ),
                new BlockTableCase(
                        "negative-table-data-start",
                        table -> ByteArrayAccess.writeLongBigEndian(table, 24, Long.MIN_VALUE),
                        "UDIF block-table data start exceeds the supported signed 64-bit range"
                ),
                new BlockTableCase(
                        "overflowing-table-sector-range",
                        table -> {
                            ByteArrayAccess.writeLongBigEndian(table, 8, Long.MAX_VALUE);
                            ByteArrayAccess.writeLongBigEndian(table, 16, 1L);
                        },
                        "Invalid or overflowing UDIF block-table sector range"
                ),
                new BlockTableCase(
                        "table-exceeds-disk",
                        table -> ByteArrayAccess.writeLongBigEndian(table, 16, 2L),
                        "UDIF block table exceeds the decoded disk"
                ),
                new BlockTableCase(
                        "inconsistent-run-count",
                        table -> ByteArrayAccess.writeIntBigEndian(table, RUN_COUNT_OFFSET, 1),
                        "UDIF block table has an inconsistent run count"
                ),
                new BlockTableCase(
                        "run-exceeds-table",
                        table -> ByteArrayAccess.writeLongBigEndian(table, FIRST_RUN_OFFSET + 8, 1L),
                        "UDIF run exceeds its block-table sector range"
                ),
                new BlockTableCase(
                        "negative-run-sector-start",
                        table -> ByteArrayAccess.writeLongBigEndian(
                                table,
                                FIRST_RUN_OFFSET + 8,
                                Long.MIN_VALUE
                        ),
                        "UDIF run sector start exceeds the supported signed 64-bit range"
                ),
                new BlockTableCase(
                        "negative-run-sector-count",
                        table -> ByteArrayAccess.writeLongBigEndian(
                                table,
                                FIRST_RUN_OFFSET + 16,
                                Long.MIN_VALUE
                        ),
                        "UDIF run sector count exceeds the supported signed 64-bit range"
                ),
                new BlockTableCase(
                        "overflowing-run-sector-range",
                        table -> {
                            ByteArrayAccess.writeLongBigEndian(table, FIRST_RUN_OFFSET + 8, Long.MAX_VALUE);
                            ByteArrayAccess.writeLongBigEndian(table, FIRST_RUN_OFFSET + 16, 1L);
                        },
                        "Invalid or overflowing UDIF run sector range"
                ),
                new BlockTableCase(
                        "unsupported-run-type",
                        table -> ByteArrayAccess.writeIntBigEndian(table, FIRST_RUN_OFFSET, 0x1234_5678),
                        "Unsupported UDIF block type 0x12345678"
                ),
                new BlockTableCase(
                        "encoded-run-without-data",
                        table -> ByteArrayAccess.writeLongBigEndian(table, FIRST_RUN_OFFSET + 32, 0L),
                        "Encoded UDIF run has no physical bytes"
                ),
                new BlockTableCase(
                        "negative-run-compressed-offset",
                        table -> ByteArrayAccess.writeLongBigEndian(
                                table,
                                FIRST_RUN_OFFSET + 24,
                                Long.MIN_VALUE
                        ),
                        "UDIF run compressed offset exceeds the supported signed 64-bit range"
                ),
                new BlockTableCase(
                        "negative-run-compressed-length",
                        table -> ByteArrayAccess.writeLongBigEndian(
                                table,
                                FIRST_RUN_OFFSET + 32,
                                Long.MIN_VALUE
                        ),
                        "UDIF run compressed length exceeds the supported signed 64-bit range"
                ),
                new BlockTableCase(
                        "overflowing-run-physical-offset",
                        table -> {
                            ByteArrayAccess.writeLongBigEndian(table, 24, Long.MAX_VALUE);
                            ByteArrayAccess.writeLongBigEndian(table, FIRST_RUN_OFFSET + 24, 1L);
                        },
                        "Invalid or overflowing UDIF run physical offset"
                ),
                new BlockTableCase(
                        "physical-run-outside-data-fork",
                        table -> ByteArrayAccess.writeLongBigEndian(table, FIRST_RUN_OFFSET + 24, 1L),
                        "UDIF physical run range exceeds the disk image"
                ),
                new BlockTableCase(
                        "raw-run-size-mismatch",
                        table -> ByteArrayAccess.writeLongBigEndian(table, FIRST_RUN_OFFSET + 32, SECTOR_SIZE - 1L),
                        "Raw UDIF run length differs from its decoded length"
                ),
                new BlockTableCase(
                        "block-table-without-data-runs",
                        table -> ByteArrayAccess.writeIntBigEndian(table, FIRST_RUN_OFFSET, 0x7fff_fffe),
                        "UDIF resource fork contains no data runs"
                )
        );
    }

    /// Supplies structurally invalid XML property lists and their required diagnostics.
    private static Stream<PropertyListCase> malformedPropertyLists() {
        String tail = "</data></dict></array></dict></dict></plist>";
        return Stream.of(
                new PropertyListCase(
                        "missing-root-dictionary",
                        propertyList -> replaceRequired(
                                replaceRequired(
                                        propertyList,
                                        "<plist version=\"1.0\"><dict>",
                                        "<plist version=\"1.0\"><array>"
                                ),
                                "</dict></plist>",
                                "</array></plist>"
                        ),
                        "UDIF property list has no dict root"
                ),
                new PropertyListCase(
                        "missing-resource-fork",
                        propertyList -> replaceRequired(
                                propertyList,
                                "<key>resource-fork</key>",
                                "<key>ignored-resource-fork</key>"
                        ),
                        "UDIF property list has no resource-fork dictionary"
                ),
                new PropertyListCase(
                        "resource-fork-not-dictionary",
                        propertyList -> replaceRequired(
                                replaceRequired(
                                        propertyList,
                                        "<key>resource-fork</key><dict>",
                                        "<key>resource-fork</key><array>"
                                ),
                                tail,
                                "</data></dict></array></array></dict></plist>"
                        ),
                        "UDIF property list has no resource-fork dictionary"
                ),
                new PropertyListCase(
                        "missing-block-array",
                        propertyList -> replaceRequired(
                                propertyList,
                                "<key>blkx</key>",
                                "<key>ignored-blkx</key>"
                        ),
                        "UDIF resource fork has no blkx array"
                ),
                new PropertyListCase(
                        "block-value-not-array",
                        propertyList -> replaceRequired(
                                replaceRequired(
                                        propertyList,
                                        "<key>blkx</key><array>",
                                        "<key>blkx</key><dict>"
                                ),
                                tail,
                                "</data></dict></dict></dict></dict></plist>"
                        ),
                        "UDIF resource fork has no blkx array"
                ),
                new PropertyListCase(
                        "non-dictionary-block-entry",
                        propertyList -> replaceRequired(
                                replaceRequired(
                                        propertyList,
                                        "<array><dict><key>Data</key><data>",
                                        "<array><string>"
                                ),
                                "</data></dict></array>",
                                "</string></array>"
                        ),
                        "UDIF resource fork has no usable blkx data"
                ),
                new PropertyListCase(
                        "missing-block-data",
                        propertyList -> replaceRequired(
                                propertyList,
                                "<key>Data</key>",
                                "<key>IgnoredData</key>"
                        ),
                        "UDIF resource fork has no usable blkx data"
                ),
                new PropertyListCase(
                        "block-data-not-data-element",
                        propertyList -> replaceRequired(
                                replaceRequired(
                                        propertyList,
                                        "<key>Data</key><data>",
                                        "<key>Data</key><string>"
                                ),
                                "</data>",
                                "</string>"
                        ),
                        "UDIF resource fork has no usable blkx data"
                ),
                new PropertyListCase(
                        "invalid-block-data-base64",
                        propertyList -> replaceDataContent(propertyList, "A"),
                        "Invalid base64 data in a UDIF blkx entry"
                )
        );
    }

    /// Supplies malformed ADC payloads spanning every command and terminal validation branch.
    private static Stream<ADCFailureCase> malformedADCRuns() {
        return Stream.of(
                new ADCFailureCase(
                        "truncated-literal",
                        new byte[]{(byte) 0xff},
                        "Invalid ADC literal range"
                ),
                new ADCFailureCase(
                        "truncated-long-back-reference",
                        new byte[]{0x40},
                        "Truncated ADC long back-reference"
                ),
                new ADCFailureCase(
                        "truncated-short-back-reference",
                        new byte[]{0x00},
                        "Truncated ADC short back-reference"
                ),
                new ADCFailureCase(
                        "invalid-back-reference",
                        new byte[]{0x00, 0x00},
                        "Invalid ADC back-reference"
                ),
                new ADCFailureCase(
                        "incomplete-output",
                        new byte[]{(byte) 0x80, (byte) 'A'},
                        "ADC run did not decode to its declared size"
                ),
                new ADCFailureCase(
                        "trailing-input",
                        concatenate(new byte[][]{adcRepeatedByte(), new byte[]{0x00}}),
                        "ADC run did not decode to its declared size"
                )
        );
    }

    /// Writes a generated image after replacing its complete XML resource fork.
    ///
    /// @param name the fixture file stem
    /// @param mutation the property-list mutation
    /// @return the mutated image path
    private Path writeMutatedPropertyListImage(
            String name,
            UnaryOperator<String> mutation
    ) throws IOException {
        Path path = writeImage(
                temporaryDirectory.resolve(name + ".dmg"),
                List.of(new DMGTestFixtures.Run(RAW_RUN, sector(67)))
        );
        byte[] image = Files.readAllBytes(path);
        int trailerOffset = image.length - SECTOR_SIZE;
        int xmlOffset = Math.toIntExact(ByteArrayAccess.readLongBigEndian(image, trailerOffset + 216));
        int xmlLength = Math.toIntExact(ByteArrayAccess.readLongBigEndian(image, trailerOffset + 224));
        String propertyList = new String(image, xmlOffset, xmlLength, StandardCharsets.UTF_8);
        String mutatedPropertyList = mutation.apply(propertyList);
        if (mutatedPropertyList.equals(propertyList)) {
            throw new AssertionError("Property-list mutation did not change the generated fixture");
        }

        byte[] replacement = mutatedPropertyList.getBytes(StandardCharsets.UTF_8);
        byte[] trailer = Arrays.copyOfRange(image, trailerOffset, image.length);
        ByteArrayAccess.writeLongBigEndian(trailer, 224, replacement.length);
        byte[] mutatedImage = new byte[Math.addExact(Math.addExact(xmlOffset, replacement.length), trailer.length)];
        System.arraycopy(image, 0, mutatedImage, 0, xmlOffset);
        System.arraycopy(replacement, 0, mutatedImage, xmlOffset, replacement.length);
        System.arraycopy(trailer, 0, mutatedImage, xmlOffset + replacement.length, trailer.length);
        return Files.write(path, mutatedImage);
    }

    /// Replaces one unique literal substring or fails when the generated fixture shape has changed.
    private static String replaceRequired(String source, String target, String replacement) {
        int offset = source.indexOf(target);
        if (offset < 0 || source.indexOf(target, offset + target.length()) >= 0) {
            throw new AssertionError("Expected exactly one property-list fragment: " + target);
        }
        return source.substring(0, offset) + replacement + source.substring(offset + target.length());
    }

    /// Replaces the text of the generated block-table data element.
    private static String replaceDataContent(String source, String replacement) {
        String openingTag = "<data>";
        String closingTag = "</data>";
        int start = source.indexOf(openingTag);
        int end = source.indexOf(closingTag, start + openingTag.length());
        if (start < 0 || end < 0 || source.indexOf(openingTag, start + openingTag.length()) >= 0) {
            throw new AssertionError("Generated UDIF fixture does not contain exactly one data element");
        }
        return source.substring(0, start + openingTag.length()) + replacement + source.substring(end);
    }

    /// Writes a generated image after mutating the first embedded block table in place.
    ///
    /// @param name the fixture file stem
    /// @param runs the initially valid encoded runs
    /// @param mutation the same-length block-table mutation
    /// @return the mutated image path
    private Path writeMutatedBlockTableImage(
            String name,
            List<DMGTestFixtures.Run> runs,
            Consumer<byte[]> mutation
    ) throws IOException {
        Path path = writeImage(temporaryDirectory.resolve(name + ".dmg"), runs);
        byte[] image = Files.readAllBytes(path);
        int trailerOffset = image.length - SECTOR_SIZE;
        int xmlOffset = Math.toIntExact(ByteArrayAccess.readLongBigEndian(image, trailerOffset + 216));
        int xmlLength = Math.toIntExact(ByteArrayAccess.readLongBigEndian(image, trailerOffset + 224));
        String propertyList = new String(image, xmlOffset, xmlLength, StandardCharsets.UTF_8);
        int encodedStart = propertyList.indexOf("<data>") + "<data>".length();
        int encodedEnd = propertyList.indexOf("</data>", encodedStart);
        if (encodedStart < "<data>".length() || encodedEnd < encodedStart) {
            throw new AssertionError("Generated UDIF fixture has no block-table data element");
        }

        String encoded = propertyList.substring(encodedStart, encodedEnd);
        byte[] table = Base64.getDecoder().decode(encoded);
        mutation.accept(table);
        byte[] replacement = Base64.getEncoder().encode(table);
        if (replacement.length != encoded.length()) {
            throw new AssertionError("Same-length block-table mutation changed its base64 length");
        }
        System.arraycopy(replacement, 0, image, xmlOffset + encodedStart, replacement.length);
        return Files.write(path, image);
    }

    /// Mutates fields within the final trailer of one generated image.
    @FunctionalInterface
    @NotNullByDefault
    private interface TrailerMutation {
        /// Applies the mutation at the supplied absolute trailer offset.
        void mutate(byte[] image, int trailerOffset);
    }

    /// Describes one deterministic invalid trailer mutation.
    ///
    /// @param name the fixture and parameterized-test name
    /// @param mutation the mutation applied to an initially valid image trailer
    /// @param expectedMessage the required parser diagnostic fragment
    @NotNullByDefault
    private record TrailerCase(
            String name,
            TrailerMutation mutation,
            String expectedMessage
    ) {
        /// Returns the concise parameterized-test display name.
        @Override
        public String toString() {
            return name;
        }
    }

    /// Describes one deterministic invalid block-table mutation.
    ///
    /// @param name the fixture and parameterized-test name
    /// @param mutation the mutation applied to an initially valid block table
    /// @param expectedMessage the required parser diagnostic fragment
    @NotNullByDefault
    private record BlockTableCase(
            String name,
            Consumer<byte[]> mutation,
            String expectedMessage
    ) {
        /// Returns the concise parameterized-test display name.
        @Override
        public String toString() {
            return name;
        }
    }

    /// Describes one deterministic invalid property-list mutation.
    ///
    /// @param name the fixture and parameterized-test name
    /// @param mutation the mutation applied to an initially valid property list
    /// @param expectedMessage the exact parser diagnostic
    @NotNullByDefault
    private record PropertyListCase(
            String name,
            UnaryOperator<String> mutation,
            String expectedMessage
    ) {
        /// Returns the concise parameterized-test display name.
        @Override
        public String toString() {
            return name;
        }
    }

    /// Describes one malformed ADC payload and its required diagnostic.
    ///
    /// @param name the fixture and parameterized-test name
    /// @param bytes the malformed ADC payload
    /// @param expectedMessage the exact decoder diagnostic
    @NotNullByDefault
    private record ADCFailureCase(
            String name,
            byte @Unmodifiable [] bytes,
            String expectedMessage
    ) {
        /// Copies the mutable payload supplied by the test case declaration.
        private ADCFailureCase {
            bytes = bytes.clone();
        }

        /// Returns an isolated malformed payload for one generated image.
        @Override
        public byte @Unmodifiable [] bytes() {
            return bytes.clone();
        }

        /// Returns the concise parameterized-test display name.
        @Override
        public String toString() {
            return name;
        }
    }
}
