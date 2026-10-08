// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio;

import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry;
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream;
import org.apache.commons.compress.archivers.cpio.CpioArchiveOutputStream;
import org.apache.commons.compress.archivers.cpio.CpioConstants;
import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Replays GNU cpio's original malformed archive and cross-checks its input scenarios with Commons Compress.
@NotNullByDefault
final class GnuCpioRegressionTest {
    /// The second old-binary header begins after the first padded name and body.
    private static final int LINK_HEADER = 46;

    /// The original trailer follows the second member's four physical payload bytes.
    private static final int TRAILER_HEADER = 82;

    /// The regular file preceding the malformed link in the downloaded archive.
    private static final byte @Unmodifiable [] FILE_CONTENT = "some content\n".getBytes(StandardCharsets.US_ASCII);

    /// Reads only the Base64 data declaration, never the surrounding shell commands.
    private static byte[] reproducer() throws IOException {
        String source = Files.readString(root().resolve("tests/symlink-bad-length.at"));
        var matcher = Pattern.compile("AT_DATA\\(\\[ARCHIVE\\.base64\\],\\s*\\[([A-Za-z0-9+/=\\r\\n]+)\\]\\)")
                .matcher(source);
        assertTrue(matcher.find(), "Missing GNU cpio reproducer");
        byte[] result = Base64.getDecoder().decode(matcher.group(1).replace("\r", "").replace("\n", ""));
        assertFalse(matcher.find(), "Ambiguous GNU cpio reproducer");
        return result;
    }

