// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipLegacyMetadataDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests raw ZIP entry name decoding.
@NotNullByDefault
public final class ZipEntryNameDecoderTest {
    /// Explicit Unicode metadata bypasses compatibility rules, including when Unicode decoding fails.
    @Test
    public void codePageCompatibilityDoesNotOverrideUnicode() throws IOException {
        var decoder = new ZipEntryNameDecoder(ZipLegacyMetadataDecoder.forCodePages(
                StandardCharsets.US_ASCII, StandardCharsets.US_ASCII));
        byte[] utf8 = "\u00e9.txt".getBytes(StandardCharsets.UTF_8);
        assertEquals("\u00e9.txt", decoder.decodePath(utf8, ZipEntryNameDecoder.UTF_8_FLAG,
                new byte[0], ZipLegacyMetadataDecoder.HeaderSource.CENTRAL_DIRECTORY, 20, 25, 0x81a4_0000L));
        byte[] legacy = {(byte) 0x82};
        assertEquals("\u00e9.txt", decoder.decodePath(legacy, 0,
                unicodeExtraField(ZipEntryNameDecoder.UNICODE_PATH_EXTRA_FIELD_ID, legacy, "\u00e9.txt"),
                ZipLegacyMetadataDecoder.HeaderSource.CENTRAL_DIRECTORY, 20, 25, 0x81a4_0000L));
        assertEquals("\u00e9.txt", decoder.decodeComment(legacy, 0,
                unicodeExtraField(ZipEntryNameDecoder.UNICODE_COMMENT_EXTRA_FIELD_ID, legacy, "\u00e9.txt"),
                ZipLegacyMetadataDecoder.HeaderSource.CENTRAL_DIRECTORY, 20, 25, 0x81a4_0000L));
        assertThrows(CharacterCodingException.class, () -> decoder.decodePath(legacy,
                ZipEntryNameDecoder.UTF_8_FLAG, new byte[0],
                ZipLegacyMetadataDecoder.HeaderSource.CENTRAL_DIRECTORY, 20, 25, 0x81a4_0000L));
    }

    /// Verifies that the UTF-8 general purpose bit flag selects strict UTF-8 decoding.
    @Test
    public void utf8Flag() throws Exception {
        ZipEntryNameDecoder decoder = decoderWithUnusedDecoder();
        byte[] rawPath = "目录/文件.txt".getBytes(StandardCharsets.UTF_8);

        String path = decoder.decodePath(rawPath, ZipEntryNameDecoder.UTF_8_FLAG, new byte[0]);

        assertEquals("目录/文件.txt", path);
    }

    /// Verifies that a valid Info-ZIP Unicode Path Extra Field overrides fallback decoding.
    @Test
    public void unicodePathExtraField() throws Exception {
        ZipEntryNameDecoder decoder = decoderWithUnusedDecoder();
        byte[] rawPath = "目录/文件.txt".getBytes(Charset.forName("GB18030"));
        byte[] extraData = unicodeExtraField(
                ZipEntryNameDecoder.UNICODE_PATH_EXTRA_FIELD_ID,
                rawPath,
                "目录/文件.txt"
        );

        String path = decoder.decodePath(rawPath, 0, extraData);

        assertEquals("目录/文件.txt", path);
    }

    /// Verifies that invalid Unicode extra fields do not hide later valid Unicode metadata.
    @Test
    public void unicodePathExtraFieldUsesLaterValidRecord() throws Exception {
        ZipEntryNameDecoder decoder = decoderWithUnusedDecoder();
        byte[] rawPath = "fallback.txt".getBytes(StandardCharsets.US_ASCII);
        byte[] invalidExtraData = unicodeExtraField(
                ZipEntryNameDecoder.UNICODE_PATH_EXTRA_FIELD_ID,
                "other.txt".getBytes(StandardCharsets.US_ASCII),
                "ignored.txt"
        );
        byte[] validExtraData = unicodeExtraField(
                ZipEntryNameDecoder.UNICODE_PATH_EXTRA_FIELD_ID,
                rawPath,
                "目录/文件.txt"
        );
        byte[] extraData = concatenate(invalidExtraData, validExtraData);

        String path = decoder.decodePath(rawPath, 0, extraData);

        assertEquals("目录/文件.txt", path);
    }

