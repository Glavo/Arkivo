// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemProvider;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the ZIP-specific legacy metadata decoder contract.
@NotNullByDefault
public final class ZipLegacyMetadataDecoderTest {
    /// Checks the creator-system, version, attribute, and header-source branches independently.
    @ParameterizedTest
    @CsvSource({
            "0, 20, 0, CENTRAL_DIRECTORY, true",
            "0, 24, 65536, CENTRAL_DIRECTORY, true",
            "0, 25, 0, CENTRAL_DIRECTORY, true",
            "0, 25, 65535, CENTRAL_DIRECTORY, true",
            "0, 25, 65536, CENTRAL_DIRECTORY, false",
            "0, 26, 65536, CENTRAL_DIRECTORY, false",
            "0, 27, 65536, CENTRAL_DIRECTORY, true",
            "0, 39, 65536, CENTRAL_DIRECTORY, true",
            "0, 40, 65536, CENTRAL_DIRECTORY, false",
            "0, 41, 65536, CENTRAL_DIRECTORY, true",
            "0, 25, -1, CENTRAL_DIRECTORY, true",
            "0, 25, 0, LOCAL_FILE_HEADER, false",
            "0, 26, -1, LOCAL_FILE_HEADER, false",
            "0, 40, 0, LOCAL_FILE_HEADER, false",
            "0, 20, 65536, LOCAL_FILE_HEADER, true",
            "6, 25, 65536, CENTRAL_DIRECTORY, true",
            "10, 50, 0, CENTRAL_DIRECTORY, true",
            "10, 49, 0, CENTRAL_DIRECTORY, false",
            "10, 51, 0, CENTRAL_DIRECTORY, false",
            "11, 50, 0, CENTRAL_DIRECTORY, true",
            "11, 20, 0, CENTRAL_DIRECTORY, false",
            "3, 20, 65536, CENTRAL_DIRECTORY, false",
            "7, 20, 0, CENTRAL_DIRECTORY, false",
            "255, 255, 0, CENTRAL_DIRECTORY, false"
    })
    public void codePageRules(int system, int version, long attributes,
                             ZipLegacyMetadataDecoder.HeaderSource source, boolean oem) throws IOException {
        var decoder = ZipLegacyMetadataDecoder.forCodePages(Charset.forName("IBM437"),
                Charset.forName("windows-1252"));
        var context = new ZipLegacyMetadataDecoder.Context(ByteBuffer.wrap(new byte[]{(byte) 0x82}),
                ZipLegacyMetadataDecoder.MetadataKind.ENTRY_NAME, source, 0, 63,
                system << 8 | version, attributes, ByteBuffer.allocate(0));
        assertEquals(oem ? "\u00e9" : "\u201a", decoder.decode(context));
    }

    /// Does not infer a creator from extraction-version bytes or unrelated extra fields.
    @Test
    public void unavailableCreatorUsesConfiguredOem() throws IOException {
        var decoder = ZipLegacyMetadataDecoder.forCodePages(Charset.forName("IBM866"), StandardCharsets.US_ASCII);
        byte[] bytes = "\u041f\u0440\u0438\u0432\u0435\u0442".getBytes(Charset.forName("IBM866"));
        assertEquals("\u041f\u0440\u0438\u0432\u0435\u0442", decoder.decode(bytes));
        for (var source : ZipLegacyMetadataDecoder.HeaderSource.values()) {
            var context = new ZipLegacyMetadataDecoder.Context(ByteBuffer.wrap(bytes),
                    ZipLegacyMetadataDecoder.MetadataKind.ENTRY_NAME, source, 0, 3 << 8 | 25,
                    -1, 0xffff_ffffL, ByteBuffer.wrap(new byte[]{0x55, 0x58, 0, 0}));
            assertEquals("\u041f\u0440\u0438\u0432\u0435\u0442", decoder.decode(context));
        }
    }