    /// Verifies the independently measured identity of the unmodified crash input.
    @Test
    void verifiesOriginalReproducer() throws Exception {
        byte[] bytes = reproducer();
        assertEquals(512, bytes.length);
        assertEquals("73150ba98c86877e04ca8dcff691982199ddd5763bcc8b0c2dc276bd72a8979c",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        assertTrue(Files.readString(root().resolve("COPYING")).contains("GNU GENERAL PUBLIC LICENSE"));
        assertTrue(Files.readString(root().resolve("tests/symlink-long.at")).contains("DIRNAME(52)"));
        assertTrue(Files.readString(root().resolve("tests/genfile.c")).contains("fputc (i & 255, fp)"));
    }

    /// Covers each word byte order and short-read schedule for the fixed old-binary reproducer.
    private static Stream<Arguments> binaryConfigurations() {
        return Stream.of(CPIOBinaryByteOrder.values()).flatMap(order ->
                Stream.of(1, 7, 512).map(chunk -> Arguments.of(order, chunk)));
    }

    /// Checks unsigned sizes beyond 16-bit and signed 32-bit boundaries without allocating their claimed length.
    private static Stream<Arguments> oversizedLinks() {
        return Stream.of(CPIOBinaryByteOrder.values()).flatMap(order ->
                Stream.of(65535L, 65536L, 0x7fff_ffffL, 0x8000_0000L, 0xffff_fffeL, 0xffff_ffffL)
                        .flatMap(size -> Stream.of(0, 1, 2).map(drain -> Arguments.of(order, size, drain))));
    }

    /// Preserves the valid first member and reports truncation through explicit reads, close, or cursor advance.
    @ParameterizedTest(name = "{0}, size={1}, drain={2}")
    @MethodSource("oversizedLinks")
    void rejectsOversizedLink(CPIOBinaryByteOrder order, long size, int drain) throws Exception {
        byte[] bytes = binaryArchive(order, size);
        assertThrows(EOFException.class, () -> {
            try (var reader = CPIOArkivoStreamingReader.open(new ChunkedInput(bytes, 7))) {
                assertTrue(reader.next());
                assertEquals("FILE", reader.readAttributes().path());
                try (var body = reader.openInputStream()) {
                    assertArrayEquals(FILE_CONTENT, body.readAllBytes());
                }
                assertTrue(reader.next());
                var attributes = reader.readAttributes(CPIOArkivoEntryAttributes.class);
                assertEquals("LINK", attributes.path());
                assertTrue(attributes.isSymbolicLink());
                assertEquals(order, attributes.binaryByteOrder());
                assertEquals(size, attributes.size());
                if (drain == 2) {
                    reader.next();
                } else {
                    try (var body = reader.openInputStream()) {
                        if (drain == 0) {
                            // The physical remainder is bounded even when the header advertises almost 4 GiB.
                            assertTrue(body.readNBytes(bytes.length + 1).length <= bytes.length);
                        } else {
                            assertEquals('F', body.read());
                        }
                    }
                }
            }
        });
        var limits = ArchiveReadLimits.builder().maximumEntrySize(512).build();
        try (var reader = CPIOArkivoStreamingReader.open(new ChunkedInput(bytes, 1),
                CPIOArchiveOptions.READ_DEFAULTS.withCommon(ArchiveReadOptions.DEFAULT.withLimits(limits)))) {
            assertTrue(reader.next());
            var failure = assertThrows(ArkivoReadLimitException.class, reader::next);
            assertEquals(ArkivoReadLimitKind.ENTRY_SIZE, failure.kind());
            assertEquals(512, failure.maximum());
            assertEquals(size, failure.actual());
            assertEquals("LINK", failure.entryPath());
        }
    }

    /// Corrects only the faulty size and checks both byte orders against the original payload and an independent reader.
    @ParameterizedTest(name = "{0}, chunk={1}")
    @MethodSource("binaryConfigurations")
    void readsRepairedReproducerAndRejectsIncompletePrefixes(CPIOBinaryByteOrder order, int chunk) throws Exception {
        byte[] bytes = binaryArchive(order, 4);
        Map<String, byte[]> expected = new LinkedHashMap<>();
        expected.put("FILE", FILE_CONTENT);
        expected.put("LINK", "FILE".getBytes(StandardCharsets.US_ASCII));
        verifyArkivo(bytes, expected, chunk);
        verifyCommons(bytes, expected);
        // The 26-byte trailer header and 12-byte padded name end at byte 120.
        for (int end = 0; end < 120; end++) {
            byte[] prefix = Arrays.copyOf(bytes, end);
            assertThrows(IOException.class, () -> {
                try (var reader = CPIOArkivoStreamingReader.open(new ChunkedInput(prefix, chunk))) {
                    while (reader.next()) {
                        try (var body = reader.openInputStream()) {
                            body.transferTo(OutputStream.nullOutputStream());
                        }
                    }
                }
            }, "Truncated at " + end);
        }
        verifyArkivo(Arrays.copyOf(bytes, 120), expected, chunk);
    }

    /// Produces header variants without changing the names, payloads, padding, or original trailer.
    private static byte[] binaryArchive(CPIOBinaryByteOrder order, long size) throws IOException {
        byte[] bytes = reproducer();
        ByteArrayAccess.writeShortLittleEndian(bytes, LINK_HEADER + 22, (short) (size >>> 16));
        ByteArrayAccess.writeShortLittleEndian(bytes, LINK_HEADER + 24, (short) size);
        if (order == CPIOBinaryByteOrder.BIG_ENDIAN) {
            for (int start : new int[]{0, LINK_HEADER, TRAILER_HEADER}) {
                for (int offset = start; offset < start + 26; offset += 2) {
                    short word = ByteArrayAccess.readShortLittleEndian(bytes, offset);
                    ByteArrayAccess.writeShortBigEndian(bytes, offset, word);
                }
            }
        }
        return bytes;
    }

    /// Combines supported dialects and word orders with link lengths around input-buffer boundaries.
    private static Stream<Arguments> generatedConfigurations() {
        return Stream.of(CPIODialect.values()).flatMap(dialect ->
                (dialect == CPIODialect.OLD_BINARY ? Stream.of(CPIOBinaryByteOrder.values())
                        : Stream.of(CPIOBinaryByteOrder.BIG_ENDIAN)).flatMap(order ->
                        Stream.of(511, 512, 520, 8191, 8192, 8193)
                                .map(length -> Arguments.of(dialect, order, length))));
    }

    /// Adapts the upstream file list and long-link input without relying on the host's symlink privileges.
    @ParameterizedTest(name = "{0}, {1}, linkLength={2}")
    @MethodSource("generatedConfigurations")
    void crossChecksFileListAndLongLink(CPIODialect dialect, CPIOBinaryByteOrder order, int linkLength) throws Exception {
        Map<String, byte[]> expected = new LinkedHashMap<>();
        String source = Files.readString(root().resolve("tests/inout.at"));
        var matcher = Pattern.compile("(?s)AT_DATA\\(\\[filelist\\],\\[(.*?)\\]\\)").matcher(source);
        assertTrue(matcher.find());
        for (String line : matcher.group(1).strip().split("\\R")) {
            String[] fields = line.strip().split("\\s+");
            assertEquals(2, fields.length);
            byte[] body = new byte[Integer.parseInt(fields[1])];
            for (int i = 0; i < body.length; i++) body[i] = (byte) i;
            expected.put(fields[0], body);
        }
        assertEquals(8, expected.size());
        String link = ("xxxxxxxxx/".repeat((linkLength + 9) / 10)).substring(0, linkLength);
        expected.put("link", link.getBytes(StandardCharsets.US_ASCII));
        expected.put("after-link", new byte[]{0, (byte) 0xff, 1});
        ByteArrayOutputStream arkivo = new ByteArrayOutputStream();
        var options = CPIOArchiveOptions.CREATE_DEFAULTS.withDialect(dialect).withBinaryByteOrder(order);
        try (var writer = CPIOArkivoStreamingWriter.open(arkivo, options)) {
            for (var entry : expected.entrySet()) {
                if (entry.getKey().equals("link")) {
                    writer.beginSymbolicLink("link", link).close();
                } else {
                    try (var body = writer.beginFile(entry.getKey()).openOutputStream()) {
                        body.write(entry.getValue());
                    }
                }
            }
        }
        verifyCommons(arkivo.toByteArray(), expected);

        short format = switch (dialect) {
            case NEW_ASCII -> CpioConstants.FORMAT_NEW;
            case NEW_ASCII_CRC -> CpioConstants.FORMAT_NEW_CRC;
            case OLD_ASCII -> CpioConstants.FORMAT_OLD_ASCII;
            case OLD_BINARY -> CpioConstants.FORMAT_OLD_BINARY;
        };
        ByteArrayOutputStream reference = new ByteArrayOutputStream();
        try (var writer = new CpioArchiveOutputStream(reference, format)) {
            for (var entry : expected.entrySet()) {
                var header = new CpioArchiveEntry(format, entry.getKey(), entry.getValue().length);
                header.setMode(entry.getKey().equals("link") ? 0120777 : 0100644);
                if (dialect == CPIODialect.NEW_ASCII_CRC) {
                    long sum = 0;
                    for (byte value : entry.getValue()) sum += Byte.toUnsignedInt(value);
                    header.setChksum(sum);
                }
                writer.putArchiveEntry(header);
                writer.write(entry.getValue());
                writer.closeArchiveEntry();
            }
        }
        for (int chunk : new int[]{1, 7, 8192}) verifyArkivo(reference.toByteArray(), expected, chunk);
    }

    /// Checks full reads and implicit draining without losing the member after a long link.
    private static void verifyArkivo(byte @Unmodifiable [] bytes, Map<String, byte[]> expected, int chunk)
            throws IOException {
        for (int drain = 0; drain < 3; drain++) {
            try (var reader = CPIOArkivoStreamingReader.open(new ChunkedInput(bytes, chunk))) {
                for (var entry : expected.entrySet()) {
                    assertTrue(reader.next());
                    var attributes = reader.readAttributes();
                    assertEquals(entry.getKey(), attributes.path());
                    assertEquals(entry.getValue().length, attributes.size());
                    assertEquals(entry.getKey().equals("LINK") || entry.getKey().equals("link"), attributes.isSymbolicLink());
                    if (drain != 2) {
                        try (var body = reader.openInputStream()) {
                            if (drain == 0) assertArrayEquals(entry.getValue(), body.readAllBytes());
                            else assertEquals(entry.getValue().length == 0 ? -1 : entry.getValue()[0] & 0xff, body.read());
                        }
                    }
                }
                assertFalse(reader.next());
                assertFalse(reader.next());
            }
        }
    }

    /// Uses an independent parser to verify names, kinds, complete bodies, and the required trailer.
    private static void verifyCommons(byte @Unmodifiable [] bytes, Map<String, byte[]> expected) throws IOException {
        try (var reader = new CpioArchiveInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry : expected.entrySet()) {
                var attributes = Objects.requireNonNull(reader.getNextEntry());
                assertEquals(entry.getKey(), attributes.getName());
                assertEquals(entry.getKey().equals("LINK") || entry.getKey().equals("link"), attributes.isSymbolicLink());
                assertArrayEquals(entry.getValue(), reader.readAllBytes());
            }
            assertNull(reader.getNextEntry());
        }
    }

    /// Returns the verified GNU source subset extracted by Gradle.
    private static Path root() {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo.gnuCpio.testDataDirectory")));
    }

    /// Bounds both bulk reads and skips so skipped members exercise incremental draining.
    @NotNullByDefault
    private static final class ChunkedInput extends ByteArrayInputStream {
        /// The maximum progress per bulk operation.
        private final int chunk;

        /// Creates a short-reading view of the supplied archive.
        private ChunkedInput(byte @Unmodifiable [] bytes, int chunk) {
            super(bytes);
            this.chunk = chunk;
        }

        @Override
        public synchronized int read(byte[] target, int offset, int length) {
            return super.read(target, offset, Math.min(length, chunk));
        }

        @Override
        public synchronized long skip(long count) {
            return super.skip(Math.min(count, chunk));
        }
    }
}