    /// Verifies that malformed extra field lengths are rejected before fallback decoding.
    @Test
    public void malformedExtraFieldLength() {
        ZipEntryNameDecoder decoder = decoderWithUnusedDecoder();
        byte[] rawPath = "fallback.txt".getBytes(StandardCharsets.UTF_8);

        IOException exception = assertThrows(
                IOException.class,
                () -> decoder.decodePath(rawPath, ZipEntryNameDecoder.UTF_8_FLAG, new byte[]{1, 0, 2, 0, 0})
        );

        assertEquals(true, exception.getMessage().contains("Invalid ZIP extra field length"));
    }

    /// Verifies that a custom decoder can select GB18030 for legacy entry metadata.
    @Test
    public void customDecoderSelectsGb18030() throws Exception {
        Charset gb18030 = Charset.forName("GB18030");
        ZipEntryNameDecoder decoder = new ZipEntryNameDecoder(bytes -> {
            assertEquals(true, bytes.isReadOnly());
            return ArchiveMetadataDecoder.forCharset(gb18030).decode(bytes);
        });
        byte[] rawPath = "目录/文件.txt".getBytes(gb18030);

        String path = decoder.decodePath(rawPath, 0, new byte[0]);

        assertEquals("目录/文件.txt", path);
    }

    /// Verifies that ZIP-specific decoders receive central-directory metadata only on the legacy decoding path.
    @Test
    public void zipDecoderReceivesCentralDirectoryContext() throws Exception {
        Charset gb18030 = Charset.forName("GB18030");
        ZipLegacyMetadataDecoder metadataDecoder = context -> {
            assertEquals(ZipLegacyMetadataDecoder.MetadataKind.ENTRY_NAME, context.metadataKind());
            assertEquals(
                    ZipLegacyMetadataDecoder.HeaderSource.CENTRAL_DIRECTORY,
                    context.headerSource()
            );
            assertEquals(0x0002, context.generalPurposeFlags());
            assertEquals(20, context.versionNeededToExtract());
            assertEquals(3, context.creatorSystem());
            assertEquals(63, context.creatorVersion());
            assertEquals(0x81a4_0000L, context.externalAttributes());
            assertEquals(0, context.extraData().remaining());
            return ArchiveMetadataDecoder.forCharset(gb18030).decode(context.bytes());
        };
        ZipEntryNameDecoder decoder = new ZipEntryNameDecoder(metadataDecoder);
        byte[] rawPath = "目录/文件.txt".getBytes(gb18030);

        String path = decoder.decodePath(
                rawPath,
                0x0002,
                new byte[0],
                ZipLegacyMetadataDecoder.HeaderSource.CENTRAL_DIRECTORY,
                20,
                3 << Byte.SIZE | 63,
                0x81a4_0000L
        );

        assertEquals("目录/文件.txt", path);
    }

    /// Verifies that the default policy decodes legacy names as CP437.
    @Test
    public void defaultDecoderUsesCp437() throws Exception {
        Charset cp437 = Charset.forName("IBM437");
        byte[] rawPath = "München.txt".getBytes(cp437);
        ZipEntryNameDecoder decoder = new ZipEntryNameDecoder(ZipArchiveOptions.DEFAULT_LEGACY_METADATA_DECODER);

        assertEquals("München.txt", decoder.decodePath(rawPath, 0, new byte[0]));
    }

