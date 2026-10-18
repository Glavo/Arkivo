// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
// Scenarios: XZ Utils 5.8.3 test_block_header.c, test_filter_flags.c, and test_index.c (0BSD).

package org.glavo.arkivo.codec.xz.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.tukaani.xz.XZInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Checks independently assembled XZ metadata through whole-buffer and fragmented decoding.
@NotNullByDefault
final class XZContainerBoundaryTest {
    /// Crosses optional sizes with the minimum and maximum filter counts and padded header lengths.
    private static Stream<Arguments> headerLayouts() {
        return IntStream.rangeClosed(1, 4).boxed().flatMap(count -> Stream.of(0, 0x40, 0x80, 0xc0)
                .flatMap(flags -> (count == 1 ? Stream.of(12, 32, 1024) : Stream.of(32, 1024))
                        .map(size -> Arguments.of(count, flags, size))));
    }

    /// Accepts independent optional size fields and up to four filters, including a maximum-size header.
    @ParameterizedTest
    @MethodSource("headerLayouts")
    void decodesHeaderLayouts(int filterCount, int sizeFlags, int headerSize) throws IOException {
        var fields = new ByteArrayOutputStream();
        if ((sizeFlags & 0x40) != 0) writeVli(fields, 1);
        if ((sizeFlags & 0x80) != 0) writeVli(fields, 0);
        for (int i = 1; i < filterCount; i++) fields.writeBytes(new byte[]{4, 0});
        fields.writeBytes(new byte[]{0x21, 1, 0});
        byte[] header = header(headerSize, sizeFlags | (filterCount - 1), fields.toByteArray());
        byte[] encoded = stream(header, new byte[]{0}, 1, index(1, header.length + 1, 0));
        assertDecoded(encoded, new byte[0]);
        // Padding is validated even when the CRC is correct and the padding lies far beyond the fields.
        header[header.length - 5] = 1;
        rewriteCrc(header, 0, header.length - 4, header.length - 4);
        assertRejected(stream(header, new byte[]{0}, 1, index(1, header.length + 1, 0)));
    }

    /// Rejects each reserved Block Flags bit instead of interpreting it as a supported size flag.
    @ParameterizedTest
    @ValueSource(ints = {4, 8, 16, 32, 60, 255})
    void rejectsReservedBlockFlags(int flags) throws IOException {
        byte[] header = header(12, flags, new byte[]{0x21, 1, 0});
        assertRejected(stream(header, new byte[]{0}, 1, index(1, 13, 0)));
    }

    /// Rejects unknown, reserved, and out-of-format filter IDs without narrowing the VLI to an int.
    @ParameterizedTest
    @ValueSource(longs = {0, 2, 0x1_0000_0004L, 0x4000_0000_0000_0000L,
            0x4000_0000_0000_0001L, Long.MAX_VALUE})
    void rejectsUnknownFilterIdentifiers(long identifier) throws IOException {
        var fields = new ByteArrayOutputStream();
        writeVli(fields, identifier);
        fields.write(0);
        fields.writeBytes(new byte[]{0x21, 1, 0});
        byte[] header = header(32, 1, fields.toByteArray());
        assertRejected(stream(header, new byte[]{0}, 1, index(1, 33, 0)));
    }

    /// Rejects property lengths before narrowing or allocating, including values above the Java array range.
    @ParameterizedTest
    @ValueSource(longs = {127, 128, Integer.MAX_VALUE, 0x1_0000_0000L, Long.MAX_VALUE})
    void rejectsExcessivePropertySizes(long size) throws IOException {
        var fields = new ByteArrayOutputStream();
        fields.write(0x21);
        writeVli(fields, size);
        byte[] header = header(32, 0, fields.toByteArray());
        assertRejected(stream(header, new byte[]{0}, 1, index(1, 33, 0)));
    }

    /// Rejects overlong and unterminated VLIs in each variable-length Block Header field.
    @ParameterizedTest
    @ValueSource(strings = {"compressed-size", "uncompressed-size", "filter-id", "properties-size"})
    void rejectsMalformedHeaderIntegers(String field) throws IOException {
        byte[] continued = new byte[9];
        Arrays.fill(continued, (byte) 0x80);
        for (byte[] bad : new byte[][]{{(byte) 0x80, 0}, continued}) {
            var fields = new ByteArrayOutputStream();
            int flags = switch (field) {
                case "compressed-size" -> 0x40;
                case "uncompressed-size" -> 0x80;
                default -> 0;
            };
            if (field.equals("properties-size")) fields.write(0x21);
            fields.writeBytes(bad);
            if (flags != 0) fields.writeBytes(new byte[]{0x21, 1, 0});
            else if (field.equals("filter-id")) fields.writeBytes(new byte[]{1, 0});
            else fields.write(0);
            byte[] header = header(32, flags, fields.toByteArray());
            assertRejected(stream(header, new byte[]{0}, 1, index(1, 33, 0)));
        }
    }