    /// Uses configured charsets strictly and leaves both input views unchanged, including after failure.
    @Test
    public void codePagesAreStrictAndPreserveBuffers() throws IOException {
        assertThrows(NullPointerException.class,
                () -> ZipLegacyMetadataDecoder.forCodePages(null, StandardCharsets.US_ASCII));
        assertThrows(NullPointerException.class,
                () -> ZipLegacyMetadataDecoder.forCodePages(StandardCharsets.US_ASCII, null));
        var decoder = ZipLegacyMetadataDecoder.forCodePages(StandardCharsets.ISO_8859_1, StandardCharsets.US_ASCII);
        for (ByteBuffer bytes : new ByteBuffer[]{ByteBuffer.allocate(3), ByteBuffer.allocateDirect(3),
                ByteBuffer.allocate(5).position(2).slice()}) {
            bytes.put(new byte[]{0, (byte) 0xe9, 0}).position(1).limit(2).mark();
            var context = new ZipLegacyMetadataDecoder.Context(bytes.asReadOnlyBuffer(),
                    ZipLegacyMetadataDecoder.MetadataKind.ENTRY_NAME,
                    ZipLegacyMetadataDecoder.HeaderSource.CENTRAL_DIRECTORY, 0, 20, 10 << 8 | 20,
                    0, ByteBuffer.wrap(new byte[]{0, 1}).position(1));
            context.bytes().mark();
            context.extraData().mark();
            assertThrows(CharacterCodingException.class, () -> decoder.decode(context));
            assertEquals(1, context.bytes().position());
            assertEquals(2, context.bytes().limit());
            context.bytes().reset();
            assertEquals(1, context.extraData().position());
            context.extraData().reset();
            assertEquals("\u00e9", decoder.decode(bytes));
            assertEquals(1, bytes.position());
            bytes.reset();
        }
    }

