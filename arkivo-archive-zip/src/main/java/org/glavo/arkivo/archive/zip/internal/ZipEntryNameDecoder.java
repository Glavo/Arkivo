// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.zip.ZipLegacyCharsetDetector;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.Objects;
import java.util.zip.CRC32;

import static org.glavo.arkivo.archive.zip.internal.ZipLittleEndian.readInt;

/// Decodes raw ZIP entry name bytes.
@NotNullByDefault
public final class ZipEntryNameDecoder {
    /// The general purpose bit flag that marks entry names as UTF-8.
    public static final int UTF_8_FLAG = ZipConstants.UTF8_FLAG;

    /// The Info-ZIP Unicode Path Extra Field identifier.
    public static final int UNICODE_PATH_EXTRA_FIELD_ID = 0x7075;

    /// The Info-ZIP Unicode Comment Extra Field identifier.
    public static final int UNICODE_COMMENT_EXTRA_FIELD_ID = 0x6375;

    /// The CP437 charset required by the original ZIP entry metadata encoding rules.
    private static final Charset CP437 = Charset.forName("IBM437");

    /// The detector used when no authoritative Unicode charset is available.
    private final ZipLegacyCharsetDetector legacyCharsetDetector;

    /// Creates an entry name decoder.
    public ZipEntryNameDecoder(ZipLegacyCharsetDetector legacyCharsetDetector) {
        this.legacyCharsetDetector = Objects.requireNonNull(
                legacyCharsetDetector,
                "legacyCharsetDetector"
        );
    }

    /// Decodes a raw ZIP entry path.
    public String decodePath(byte[] rawPath, int generalPurposeFlags, byte[] extraData) throws IOException {
        String unicodePath = decodeUnicodeExtraField(rawPath, extraData, UNICODE_PATH_EXTRA_FIELD_ID);
        if (unicodePath != null) {
            return unicodePath;
        }
        return decodeFallback(rawPath, generalPurposeFlags);
    }

    /// Decodes a raw ZIP entry comment, or returns `null` when no comment is present.
    public @Nullable String decodeComment(
            byte @Nullable [] rawComment,
            int generalPurposeFlags,
            byte[] extraData
    ) throws IOException {
        if (rawComment == null || rawComment.length == 0) {
            return null;
        }

        try {
            String unicodeComment =
                    decodeUnicodeExtraField(rawComment, extraData, UNICODE_COMMENT_EXTRA_FIELD_ID);
            if (unicodeComment != null) {
                return unicodeComment;
            }
            return decodeFallback(rawComment, generalPurposeFlags);
        } catch (CharacterCodingException exception) {
            throw new IOException("Failed to decode ZIP entry comment", exception);
        }
    }

    /// Decodes a validated Info-ZIP Unicode extra field value, or returns `null` when no valid field is present.
    public static @Nullable String decodeUnicodeExtraField(
            byte[] rawValue,
            byte[] extraData,
            int expectedFieldId
    ) throws IOException {
        int offset = 0;
        while (offset < extraData.length) {
            ZipExtraFields.Field field = ZipExtraFields.read(extraData, offset);
            if (field.id() == expectedFieldId) {
                String value = decodeUnicodeExtraFieldData(rawValue, extraData, field.dataOffset(), field.dataSize());
                if (value != null) {
                    return value;
                }
            }
            offset = field.nextOffset();
        }
        return null;
    }

    /// Decodes a validated Info-ZIP Unicode extra field data payload.
    private static @Nullable String decodeUnicodeExtraFieldData(
            byte[] rawValue,
            byte[] extraData,
            int dataOffset,
            int dataSize
    ) throws CharacterCodingException {
        if (dataSize < 5 || extraData[dataOffset] != 1) {
            return null;
        }

        int expectedCrc32 = readInt(extraData, dataOffset + 1);
        CRC32 crc32 = new CRC32();
        crc32.update(rawValue);
        if ((int) crc32.getValue() != expectedCrc32) {
            return null;
        }

        return strictDecode(extraData, dataOffset + 5, dataSize - 5, java.nio.charset.StandardCharsets.UTF_8);
    }

    /// Decodes raw bytes after authoritative Unicode metadata has been considered.
    private String decodeFallback(byte[] rawValue, int generalPurposeFlags) throws IOException {
        if ((generalPurposeFlags & UTF_8_FLAG) != 0) {
            return strictDecode(rawValue, java.nio.charset.StandardCharsets.UTF_8);
        }

        @Nullable Charset detectedCharset = legacyCharsetDetector.detect(
                ByteBuffer.wrap(rawValue).asReadOnlyBuffer()
        );
        return strictDecode(rawValue, detectedCharset != null ? detectedCharset : CP437);
    }

    /// Strictly decodes a complete byte array with the given charset.
    private static String strictDecode(byte[] value, Charset charset) throws CharacterCodingException {
        return strictDecode(value, 0, value.length, charset);
    }

    /// Strictly decodes a byte array range with the given charset.
    private static String strictDecode(
            byte[] value,
            int offset,
            int length,
            Charset charset
    ) throws CharacterCodingException {
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value, offset, length))
                .toString();
    }

}
