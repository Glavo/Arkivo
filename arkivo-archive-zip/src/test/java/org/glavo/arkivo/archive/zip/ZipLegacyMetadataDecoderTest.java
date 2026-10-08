// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
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
                ByteBuffer.wrap(new byte[]{4, 5})
        );

        assertTrue(context.bytes().isReadOnly());
        assertTrue(context.extraData().isReadOnly());
        assertEquals(3, context.creatorSystem());
        assertEquals(63, context.creatorVersion());
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
                ByteBuffer.allocate(0)
        );
    }
}
