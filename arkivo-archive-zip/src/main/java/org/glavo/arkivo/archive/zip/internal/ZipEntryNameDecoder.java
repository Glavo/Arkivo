// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.glavo.arkivo.archive.zip.ZipLegacyMetadataDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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

    /// The decoder used when no authoritative Unicode charset is available.
    private final ArchiveMetadataDecoder legacyMetadataDecoder;

    /// Creates an entry name decoder.
    ///
    /// @param legacyMetadataDecoder the decoder consulted when no authoritative Unicode metadata is available
    /// @throws NullPointerException if `legacyMetadataDecoder` is `null`
    public ZipEntryNameDecoder(ArchiveMetadataDecoder legacyMetadataDecoder) {
        this.legacyMetadataDecoder = Objects.requireNonNull(
                legacyMetadataDecoder,
                "legacyMetadataDecoder"
        );
    }

    /// Decodes a raw ZIP entry path without additional header context.
    ///
    /// The input arrays are read without changing their contents.
    ///
    /// @param rawPath the encoded entry name bytes
    /// @param generalPurposeFlags the unsigned 16-bit general purpose flags
    /// @param extraData the encoded extra field records
    /// @return the decoded entry path
    /// @throws NullPointerException if `rawPath` or `extraData` is `null`
    /// @throws IOException if Unicode metadata is malformed or the legacy decoder cannot decode the path
    public String decodePath(byte[] rawPath, int generalPurposeFlags, byte[] extraData) throws IOException {
        return decodePath(
                rawPath,
                generalPurposeFlags,
                extraData,
                ZipLegacyMetadataDecoder.HeaderSource.UNKNOWN,
                ZipLegacyMetadataDecoder.UNKNOWN_HEADER_VALUE,
                ZipLegacyMetadataDecoder.UNKNOWN_HEADER_VALUE
        );
    }

    /// Decodes a raw ZIP entry path with available header context.
    ///
    /// @param rawPath the encoded entry name bytes
    /// @param generalPurposeFlags the unsigned 16-bit general purpose flags
    /// @param extraData the encoded extra field records
    /// @param headerSource the header that supplied the metadata
    /// @param versionNeededToExtract the unsigned 16-bit extraction version, or the decoder's unknown sentinel
    /// @param versionMadeBy the unsigned 16-bit creator version, or the decoder's unknown sentinel
    /// @return the decoded entry path
    /// @throws NullPointerException if an array or `headerSource` is `null`
    /// @throws IOException if Unicode metadata is malformed or the legacy decoder cannot decode the path
    public String decodePath(
            byte[] rawPath,
            int generalPurposeFlags,
            byte[] extraData,
            ZipLegacyMetadataDecoder.HeaderSource headerSource,
            int versionNeededToExtract,
            int versionMadeBy
    ) throws IOException {
        String unicodePath = decodeUnicodeExtraField(rawPath, extraData, UNICODE_PATH_EXTRA_FIELD_ID);
        if (unicodePath != null) {
            return unicodePath;
        }
        return decodeFallback(
                rawPath,
                generalPurposeFlags,
                extraData,
                ZipLegacyMetadataDecoder.MetadataKind.ENTRY_NAME,
                headerSource,
                versionNeededToExtract,
                versionMadeBy
        );
    }

    /// Decodes a raw ZIP entry comment without additional header context, or returns `null` when none is present.
    ///
    /// @param rawComment the encoded comment bytes, or `null` when absent
    /// @param generalPurposeFlags the unsigned 16-bit general purpose flags
    /// @param extraData the encoded extra field records
    /// @return the decoded comment, or `null` when absent
    /// @throws NullPointerException if `extraData` is `null`
    /// @throws IOException if Unicode metadata is malformed or the legacy decoder cannot decode the comment
    public @Nullable String decodeComment(
            byte @Nullable [] rawComment,
            int generalPurposeFlags,
            byte[] extraData
    ) throws IOException {
        return decodeComment(
                rawComment,
                generalPurposeFlags,
                extraData,
                ZipLegacyMetadataDecoder.HeaderSource.UNKNOWN,
                ZipLegacyMetadataDecoder.UNKNOWN_HEADER_VALUE,
                ZipLegacyMetadataDecoder.UNKNOWN_HEADER_VALUE
        );
    }

    /// Decodes a raw ZIP entry comment with available header context, or returns `null` when none is present.
    ///
    /// @param rawComment the encoded comment bytes, or `null` when absent
    /// @param generalPurposeFlags the unsigned 16-bit general purpose flags
    /// @param extraData the encoded extra field records
    /// @param headerSource the header that supplied the metadata
    /// @param versionNeededToExtract the unsigned 16-bit extraction version, or the decoder's unknown sentinel
    /// @param versionMadeBy the unsigned 16-bit creator version, or the decoder's unknown sentinel
    /// @return the decoded comment, or `null` when absent
    /// @throws NullPointerException if `extraData` or `headerSource` is `null`
    /// @throws IOException if Unicode metadata is malformed or the legacy decoder cannot decode the comment
    public @Nullable String decodeComment(
            byte @Nullable [] rawComment,
            int generalPurposeFlags,
            byte[] extraData,
            ZipLegacyMetadataDecoder.HeaderSource headerSource,
            int versionNeededToExtract,
            int versionMadeBy
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
            return decodeFallback(
                    rawComment,
                    generalPurposeFlags,
                    extraData,
                    ZipLegacyMetadataDecoder.MetadataKind.ENTRY_COMMENT,
                    headerSource,
                    versionNeededToExtract,
                    versionMadeBy
            );
        } catch (CharacterCodingException exception) {
            throw new IOException("Failed to decode ZIP entry comment", exception);
        }
    }

    /// Decodes a complete Info-ZIP Unicode extra field value, or returns `null` when no valid field is present.
    ///
    /// A truncated unknown trailing field is ignored, while a truncated recognized field remains malformed.
    ///
    /// @param rawValue the non-Unicode bytes whose CRC-32 must match the Unicode record
    /// @param extraData the encoded extra field records
    /// @param expectedFieldId the unsigned 16-bit Unicode extra field identifier to select
    /// @return the strictly decoded UTF-8 value, or `null` when no valid matching record is present
    /// @throws NullPointerException if `rawValue` or `extraData` is `null`
    /// @throws IOException if a recognized extra field is truncated or matching UTF-8 data is malformed
    public static @Nullable String decodeUnicodeExtraField(
            byte[] rawValue,
            byte[] extraData,
            int expectedFieldId
    ) throws IOException {
        int offset = 0;
        while (offset < extraData.length) {
            @Nullable ZipExtraFields.Field field = ZipExtraFields.readForReading(extraData, offset);
            if (field == null) {
                return null;
            }
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

        return strictDecode(extraData, dataOffset + 5, dataSize - 5, StandardCharsets.UTF_8);
    }

    /// Decodes raw bytes after authoritative Unicode metadata has been considered.
    private String decodeFallback(
            byte[] rawValue,
            int generalPurposeFlags,
            byte[] extraData,
            ZipLegacyMetadataDecoder.MetadataKind metadataKind,
            ZipLegacyMetadataDecoder.HeaderSource headerSource,
            int versionNeededToExtract,
            int versionMadeBy
    ) throws IOException {
        if ((generalPurposeFlags & UTF_8_FLAG) != 0) {
            return strictDecode(rawValue, StandardCharsets.UTF_8);
        }

        String decoded;
        if (legacyMetadataDecoder instanceof ZipLegacyMetadataDecoder zipDecoder) {
            decoded = zipDecoder.decode(new ZipLegacyMetadataDecoder.Context(
                    ByteBuffer.wrap(rawValue),
                    metadataKind,
                    headerSource,
                    generalPurposeFlags,
                    versionNeededToExtract,
                    versionMadeBy,
                    ByteBuffer.wrap(extraData)
            ));
        } else {
            decoded = legacyMetadataDecoder.decode(rawValue);
        }
        return Objects.requireNonNull(decoded, "decoded ZIP metadata");
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
        if (charset == StandardCharsets.ISO_8859_1) {
            return new String(value, offset, length, StandardCharsets.ISO_8859_1);
        }
        if (charset == StandardCharsets.UTF_8 || charset == StandardCharsets.US_ASCII) {
            int end = offset + length;
            int index = offset;
            while (index < end && value[index] >= 0) index++;
            if (index == end) {
                // These built-in charsets map ASCII identically; no replacement decoding is needed.
                return new String(value, offset, length, StandardCharsets.ISO_8859_1);
            }
        }
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value, offset, length))
                .toString();
    }
}