    /// Carries central attributes through indexed lookup, NIO configuration, and unchanged-record updates.
    @Test
    public void configuredCodePagesAcrossReadersAndUpdates(@TempDir Path directory) throws IOException {
        var decoder = ZipLegacyMetadataDecoder.forCodePages(Charset.forName("IBM437"),
                Charset.forName("windows-1252"));
        byte[] archive = legacyArchive();
        int centralOffset = ByteArrayAccess.readIntLittleEndian(archive, archive.length - 6);
        ByteArrayAccess.writeShortLittleEndian(archive, centralOffset + 4, (short) 25);
        ByteArrayAccess.writeIntLittleEndian(archive, centralOffset + 38, 0x81a4_0000);
        Path file = directory.resolve("code-pages.zip");
        Files.write(file, archive);
        String oemName = new String("legacy-\u00e9.txt".getBytes(StandardCharsets.ISO_8859_1), Charset.forName("IBM437"));
        try (var fs = ZipArkivoFileSystem.open(file)) {
            assertTrue(Files.exists(fs.getPath(oemName)));
        }
        try (var fs = ZipArkivoFileSystemProvider.instance().newFileSystem(file,
                Map.of("arkivo.zip.legacyMetadataDecoder", decoder))) {
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(fs.getPath("legacy-\u00e9.txt")));
        }
        var read = ZipArchiveOptions.READ_DEFAULTS.withLegacyMetadataDecoder(decoder);
        try (var fs = ZipArkivoFileSystem.open(file, read)) {
            var attributes = Files.readAttributes(fs.getPath("legacy-\u00e9.txt"), ZipArkivoEntryAttributes.class);
            assertEquals("\u00e9", attributes.comment());
            assertEquals(0x81a4_0000L, attributes.externalAttributes());
        }
        try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive), read)) {
            assertTrue(reader.next());
            assertEquals(oemName, reader.readAttributes().path());
        }
        try (var fs = ZipArkivoFileSystem.update(file,
                ZipArchiveOptions.UPDATE_DEFAULTS.withLegacyMetadataDecoder(decoder))) {
            assertEquals("\u00e9", Files.readAttributes(fs.getPath("legacy-\u00e9.txt"),
                    ZipArkivoEntryAttributes.class).comment());
            Files.write(fs.getPath("new.txt"), new byte[]{4});
        }
        try (var fs = ZipArkivoFileSystem.open(file, read)) {
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(fs.getPath("legacy-\u00e9.txt")));
            assertArrayEquals(new byte[]{4}, Files.readAllBytes(fs.getPath("new.txt")));
            assertEquals("\u00e9", Files.readAttributes(fs.getPath("legacy-\u00e9.txt"),
                    ZipArkivoEntryAttributes.class).comment());
        }
    }

    /// Keeps concurrently decoded OEM and ANSI values independent across shared policy invocations.
    @Test
    public void sharedCodePagePolicy() {
        var decoder = ZipLegacyMetadataDecoder.forCodePages(Charset.forName("IBM437"),
                Charset.forName("windows-1252"));
        IntStream.range(0, 256).parallel().forEach(index -> {
            boolean oem = index % 2 == 0;
            var context = new ZipLegacyMetadataDecoder.Context(ByteBuffer.wrap(new byte[]{(byte) 0x82}),
                    ZipLegacyMetadataDecoder.MetadataKind.ENTRY_COMMENT,
                    ZipLegacyMetadataDecoder.HeaderSource.CENTRAL_DIRECTORY, 0, 20,
                    oem ? 20 : 10 << 8 | 20, 0, ByteBuffer.allocate(0));
            try {
                assertEquals(oem ? "\u00e9" : "\u201a", decoder.decode(context));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        });
    }

    /// Validates the complete unsigned external-attribute range and the unknown sentinel.
    @Test
    public void externalAttributeRange() {
        for (long value : new long[]{-1, 0, 0xffff_ffffL}) {
            assertEquals(value, contextWithAttributes(value).externalAttributes());
        }
        for (long value : new long[]{-2, 0x1_0000_0000L, Long.MIN_VALUE, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> contextWithAttributes(value));
        }
    }

    /// Creates an otherwise unknown context with the supplied external attributes.
    private static ZipLegacyMetadataDecoder.Context contextWithAttributes(long attributes) {
        return new ZipLegacyMetadataDecoder.Context(ByteBuffer.allocate(0),
                ZipLegacyMetadataDecoder.MetadataKind.UNKNOWN, ZipLegacyMetadataDecoder.HeaderSource.UNKNOWN,
                -1, -1, -1, attributes, ByteBuffer.allocate(0));
    }

    /// Uses custom text for lookup without discarding raw metadata in either ZIP reading mode.
    @Test
    public void customTextAndRawBytes(@TempDir Path directory) throws IOException {
        byte[] archive = legacyArchive();
        Path file = directory.resolve("legacy.zip");
        Files.write(file, archive);
        var seen = EnumSet.noneOf(ZipLegacyMetadataDecoder.HeaderSource.class);
        ZipLegacyMetadataDecoder decoder = context -> {
            assertTrue(context.bytes().isReadOnly());
            byte[] bytes = new byte[context.bytes().remaining()];
            context.bytes().get(bytes);
            seen.add(context.headerSource());
            if (context.metadataKind() == ZipLegacyMetadataDecoder.MetadataKind.ENTRY_COMMENT) {
                assertArrayEquals(new byte[]{(byte) 0xe9}, bytes);
                return "decoded comment";
            }
            assertEquals(ZipLegacyMetadataDecoder.MetadataKind.ENTRY_NAME, context.metadataKind());
            assertArrayEquals("legacy-\u00e9.txt".getBytes(StandardCharsets.ISO_8859_1), bytes);
            return "restored.txt";
        };
        var options = ZipArchiveOptions.READ_DEFAULTS.withLegacyMetadataDecoder(decoder);
        try (var fs = ZipArkivoFileSystem.open(file, options)) {
            var attributes = Files.readAttributes(fs.getPath("restored.txt"), ZipArkivoEntryAttributes.class);
            assertArrayEquals("legacy-\u00e9.txt".getBytes(StandardCharsets.ISO_8859_1), attributes.rawPath());
            assertEquals("decoded comment", attributes.comment());
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(fs.getPath("restored.txt")));
        }
        try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive), options)) {
            assertTrue(reader.next());
            assertEquals("restored.txt", reader.readAttributes().path());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(new byte[]{1, 2, 3}, input.readAllBytes());
            }
            assertFalse(reader.next());
        }
        assertEquals(EnumSet.of(ZipLegacyMetadataDecoder.HeaderSource.CENTRAL_DIRECTORY,
                ZipLegacyMetadataDecoder.HeaderSource.LOCAL_FILE_HEADER), seen);
    }

    /// Rejects unsafe custom paths and invalid null results without applying a fallback policy.
    @Test
    public void customResultsStillRequireValidPaths(@TempDir Path directory) throws IOException {
        byte[] archive = legacyArchive();
        Path file = directory.resolve("invalid.zip");
        Files.write(file, archive);
        for (String path : new String[]{"", "../escape", "/absolute", "C:/absolute"}) {
            var options = ZipArchiveOptions.READ_DEFAULTS.withLegacyMetadataDecoder(bytes -> path);
            try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive), options)) {
                assertThrows(IOException.class, reader::next);
            }
            assertThrows(IOException.class, () -> {
                try (var fs = ZipArkivoFileSystem.open(file, options);
                     var entries = Files.newDirectoryStream(fs.getPath("/"))) {
                    entries.iterator().hasNext();
                }
            });
        }
        for (ArchiveMetadataDecoder invalid : new ArchiveMetadataDecoder[]{bytes -> null,
                (ZipLegacyMetadataDecoder) context -> null}) {
            var options = ZipArchiveOptions.READ_DEFAULTS.withLegacyMetadataDecoder(invalid);
            try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive), options)) {
                assertThrows(NullPointerException.class, reader::next);
            }
        }
    }

    /// Preserves a policy's checked exception rather than trying another charset.
    @Test
    public void decoderFailureIsNotRetried() throws IOException {
        IOException failure = new IOException("Metadata rejected");
        var options = ZipArchiveOptions.READ_DEFAULTS.withLegacyMetadataDecoder(bytes -> { throw failure; });
        try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(legacyArchive()), options)) {
            assertSame(failure, assertThrows(IOException.class, reader::next));
        }
    }

    /// Creates a ZIP with non-UTF-8 name and comment bytes and an independently encoded stored body.
    private static byte[] legacyArchive() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes, StandardCharsets.ISO_8859_1)) {
            ZipEntry entry = new ZipEntry("legacy-\u00e9.txt");
            entry.setComment("\u00e9");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(3);
            CRC32 crc = new CRC32();
            crc.update(new byte[]{1, 2, 3});
            entry.setCrc(crc.getValue());
            output.putNextEntry(entry);
            output.write(new byte[]{1, 2, 3});
            output.closeEntry();
        }
        return bytes.toByteArray();
    }

    /// Verifies that basic invocations supply an unknown ZIP context with a read-only byte view.
    @Test
    public void basicInvocation() throws Exception {
        ByteBuffer source = ByteBuffer.wrap(new byte[]{0, 1, 2});
        source.position(1);
        source.mark();
        ZipLegacyMetadataDecoder metadataDecoder = context -> {
            assertTrue(context.bytes().isReadOnly());
            assertEquals(2, context.bytes().remaining());
            assertEquals(ZipLegacyMetadataDecoder.MetadataKind.UNKNOWN, context.metadataKind());
            assertEquals(ZipLegacyMetadataDecoder.HeaderSource.UNKNOWN, context.headerSource());
            assertEquals(ZipLegacyMetadataDecoder.UNKNOWN_HEADER_VALUE, context.generalPurposeFlags());
            assertEquals(ZipLegacyMetadataDecoder.UNKNOWN_HEADER_VALUE, context.versionNeededToExtract());
            assertEquals(ZipLegacyMetadataDecoder.UNKNOWN_HEADER_VALUE, context.versionMadeBy());
            assertEquals(ZipLegacyMetadataDecoder.UNKNOWN_HEADER_VALUE, context.externalAttributes());
            assertEquals(0, context.extraData().remaining());
            context.bytes().position(context.bytes().limit());
            return "decoded";
        };

        assertEquals("decoded", metadataDecoder.decode(source));
        assertEquals(1, source.position());
        source.reset();
        assertEquals(1, source.position());
    }

    /// Verifies that a central-directory context exposes creator and version fields without changing its buffers.
    @Test
    public void centralDirectoryContext() {
        ZipLegacyMetadataDecoder.Context context = new ZipLegacyMetadataDecoder.Context(
                ByteBuffer.wrap(new byte[]{1, 2}),
                ZipLegacyMetadataDecoder.MetadataKind.ENTRY_NAME,
                ZipLegacyMetadataDecoder.HeaderSource.CENTRAL_DIRECTORY,
                0x0002,
                20,
                3 << Byte.SIZE | 63,
                0x81a4_0000L,
                ByteBuffer.wrap(new byte[]{4, 5})
        );

        assertTrue(context.bytes().isReadOnly());
        assertTrue(context.extraData().isReadOnly());
        assertEquals(3, context.creatorSystem());
        assertEquals(63, context.creatorVersion());
        assertEquals(0x81a4_0000L, context.externalAttributes());
    }

    /// Verifies unavailable creator metadata and validates every unsigned-short header field.
    @Test
    public void validatesHeaderValueRanges() {
        ZipLegacyMetadataDecoder.Context unknown = context(-1, -1, -1);
        assertEquals(ZipLegacyMetadataDecoder.UNKNOWN_HEADER_VALUE, unknown.creatorSystem());
        assertEquals(ZipLegacyMetadataDecoder.UNKNOWN_HEADER_VALUE, unknown.creatorVersion());

        ZipLegacyMetadataDecoder.Context maximum = context(0xffff, 0xffff, 0xffff);
        assertEquals(0xff, maximum.creatorSystem());
        assertEquals(0xff, maximum.creatorVersion());

        assertInvalidHeaderValues(-2, -1, -1);
        assertInvalidHeaderValues(0x1_0000, -1, -1);
        assertInvalidHeaderValues(-1, -2, -1);
        assertInvalidHeaderValues(-1, 0x1_0000, -1);
        assertInvalidHeaderValues(-1, -1, -2);
        assertInvalidHeaderValues(-1, -1, 0x1_0000);
    }

    /// Requires construction to reject one invalid ZIP header-value combination.
    private static void assertInvalidHeaderValues(
            int generalPurposeFlags,
            int versionNeededToExtract,
            int versionMadeBy
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> context(generalPurposeFlags, versionNeededToExtract, versionMadeBy)
        );
    }

    /// Creates a minimal ZIP legacy metadata context with the requested integer fields.
    private static ZipLegacyMetadataDecoder.Context context(
            int generalPurposeFlags,
            int versionNeededToExtract,
            int versionMadeBy
    ) {
        return new ZipLegacyMetadataDecoder.Context(
                ByteBuffer.allocate(0),
                ZipLegacyMetadataDecoder.MetadataKind.ENTRY_NAME,
                ZipLegacyMetadataDecoder.HeaderSource.CENTRAL_DIRECTORY,
                generalPurposeFlags,
                versionNeededToExtract,
                versionMadeBy,
                ZipLegacyMetadataDecoder.UNKNOWN_HEADER_VALUE,
                ByteBuffer.allocate(0)
        );
    }
}