    /// Crosses all supported filter identifiers with property lengths around the accepted encodings.
    private static Stream<Arguments> propertyLengths() {
        return IntStream.concat(IntStream.rangeClosed(3, 11), IntStream.of(0x21)).boxed()
                .flatMap(id -> IntStream.rangeClosed(0, 5).mapToObj(size -> Arguments.of(id, size)));
    }

    /// Requires one byte for Delta/LZMA2 and zero or four bytes for BCJ properties.
    @ParameterizedTest
    @MethodSource("propertyLengths")
    void validatesFilterPropertyLengths(int identifier, int size) throws IOException {
        var fields = new ByteArrayOutputStream();
        fields.write(identifier);
        fields.write(size);
        fields.writeBytes(new byte[size]);
        if (identifier != 0x21) fields.writeBytes(new byte[]{0x21, 1, 0});
        byte[] header = header(32, identifier == 0x21 ? 0 : 1, fields.toByteArray());
        byte[] encoded = stream(header, new byte[]{0}, 1, index(1, 33, 0));
        boolean valid = identifier == 3 || identifier == 0x21 ? size == 1 : size == 0 || size == 4;
        if (valid) assertDecoded(encoded, new byte[0]);
        else assertRejected(encoded);
    }

    /// Distinguishes BCJ property parsing from the alignment checks required when initializing the filter.
    @ParameterizedTest
    @ValueSource(ints = {4, 5, 6, 7, 8, 9, 10, 11})
    void validatesBcjStartOffsets(int identifier) throws IOException {
        int alignment = switch (identifier) {
            case 4 -> 1;
            case 6 -> 16;
            case 8, 11 -> 2;
            default -> 4;
        };
        for (long offset : new long[]{0, 256, 257, 0xffff_ffffL, 0x1_0000_0000L - alignment}) {
            byte[] fields = {(byte) identifier, 4, 0, 0, 0, 0, 0x21, 1, 0};
            ByteArrayAccess.writeIntLittleEndian(fields, 2, (int) offset);
            byte[] header = header(32, 1, fields);
            byte[] encoded = stream(header, new byte[]{0}, 1, index(1, 33, 0));
            if (offset % alignment == 0) {
                assertDecoded(encoded, new byte[0]);
            } else {
                // XZ for Java 1.12 doesn't enforce the alignment required by XZ section 5.3.2.
                assertArkivoRejected(encoded);
            }
        }
    }

    /// Rejects declared compressed sizes whose header and integrity check cannot fit in a 63-bit unpadded size.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 4, 10})
    void validatesDeclaredUnpaddedSize(int check) throws IOException {
        int checkSize = switch (check) {
            case 0 -> 0;
            case 1 -> 4;
            case 4 -> 8;
            case 10 -> 32;
            default -> throw new AssertionError(check);
        };
        long maximum = Long.MAX_VALUE - 32 - checkSize;
        for (long size : new long[]{maximum, maximum + 1, Long.MAX_VALUE}) {
            var fields = new ByteArrayOutputStream();
            writeVli(fields, size);
            fields.writeBytes(new byte[]{0x21, 1, 0});
            var prefix = new ByteArrayOutputStream();
            prefix.writeBytes(streamHeader(check));
            prefix.writeBytes(header(32, 0x40, fields.toByteArray()));
            try (var decoder = new XZDecoder(4096, true)) {
                ByteBuffer source = ByteBuffer.wrap(prefix.toByteArray());
                ByteBuffer target = ByteBuffer.allocate(1);
                if (size == maximum) {
                    assertEquals(CodecOutcome.NEEDS_INPUT, decoder.decode(source, target));
                } else {
                    assertThrows(IOException.class, () -> decoder.decode(source, target));
                }
                assertEquals(0, target.position());
                assertEquals(source.limit(), source.position());
            }
        }
    }

    /// Tests Index record counts on both sides of the first VLI length transition.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 127, 128, 129})
    void decodesIndexRecordCounts(int count) throws IOException {
        byte[] header = header(12, 0, new byte[]{0x21, 1, 0});
        assertDecoded(stream(header, new byte[]{0}, count, index(count, 13, 0)), new byte[0]);
    }

