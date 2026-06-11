// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.zip.ZipEntryNameEncoding;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests raw ZIP entry name decoding.
@NotNullByDefault
public final class ZipEntryNameDecoderTest {
    /// Verifies that the UTF-8 general purpose bit flag selects strict UTF-8 decoding.
    @Test
    public void utf8Flag() throws Exception {
        ZipEntryNameDecoder decoder = new ZipEntryNameDecoder(ZipEntryNameEncoding.standard());
        byte[] rawPath = "目录/文件.txt".getBytes(StandardCharsets.UTF_8);

        String path = decoder.decodePath(rawPath, ZipEntryNameDecoder.UTF_8_FLAG, new byte[0]);

        assertEquals("目录/文件.txt", path);
    }

    /// Verifies that a valid Info-ZIP Unicode Path Extra Field overrides fallback decoding.
    @Test
    public void unicodePathExtraField() throws Exception {
        ZipEntryNameDecoder decoder = new ZipEntryNameDecoder(ZipEntryNameEncoding.standard());
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
        ZipEntryNameDecoder decoder = new ZipEntryNameDecoder(ZipEntryNameEncoding.standard());
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
        ZipEntryNameDecoder decoder = new ZipEntryNameDecoder(ZipEntryNameEncoding.standard());
        byte[] rawPath = "fallback.txt".getBytes(StandardCharsets.UTF_8);

        IOException exception = assertThrows(
                IOException.class,
                () -> decoder.decodePath(rawPath, ZipEntryNameDecoder.UTF_8_FLAG, new byte[]{1, 0, 2, 0, 0})
        );

        assertEquals(true, exception.getMessage().contains("Invalid ZIP extra field length"));
    }

    /// Verifies that automatic decoding can select GB18030 from a candidate list.
    @Test
    public void autoDetectsGb18030() throws Exception {
        ZipEntryNameEncoding encoding = ZipEntryNameEncoding.auto(List.of(
                Charset.forName("GB18030"),
                ZipEntryNameEncoding.cp437()
        ));
        ZipEntryNameDecoder decoder = new ZipEntryNameDecoder(encoding);
        byte[] rawPath = "目录/文件.txt".getBytes(Charset.forName("GB18030"));

        String path = decoder.decodePath(rawPath, 0, new byte[0]);

        assertEquals("目录/文件.txt", path);
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