    /// ASCII metadata still consults the decoder and obeys non-ASCII-compatible charsets.
    @Test
    public void asciiBytesRespectSelectedCharset() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ZipEntryNameDecoder decoder = new ZipEntryNameDecoder(bytes -> {
            calls.incrementAndGet();
            return ArchiveMetadataDecoder.forCharset(StandardCharsets.UTF_16BE).decode(bytes);
        });
        byte[] raw = {0, 'A', 0, 'B'};
        assertEquals("AB", decoder.decodePath(raw, 0, new byte[0]));
        assertEquals("AB", decoder.decodeComment(raw, 0, new byte[0]));
        assertEquals(2, calls.get());
        decoder = new ZipEntryNameDecoder(bytes -> {
            calls.incrementAndGet();
            return ZipArchiveOptions.DEFAULT_LEGACY_METADATA_DECODER.decode(bytes);
        });
        assertEquals("entry.txt", decoder.decodePath("entry.txt".getBytes(StandardCharsets.US_ASCII), 0, new byte[0]));
        assertEquals(3, calls.get());
    }

    /// Built-in single-byte mappings and UTF-8 agree with strict charset decoding for every byte value.
    @Test
    public void byteValuesMatchStrictDecoding() throws Exception {
        for (Charset charset : new Charset[]{StandardCharsets.UTF_8, StandardCharsets.US_ASCII,
                StandardCharsets.ISO_8859_1, Charset.forName("IBM437")}) {
            ZipEntryNameDecoder decoder = new ZipEntryNameDecoder(ArchiveMetadataDecoder.forCharset(charset));
            for (int value = 0; value < 256; value++) {
                byte[] raw = {'a', (byte) value, 'z'};
                String expected;
                try {
                    expected = charset.newDecoder().decode(ByteBuffer.wrap(raw)).toString();
                } catch (CharacterCodingException exception) {
                    assertThrows(CharacterCodingException.class, () -> decoder.decodePath(raw, 0, new byte[0]));
                    assertThrows(IOException.class, () -> decoder.decodeComment(raw, 0, new byte[0]));
                    continue;
                }
                assertEquals(expected, decoder.decodePath(raw, 0, new byte[0]));
                assertEquals(expected, decoder.decodeComment(raw, 0, new byte[0]));
            }
        }
    }

    /// An ASCII prefix does not hide overlong sequences, surrogate encodings, or truncated UTF-8 tails.
    @Test
    public void malformedUtf8AfterAsciiPrefix() {
        ZipEntryNameDecoder decoder = decoderWithUnusedDecoder();
        byte[] prefix = new byte[257];
        Arrays.fill(prefix, (byte) 'a');
        for (byte[] suffix : new byte[][]{{(byte) 0xc0, (byte) 0x80}, {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
                {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80}, {(byte) 0xe2, (byte) 0x82}}) {
            byte[] raw = concatenate(prefix, suffix);
            assertThrows(CharacterCodingException.class,
                    () -> decoder.decodePath(raw, ZipEntryNameDecoder.UTF_8_FLAG, new byte[0]));
        }
    }

    /// ASCII Unicode payloads use only the selected extra-field range and still override the legacy bytes.
    @Test
    public void asciiUnicodePayloadUsesExactRange() throws Exception {
        byte[] raw = {(byte) 0xff, (byte) 0x80};
        byte[] extra = concatenate(
                unicodeExtraField(ZipEntryNameDecoder.UNICODE_PATH_EXTRA_FIELD_ID, raw, "ascii.txt"),
                new byte[]{(byte) 0xff, (byte) 0xff, 1, 0, (byte) 0xff});
        assertEquals("ascii.txt", decoderWithUnusedDecoder().decodePath(raw, ZipEntryNameDecoder.UTF_8_FLAG, extra));
    }

    /// A matching Unicode field cannot be repaired by the legacy decoder when its UTF-8 payload is malformed.
    @Test
    public void malformedUnicodeExtraFieldBypassesLegacyPolicy() {
        byte[] raw = {(byte) 0xe9};
        byte[] extra = unicodeExtraField(ZipEntryNameDecoder.UNICODE_PATH_EXTRA_FIELD_ID, raw, "x");
        extra[9] = (byte) 0xff;
        assertThrows(CharacterCodingException.class,
                () -> decoderWithUnusedDecoder().decodePath(raw, 0, extra));
    }

    /// Returns a name decoder that rejects calls to its legacy metadata policy.
    private static ZipEntryNameDecoder decoderWithUnusedDecoder() {
        return new ZipEntryNameDecoder(bytes -> {
            throw new AssertionError("Legacy decoder must not be invoked");
        });
    }

    /// Returns the concatenation of two byte arrays.
    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /// Creates an Info-ZIP Unicode extra field.
    private static byte[] unicodeExtraField(int fieldId, byte[] rawValue, String unicodeValue) {
        byte[] unicodeBytes = unicodeValue.getBytes(StandardCharsets.UTF_8);
        int dataSize = 1 + Integer.BYTES + unicodeBytes.length;
        byte[] extraData = new byte[Short.BYTES + Short.BYTES + dataSize];

        writeShortLE(extraData, 0, fieldId);
        writeShortLE(extraData, 2, dataSize);
        extraData[4] = 1;
        writeIntLE(extraData, 5, crc32(rawValue));
        System.arraycopy(unicodeBytes, 0, extraData, 9, unicodeBytes.length);
        return extraData;
    }

    /// Returns the CRC-32 value for raw bytes.
    private static int crc32(byte[] value) {
        CRC32 crc32 = new CRC32();
        crc32.update(value);
        return (int) crc32.getValue();
    }

    /// Writes a little-endian short to a byte array.
    private static void writeShortLE(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
    }

    /// Writes a little-endian int to a byte array.
    private static void writeIntLE(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }
}