    /// Checks unpadded and uncompressed Index fields across one-, two-, and three-byte VLI encodings.
    @ParameterizedTest
    @ValueSource(ints = {1, 110, 111, 112, 127, 128, 16367, 16368, 16383, 16384})
    void decodesIndexRecordSizes(int size) throws IOException {
        byte[] payload = new byte[size + 4];
        payload[0] = 1; // LZMA2 uncompressed chunk with a dictionary reset.
        payload[1] = (byte) ((size - 1) >>> 8);
        payload[2] = (byte) (size - 1);
        for (int i = 0; i < size; i++) payload[i + 3] = (byte) (i * 17);
        byte[] header = header(12, 0, new byte[]{0x21, 1, 0});
        byte[] encoded = stream(header, payload, 1, index(1, header.length + payload.length, size));
        assertDecoded(encoded, Arrays.copyOfRange(payload, 3, 3 + size));
    }

    /// Rejects malformed VLIs separately in the record count, unpadded size, and uncompressed size fields.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void rejectsMalformedIndexFields(int field) throws IOException {
        byte[] header = header(12, 0, new byte[]{0x21, 1, 0});
        byte[] continued = new byte[9];
        Arrays.fill(continued, (byte) 0x80);
        byte[] maximum = new byte[9];
        Arrays.fill(maximum, (byte) 0xff);
        maximum[8] = 0x7f;
        for (byte[] bad : new byte[][]{{(byte) 0x80, 0}, continued, maximum}) {
            var records = new ByteArrayOutputStream();
            records.write(0);
            for (int i = 0; i < 3; i++) {
                if (i == field) records.writeBytes(bad);
                else records.write(i == 0 ? 1 : i == 1 ? 13 : 0);
            }
            assertRejected(stream(header, new byte[]{0}, 1, records.toByteArray()));
        }
    }

    /// Requires every Index and Footer byte, then reuses the decoder for an empty and a nonempty Index.
    @Test
    void rejectsTruncatedIndexAndResets() throws IOException {
        byte[] header = header(12, 0, new byte[]{0x21, 1, 0});
        byte[] encoded = stream(header, new byte[]{0}, 2, index(2, 13, 0));
        byte[] empty = stream(header, new byte[]{0}, 0, index(0, 13, 0));
        try (var decoder = new XZDecoder(4096, true)) {
            for (int length = 12 + 2 * 16; length < encoded.length; length++) {
                ByteBuffer source = ByteBuffer.wrap(Arrays.copyOf(encoded, length));
                assertThrows(IOException.class, () -> decoder.finish(source, ByteBuffer.allocate(1)));
                decoder.reset();
                assertEquals(CodecOutcome.FINISHED, decoder.finish(ByteBuffer.wrap(empty), ByteBuffer.allocate(1)));
                decoder.reset();
                assertEquals(CodecOutcome.FINISHED, decoder.finish(ByteBuffer.wrap(encoded), ByteBuffer.allocate(1)));
                decoder.reset();
            }
        }
    }

    /// Compares complete plaintext with XZ for Java and verifies fragmented engine progress and frame boundaries.
    private static void assertDecoded(byte[] encoded, byte[] expected) throws IOException {
        try (var reference = new XZInputStream(new ByteArrayInputStream(encoded))) {
            assertArrayEquals(expected, reference.readAllBytes());
        }
        for (int fragment : new int[]{1, 7, encoded.length + 3}) {
            byte[] framed = Arrays.copyOf(encoded, encoded.length + 3);
            Arrays.fill(framed, encoded.length, framed.length, (byte) 0x5a);
            @UnmodifiableView ByteBuffer source = ByteBuffer.allocateDirect(framed.length).put(framed).flip().asReadOnlyBuffer();
            source.limit(0);
            var output = new ByteArrayOutputStream();
            try (var decoder = new XZDecoder(4096, true)) {
                CodecOutcome outcome = CodecOutcome.NEEDS_INPUT;
                int steps = 0;
                while (outcome != CodecOutcome.FINISHED) {
                    assertTrue(++steps <= encoded.length * 4 + expected.length + 16, "Decoder failed to finish");
                    if (outcome == CodecOutcome.NEEDS_INPUT) {
                        assertTrue(source.limit() < framed.length);
                        source.limit(Math.min(framed.length, source.limit() + fragment));
                    }
                    int before = source.position();
                    ByteBuffer target = ByteBuffer.allocate(17);
                    outcome = decoder.decode(source, target);
                    output.write(target.array(), 0, target.position());
                    assertTrue(source.position() > before || target.position() > 0 || outcome == CodecOutcome.FINISHED);
                }
                assertEquals(encoded.length, source.position());
                assertArrayEquals(expected, output.toByteArray());
                source.limit(framed.length);
                assertEquals(CodecOutcome.FINISHED, decoder.decode(source, ByteBuffer.allocate(1)));
                assertEquals(encoded.length, source.position());
            }
        }
    }

    /// Requires independent decoders to reject corrupt metadata even when its CRC has been recomputed.
    private static void assertRejected(byte[] encoded) throws IOException {
        assertThrows(IOException.class, () -> {
            try (var reference = new XZInputStream(new ByteArrayInputStream(encoded))) {
                reference.readAllBytes();
            }
        });
        assertArkivoRejected(encoded);
    }

    /// Requires checked rejection with whole input and at every one-byte metadata boundary.
    private static void assertArkivoRejected(byte[] encoded) throws IOException {
        for (int fragment : new int[]{1, encoded.length}) {
            try (var decoder = new XZDecoder(4096, true)) {
                @UnmodifiableView ByteBuffer source = ByteBuffer.wrap(encoded).asReadOnlyBuffer();
                source.limit(0);
                assertThrows(IOException.class, () -> {
                    for (int step = 0; step <= encoded.length + 1; step++) {
                        source.limit(Math.min(encoded.length, source.limit() + fragment));
                        CodecOutcome outcome = source.limit() == encoded.length
                                ? decoder.finish(source, ByteBuffer.allocate(17))
                                : decoder.decode(source, ByteBuffer.allocate(17));
                        if (outcome == CodecOutcome.FINISHED) throw new AssertionError("Corrupt stream accepted");
                    }
                    throw new AssertionError("Corrupt stream made no progress");
                });
            }
        }
    }

    /// Creates a Block Header without invoking the production header or VLI encoders.
    private static byte[] header(int size, int flags, byte[] fields) {
        assertTrue(size >= 8 && size <= 1024 && size % 4 == 0);
        assertTrue(fields.length <= size - 6);
        byte[] result = new byte[size];
        result[0] = (byte) (size / 4 - 1);
        result[1] = (byte) flags;
        System.arraycopy(fields, 0, result, 2, fields.length);
        rewriteCrc(result, 0, size - 4, size - 4);
        return result;
    }

    /// Creates a Stream Header with the selected integrity check identifier.
    private static byte[] streamHeader(int check) {
        byte[] header = {(byte) 0xfd, '7', 'z', 'X', 'Z', 0, 0, (byte) check, 0, 0, 0, 0};
        rewriteCrc(header, 6, 2, 8);
        return header;
    }

    /// Creates repeated unchecked Blocks followed by a caller-supplied Index body and matching Footer.
    private static byte[] stream(byte[] header, byte[] payload, int count, byte[] index) {
        var result = new ByteArrayOutputStream();
        result.writeBytes(streamHeader(0));
        for (int i = 0; i < count; i++) {
            result.writeBytes(header);
            result.writeBytes(payload);
            while (result.size() % 4 != 0) result.write(0);
        }
        byte[] paddedIndex = Arrays.copyOf(index, ((index.length + 3) & ~3) + 4);
        rewriteCrc(paddedIndex, 0, paddedIndex.length - 4, paddedIndex.length - 4);
        result.writeBytes(paddedIndex);
        byte[] footer = new byte[12];
        ByteArrayAccess.writeIntLittleEndian(footer, 4, paddedIndex.length / 4 - 1);
        footer[10] = 'Y';
        footer[11] = 'Z';
        rewriteCrc(footer, 4, 6, 0);
        result.writeBytes(footer);
        return result.toByteArray();
    }

    /// Encodes identical Index records without their padding or CRC.
    private static byte[] index(int count, long unpaddedSize, long uncompressedSize) {
        var result = new ByteArrayOutputStream();
        result.write(0);
        writeVli(result, count);
        for (int i = 0; i < count; i++) {
            writeVli(result, unpaddedSize);
            writeVli(result, uncompressedSize);
        }
        return result.toByteArray();
    }

    /// Writes an independently encoded unsigned 63-bit integer in canonical base-128 form.
    private static void writeVli(ByteArrayOutputStream output, long value) {
        assertTrue(value >= 0);
        do {
            int low = (int) (value & 0x7f);
            value >>>= 7;
            output.write(low | (value == 0 ? 0 : 0x80));
        } while (value != 0);
    }

    /// Stores the JDK CRC-32 of a selected region in little-endian form.
    private static void rewriteCrc(byte[] bytes, int offset, int length, int destination) {
        var crc = new CRC32();
        crc.update(bytes, offset, length);
        ByteArrayAccess.writeIntLittleEndian(bytes, destination, (int) crc.getValue());
    }
}
